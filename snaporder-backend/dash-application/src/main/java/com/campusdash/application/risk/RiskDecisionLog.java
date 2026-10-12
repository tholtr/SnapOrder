package com.campusdash.application.risk;

import com.campusdash.domain.risk.model.RiskDecision;
import com.campusdash.shared.LogField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一次风险决策的结果行（设计 §5.2）。字段 camelCase；离线分析脚本负责映射到
 * snake_case 指标，所以<b>字段名与顺序是跨系统的契约，改动等于改协议</b>。
 *
 * <pre>
 * risk_decision userId=... errandId=... requestId=... action=... riskScore=...
 *               matchedRules=USER_WINDOW_SHORT|USER_WINDOW_LONG ruleVersion=1
 *               degraded=false degradeReasons=- eventId=- decisionLatencyMs=3
 * </pre>
 *
 * <p>{@code eventId} 有两种形态（P9.4 的 D3）：这次决策产生了风险事件时它是**分配到的
 * 那个 id**，没有事件时是 {@code -}。它是本行与 {@code risk_event_publish} 行、
 * 事件载荷、{@code risk_event_processed} 行之间唯一的凭据——四处的 eventId 相同，
 * 一条决策的完整轨迹才能拼起来；对不上就没有第二条线索能补救。
 *
 * <p><b>为什么不记特征计数</b>：短路路径（命中限频键、被黑名单拦下）**根本没有采集**，
 * 此时打 {@code count10s=0} 是伪造数据——「未采集」与「采集到 0」是两回事，
 * 后者会让人以为该用户真的没有请求过。要看计数请用 {@code risk_feature} 行。
 *
 * <p><b>为什么入参是整条 {@link RiskDecision}、而不是拆开的 action / riskScore / matchedRules</b>：
 * 这三个值在 {@code RiskDecision} 里有一条不可破坏的一致性约束（动作必须是总分的纯函数）。
 * 拆成三个平铺参数就等于在日志这一侧把那条约束重新放开，而一份
 * {@code action=BLOCK riskScore=20} 的日志是没人能解释的——它与「一条被伪造的行」
 * 长得一模一样。
 *
 * <p><b>安全约束</b>（P9.1 的 D1）：本行以及所有风控日志不得包含原始 {@code deviceId} /
 * {@code clientIp}。{@link RiskDecision} 里本来就没有这两个值，所以这条在类型层面
 * 即成立，而不是靠每次改代码时记得住。
 */
public final class RiskDecisionLog {

    private static final Logger log = LoggerFactory.getLogger(RiskDecisionLog.class);

    private RiskDecisionLog() {
    }

    /**
     * 拼出日志行。单独拆出来是为了能在单测里断言整行文本——
     * 本仓库没有日志捕获依赖，把格式做成纯函数是唯一不引依赖的测法。
     *
     * <p>{@code decisionLatencyMs} 由调用方测量（{@link RiskPreCheckUseCase}），
     * 这里是纯透传：这条日志本身不做计时。
     *
     * <p><b>{@code eventId} 从常量变成参数，是 P9.2 的 D8 预料到的那一次改动</b>：
     * 当时把它写死成 {@code -} 而不是开一个恒收 {@code null} 的参数，理由写在
     * 那段注释里——「等真有 eventId 时，改签名会让编译器把所有调用点找出来」。
     * 现在就是这么发生的：签名一改，两个调用点编译不过，谁都没漏。
     *
     * @param eventId 本次决策对应的风险事件 id，**没有事件时为 {@code null}**。
     *                判据是 {@link RiskDecision#producesEvent()}：为真时调用方分配一个
     *                id，为假时传 {@code null}。它过 {@link LogField#safe} 不是为了防
     *                伪造（这个值由应用自己生成，不经过客户端），而是让 {@code null}
     *                与空白落到同一个 {@code -} 上——「没有事件」只该有一种呈现方式，
     *                为此多写一个三元表达式只会多出一个可能写反的地方
     */
    public static String format(long userId, long errandId, String requestId,
                                RiskDecision decision, String eventId, long decisionLatencyMs) {
        return "risk_decision userId=" + userId
                + " errandId=" + errandId
                + " requestId=" + LogField.safe(requestId)
                + " action=" + decision.action()
                + " riskScore=" + decision.riskScore()
                + " matchedRules=" + join(decision.matchedRules())
                + " ruleVersion=" + LogField.safe(decision.ruleVersion())
                + " degraded=" + decision.degraded()
                + " degradeReasons=" + join(decision.degradeReasons())
                + " eventId=" + LogField.safe(eventId)
                + " decisionLatencyMs=" + decisionLatencyMs;
    }

    /** 决策完成后调用。 */
    public static void emit(long userId, long errandId, String requestId,
                            RiskDecision decision, String eventId, long decisionLatencyMs) {
        log.info(format(userId, errandId, requestId, decision, eventId, decisionLatencyMs));
    }

    /**
     * 按枚举**声明序**以 {@code |} 连接；空集合渲染为 {@code -}。
     *
     * <p>{@code ruleVersion} 不在其列——它虽是自由文本却只有单个值，不参与连接，
     * 所以单独过 {@link LogField#safe}。
     *
     * <p><b>为什么要在这里再排一次序</b>：{@code RiskRules.evaluate} 本来就按声明序
     * 产出，{@code RiskRulesTest} 也锁了那个顺序。这里排序是为了**日志行自身的稳定性
     * 不依赖上游**——P9.3/P9.4 会引入别的决策构造路径，那时一条顺序不同的
     * {@code matchedRules} 会让离线脚本按位置切分时静默产出错指标。
     * 注意这里是**排序**而不是去重：日志如实反映决策携带的规则集合，
     * 真出现重复条目就该在日志里看见，而不是被悄悄抹平。
     */
    private static <E extends Enum<E>> String join(List<E> values) {
        if (values.isEmpty()) {
            return LogField.EMPTY;
        }
        List<E> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.comparingInt(e -> e.ordinal()));

        StringBuilder joined = new StringBuilder();
        for (E value : ordered) {
            if (joined.length() > 0) {
                // 枚举名由代码定义，不含 "|" 与空白，所以这个分隔符无法被值本身伪造
                joined.append('|');
            }
            joined.append(value.name());
        }
        return joined.toString();
    }
}
