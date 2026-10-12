package com.campusdash.application.risk;

import com.campusdash.domain.risk.model.BlacklistHits;
import com.campusdash.domain.risk.model.Cluster;
import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskContext;
import com.campusdash.domain.risk.model.RiskDecision;
import com.campusdash.domain.risk.model.RiskMode;
import com.campusdash.domain.risk.model.RiskPolicy;
import com.campusdash.domain.risk.model.RiskRules;
import com.campusdash.domain.risk.model.UserWindow;
import com.campusdash.domain.risk.ports.RiskBlacklistPort;
import com.campusdash.domain.risk.ports.RiskFeaturePort;
import com.campusdash.domain.risk.ports.RiskLimitPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 风控预检编排：把「读限频键 → 查黑名单 → 采集特征 → 决策 → 写限频键 → 记日志」
 * 串成一个用例（设计 §5.1 的执行顺序 D10）。
 *
 * <p><b>本类是全仓第一个构造 {@link RiskContext} 的地方</b>。P9.1 的拦截器只交付
 * {@code RiskRequest}，因为它在 {@code preHandle} 阶段拿不到 {@code errandId}；
 * 到了用例层这个值已经有了，五要素才第一次凑齐。
 *
 * <h2>三种状态不是两两组合</h2>
 *
 * <table border="1">
 *   <caption>各开关下实际发生的调用</caption>
 *   <tr><th></th><th>采集</th><th>查黑名单</th><th>读限频键</th><th>写限频键</th><th>拦截</th></tr>
 *   <tr><td>{@code enabled=false}</td><td>❌</td><td>❌</td><td>❌</td><td>❌</td><td>❌</td></tr>
 *   <tr><td>{@code mode=SHADOW}</td><td>✅</td><td>✅</td><td>❌</td><td>❌</td><td>❌</td></tr>
 *   <tr><td>{@code mode=ENFORCE}</td><td>✅</td><td>✅</td><td>✅</td><td>✅</td><td>✅</td></tr>
 * </table>
 *
 * <p><b>「关掉」与「SHADOW」不能合并</b>：{@code enabled=false} 一次 I/O 都不做，
 * 而 {@code SHADOW} 要**完整地算**——它的产出就是真实流量的决策分布。
 * 反过来，{@code SHADOW} 下限频键**连读取都不发生**（不是读了再忽略），
 * 因为限频键一旦在 SHADOW 下被写入，切到 ENFORCE 时就会凭空出现一批
 * 「之前被限过频」的用户，而这些人从没被拦过（{@link RiskLimitPort} 类注释）。
 *
 * <h2>降级不是放行</h2>
 *
 * <p>任何一个端口抛异常，都只记一个 {@link DegradeReason} 然后**当作该项缺失继续算**：
 * 黑名单挂了就当没命中、窗口挂了就当计数为 0。决策照常给出，分数照常累加——
 * 黑名单失败但设备维度已经越线时仍然是 {@code BLOCK}。
 * 写成「有降级就返回 PASS」会漏掉这次拦截，那是把一次 Redis 抖动放大成一次漏防。
 *
 * <p><b>为什么不为每次降级单独打一条 warn 日志</b>：本类已经有一条更好的通道——
 * {@code risk_decision} 行里的 {@code degraded} / {@code degradeReasons} 字段。
 * 而 Redis 故障是**系统性**的，按请求打 warn 会在故障期间把日志淹掉；
 * 与 {@code RiskContextInterceptor} 的情况不同（那边的失败没有任何别的记录通道，
 * 且发生频率低），这里不缺记录手段，缺的只是堆栈——而根因在 Redis 客户端自己的日志里。
 *
 * <h2>时间预算与「不可用」的第三态（P9.3）</h2>
 *
 * <p>预检带一个毫秒级预算，第 1~4 步之前各有一个检查点；预算耗尽后**不再发起**任何
 * 采集类调用，决策由已经拿到的证据给出。这与「降级不是放行」是同一条不变式：
 * 耗尽只意味着少了一个证据，已经越线的证据仍然会把动作判成 {@code BLOCK}。
 *
 * <p>唯一被预算影响处置的地方是第 8 步：{@code ENFORCE} + {@code fail-open=false}
 * + 预算耗尽 + 决策仍为 {@code PASS} 时，这次预检被标成「不可用」，调用方据此返回
 * {@link com.campusdash.shared.ErrorCode#RISK_UNAVAILABLE}。第 6 步（写限频键）
 * **不受检查点管辖**，理由见 {@link #preCheck(RiskContext)} 里的注释。
 *
 * <p><b>本阶段没有生产调用方</b>（设计 §7.4）：只有测试调用。这是如实记录的状态，
 * 不是缺陷；端到端行为要等 P9.3 接入抢单链路后才能观察。
 */
@Service
public class RiskPreCheckUseCase {

    /** 采集失败时的用户窗口。"0 次"与"没采到"在这里等价——两者都不该命中任何窗口规则。 */
    private static final UserWindow NO_WINDOW = new UserWindow(0, 0, false);

    private final RiskFeaturePort featurePort;
    private final RiskBlacklistPort blacklistPort;
    private final RiskLimitPort limitPort;
    private final RiskRules rules;
    private final RiskPolicy policy;
    private final LongSupplier clock;

    /**
     * 生产构造器。运行策略整体注入（P9.3 决策 D10），不再分两处读同一个 key：
     * P9.2 时 {@code enabled} 走 {@code @Value}、{@code mode} 走领域 bean，同一个
     * {@code dash.risk.enabled} 有两处默认值，只能靠注释维系一致（F-17）；
     * {@code RiskContextInterceptor} 那处是第三份。收成 {@link RiskPolicy} 之后，
     * 真值来源只有 {@code RiskProperties} 一个。
     *
     * <p><b>{@code @Autowired} 不是可省的</b>：本类有第二个构造函数（下面的包私有
     * 测试接缝），而 Spring 在「多个构造函数 + 没有无参构造函数」时不会自己挑一个，
     * 启动即报 {@code No default constructor found}。整个仓库只有这一处需要它，
     * 因为只有这里有「同一个对象、生产与单测各一套构造路径」的需求。
     */
    @Autowired
    public RiskPreCheckUseCase(RiskFeaturePort featurePort,
                               RiskBlacklistPort blacklistPort,
                               RiskLimitPort limitPort,
                               RiskRules rules,
                               RiskPolicy policy) {
        this(featurePort, blacklistPort, limitPort, rules, policy, System::nanoTime);
    }

    /**
     * 包私有：注入假时钟，供单测确定地构造「第几步之后耗尽」（P9.3 设计 §5.1）。
     *
     * <p>预算是 10ms 量级，靠 {@code sleep} 去造「第 3 步之后耗尽」会抖，而「哪一步
     * 之后耗尽」决定了调用面与 {@code degradeReasons} 的全部断言，必须是确定的。
     * 假时钟由测试的 fake 端口持有：端口被调用时把时钟推进过去，于是「第几步之后
     * 耗尽」就变成了测试里写下的一个数字。
     */
    RiskPreCheckUseCase(RiskFeaturePort featurePort,
                        RiskBlacklistPort blacklistPort,
                        RiskLimitPort limitPort,
                        RiskRules rules,
                        RiskPolicy policy,
                        LongSupplier clock) {
        this.featurePort = featurePort;
        this.blacklistPort = blacklistPort;
        this.limitPort = limitPort;
        this.rules = rules;
        this.policy = policy;
        this.clock = clock;
    }

    public RiskPreCheckResult preCheck(RiskContext context) {
        long startedAt = System.nanoTime();

        // 第 0 步：关掉时连日志都不走"评估过了"的形态——规则版本仍带上，
        // 好让离线分析能把「关闭期间」和「开着但全 PASS」分开。
        if (!policy.enabled()) {
            RiskDecision off = RiskDecision.pass(rules.ruleVersion());
            // eventId 传 null 而不是分配一个：关闭时不产生事件（见 RiskDecision.pass 的
            // 注释——「关闭」不是「降级」，两个触发条件都不成立）。这里也不必调
            // allocateEventId：那会走一遍判据，而判据的结果是已知的。
            RiskDecisionLog.emit(context.userId(), context.errandId(), context.requestId(),
                    off, null, elapsedMs(startedAt));
            // 第三态恒为 false：关闭既不是「拦了」也不是「不可用」。
            // （两个相邻的 boolean 写反不会静默：RiskPreCheckResult 的不变式当场抛。）
            return new RiskPreCheckResult(off, false, false, null);
        }

        boolean enforce = policy.mode() == RiskMode.ENFORCE;
        List<DegradeReason> reasons = new ArrayList<>(2);
        RiskDeadline deadline = RiskDeadline.after(policy.deadlineMs(), clock);

        // 检查点（P9.3 设计 §5.2）：第 1~4 步之前各一次。预算耗尽后**不再发起**
        // 任何采集类调用，决策则照常由已经拿到的证据给出——「降级不是放行」在
        // deadline 语境下是同一条不变式：少了一个证据，不等于放弃判断。
        // 标记用 `|=` 累积，只增不减：一旦耗尽，后面的检查点不会把它重置回 false。
        boolean budgetExhausted = deadline.expired();

        // 第 1 步：限频键只在 ENFORCE 下读。SHADOW 下**不读**（而不是读了再忽略）——
        // 与 P9.1 测试头开关的双闸门禁同型：少一次「值已经进了内存、只差一个判断写错」。
        boolean limitHit = false;
        if (enforce && !budgetExhausted) {
            limitHit = readLimited(context, reasons);
        }

        UserWindow window = NO_WINDOW;
        Cluster cluster = Cluster.EMPTY;
        BlacklistHits blacklist = BlacklistHits.NONE;

        // 第 2~4 步：命中限频键就短路——本次请求无论如何都是拒绝，
        // 再花两次往返去查黑名单与采集特征不会改变处置。
        //
        // 代价说清楚：若该用户同时也在黑名单里，**这一次**的日志记的是 LIMIT 而不是
        // BLOCK。这不是漏判——被黑名单拦下的用户根本不会写限频键（第 6 步只写 LIMIT），
        // 所以能走到这里的人上一次的决策必然不是 BLOCK；等冷却期一过，
        // 下一次预检就会正常查到黑名单并记成 BLOCK。
        //
        // 每步之前的那一行就是检查点，顺序与 P9.2 完全一致（黑名单 → 用户窗口 → 聚集），
        // 插入的只有检查点本身。
        if (!limitHit) {
            budgetExhausted |= deadline.expired();
            if (!budgetExhausted) {
                blacklist = queryBlacklist(context, reasons);
            }
            budgetExhausted |= deadline.expired();
            if (!budgetExhausted) {
                window = trackUserWindow(context, reasons);
            }
            budgetExhausted |= deadline.expired();
            if (!budgetExhausted) {
                cluster = trackCluster(context, reasons);
            }
        }

        // 第 5 步：决策。短路路径也走同一个纯函数，而不是自己拼一个
        // 「分数 30 / 动作 LIMIT」——档位表只有一份，绕开它就会在改档位时漏改这里。
        RiskDecision decision = rules.evaluate(window, cluster, blacklist, limitHit);

        // 第 6 步：只有 LIMIT 才写限频键。REVIEW / BLOCK 的处置不是「冷一下再放」，
        // 写进去会让同一个请求在冷却期内被两条规则分别解释一次。
        //
        // **这一步不受检查点管辖**：它执行的是一个已经做出的决定，不是采集证据。
        // 跳过它会造出一个很坏的组合——请求被拒了（用户受到了惩罚），但冷却期没生效
        // （惩罚的依据没了），于是下一次请求立刻到来、窗口里计数照旧越线、再判一次
        // LIMIT：日志里持续出现 action=LIMIT 而冷却一次都没发生过。让它写进去，
        // LIMIT 的语义才完整：这一次拒绝 + 接下来冷却一段。
        if (enforce && decision.action() == RiskAction.LIMIT) {
            markLimited(context, reasons);
        }

        // 预算耗尽在所有步骤之后补记**一次**：它由四个检查点中的任意一个触发，
        // 逐个检查点去记会产出四个同值的 DEADLINE_EXCEEDED，而「这一次预检证据不全」
        // 只发生了一次（设计 §8.1 要求它恰好出现一次）。
        if (budgetExhausted) {
            reasons.add(DegradeReason.DEADLINE_EXCEEDED);
        }

        // 降级原因在所有步骤跑完之后一次性附上：决策在算的过程中每加一个原因
        // 就重建一次 {@link RiskDecision} 是没必要的（分数与动作不因降级而改变）。
        decision = decision.withDegradeReasons(inDeclarationOrder(reasons));

        // 第 6.5 步：分配事件 id（P9.4 的 D3）。
        //
        // **位置很要紧**：必须在 withDegradeReasons **之后**。判据是 producesEvent()，
        // 而它含一项 degraded()——一个在第 2~4 步降级、但分数仍为 0 的决策，
        // 在附上降级原因之前看起来是「普通 PASS、不发事件」。把分配提到前面，
        // 这类决策就会拿到 null，而它的证据恰恰是最需要记下来的那种。
        String eventId = allocateEventId(decision);

        // 第 7 步：决策日志行。**短路路径也记**——「这次为什么是 LIMIT」和
        // 「这次算了半天为什么是 PASS」同等重要，少了短路那次，日志里会出现
        // 一个只有 PASS 的假分布。
        RiskDecisionLog.emit(context.userId(), context.errandId(), context.requestId(),
                decision, eventId, elapsedMs(startedAt));

        // 第 8 步：整体不可用判定（P9.3 决策 D7）。四个条件缺一不可，每个都有反向用例：
        //   enforce          —— 去掉它，SHADOW 下会开始拒绝请求，而 SHADOW 的定义就是不拦截
        //   !failOpen        —— 去掉它，默认配置被悄悄改成 fail-closed
        //   budgetExhausted  —— 去掉它，一次普通的局部降级（黑名单查询失败）就会拒绝整次请求
        //   action == PASS   —— 去掉它，预算耗尽但证据已经足够 BLOCK 时会被改判放行，
        //                       即「降级不是放行」在 deadline 语境下被违反
        //
        // 被判为不可用的那一次，上面的日志行里仍然是 action=PASS：那**就是**决策的真实
        // 内容（决策是分数的纯函数），变的只是「这次决策算不算数」。两者都能从同一行读
        // 出来（degraded=true degradeReasons=DEADLINE_EXCEEDED action=PASS，加上调用方
        // 拿到的结果码）；为了对齐两者去给日志加字段，等于动一份已经冻结的跨系统契约
        // （P9.2 的 D8）。
        boolean unavailable = enforce && !policy.failOpen() && budgetExhausted
                && decision.action() == RiskAction.PASS;

        return new RiskPreCheckResult(decision, enforce, unavailable, eventId);
    }

    /**
     * 这次决策要不要一个事件 id（P9.4 的 D3）。
     *
     * <p><b>判据不在这里</b>，在 {@link RiskDecision#producesEvent()}——这里是它唯一的
     * 调用点。判据写成两份的表现是「某一类决策静默地不再产生事件」，不报错、不告警。
     *
     * <p>{@code UUID.randomUUID().toString()} 而不是自增/时间戳：三个抢单实例之间不共享
     * 任何东西，而 eventId 要跨实例唯一才能当关联凭据。UUID v4 的碰撞概率在这里
     * 可以忽略，且不需要一次网络往返或一段共享状态——那两样都会把一个日志用的 id
     * 变成抢单链路上的依赖。
     *
     * <p>不用 {@code DeviceHasher} 那类哈希：eventId 不需要可逆、也不需要稳定
     * （同一个请求重算一次就该得到不同的 id，它标识的是**这一次事件**）。
     */
    private static String allocateEventId(RiskDecision decision) {
        return decision.producesEvent() ? UUID.randomUUID().toString() : null;
    }

    private boolean readLimited(RiskContext context, List<DegradeReason> reasons) {
        try {
            return limitPort.isLimited(context.userId());
        } catch (RuntimeException e) {
            // 读失败与「键不存在」是两回事：无法判断该用户是否在冷却期，
            // 只能当作没命中继续——可能放过一个本该被限频的请求，所以必须记进原因。
            reasons.add(DegradeReason.LIMIT_READ_FAILED);
            return false;
        }
    }

    private BlacklistHits queryBlacklist(RiskContext context, List<DegradeReason> reasons) {
        try {
            return blacklistPort.query(context.userId(), context.deviceId(), context.clientIp());
        } catch (RuntimeException e) {
            // 查不到就当三类都没命中。**这里绝不能改判成放行**：黑名单挂了只丢一个
            // 加分项，而设备/IP 聚集那几条规则照常参与决策（见类注释「降级不是放行」）。
            reasons.add(DegradeReason.BLACKLIST_FAILED);
            return BlacklistHits.NONE;
        }
    }

    private UserWindow trackUserWindow(RiskContext context, List<DegradeReason> reasons) {
        try {
            return featurePort.trackUserWindow(context.userId(), context.requestId());
        } catch (RuntimeException e) {
            reasons.add(DegradeReason.USER_WINDOW_FAILED);
            return NO_WINDOW;
        }
    }

    private Cluster trackCluster(RiskContext context, List<DegradeReason> reasons) {
        try {
            return featurePort.trackCluster(context.userId(), context.deviceId(), context.clientIp());
        } catch (RuntimeException e) {
            reasons.add(DegradeReason.CLUSTER_FAILED);
            return Cluster.EMPTY;
        }
    }

    private void markLimited(RiskContext context, List<DegradeReason> reasons) {
        try {
            limitPort.markLimited(context.userId());
        } catch (RuntimeException e) {
            // 决策是对的，冷却期没生效——同一用户会立刻再来一次。
            // 不能因此把动作升级成 BLOCK：那等于用一次写失败去改判一个已经算对的结论。
            reasons.add(DegradeReason.LIMIT_WRITE_FAILED);
        }
    }

    /**
     * 按枚举**声明序**排列降级原因。
     *
     * <p>发现顺序是执行顺序（读限频键在最前），而声明序把它们排成
     * 「用户窗口 → 聚集 → 黑名单 → 限频读 → 限频写」。{@link RiskDecision} 里
     * {@code matchedRules} 已经是声明序，让 {@code degradeReasons} 也一致，
     * 离线分析读这两个列表时就不必记两套顺序。
     */
    private static List<DegradeReason> inDeclarationOrder(List<DegradeReason> reasons) {
        List<DegradeReason> ordered = new ArrayList<>(reasons);
        ordered.sort(Comparator.comparingInt(r -> r.ordinal()));
        return ordered;
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
