package com.campusdash.application.risk;

import com.campusdash.domain.risk.model.BlacklistHits;
import com.campusdash.domain.risk.model.Cluster;
import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskContext;
import com.campusdash.domain.risk.model.RiskMode;
import com.campusdash.domain.risk.model.RiskPolicy;
import com.campusdash.domain.risk.model.RiskRuleId;
import com.campusdash.domain.risk.model.RiskRules;
import com.campusdash.domain.risk.model.UserWindow;
import com.campusdash.domain.risk.ports.RiskBlacklistPort;
import com.campusdash.domain.risk.ports.RiskFeaturePort;
import com.campusdash.domain.risk.ports.RiskLimitPort;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排层的行为锁：**哪些端口在哪条路径上被调用**。
 *
 * <p>这三种状态（关闭 / SHADOW / ENFORCE）的差别**全在调用面**上，不在返回值上：
 * {@code SHADOW} 与 {@code ENFORCE} 算出的 {@code RiskDecision} 一模一样，
 * 区别只是 SHADOW 不碰限频键。所以本类断言的重点是**调用记录**，
 * 每个 fake 都把自己被调用的每一次记进同一个 journal——
 * 于是「SHADOW 下不读限频键」从类注释里的一句声明，变成一条能红的断言
 * （与 P9.1 的 {@code RiskContextInterceptorTest} 同一手法）。
 *
 * <p>不使用 Mockito（全仓无该依赖），fake 是手写的。降级路径**只有这里能覆盖**：
 * 诱发真实 Redis 故障要么 mock 要么断连，两者都比抛异常脆（设计 §7.2）。
 *
 * <p>阈值用的是 yaml 里的生产默认值，于是场景可以直接按「6 次 / 31 次 / 9 个账号」来读，
 * 不必在脑子里换算（档位表本身由 {@code RiskRulesTest} 锁，这里不重复）。
 */
@DisplayName("风控预检用例")
class RiskPreCheckUseCaseTest {

    private static final RiskRules RULES = new RiskRules(5, 30, 3, 8, 10, 100, 10_000, "1");
    private static final RiskContext CONTEXT =
            new RiskContext(1001L, 2002L, "req-1", "dev-raw", "1.2.3.4");

    /** 三个 fake 共用一个调用流水，顺序断言才有意义。 */
    private final List<String> journal = new ArrayList<>();
    private final FakeClock clock = new FakeClock();
    private final JournalFeaturePort features = new JournalFeaturePort(journal, clock);
    private final JournalBlacklistPort blacklist = new JournalBlacklistPort(journal, clock);
    private final JournalLimitPort limit = new JournalLimitPort(journal, clock);

    /** 默认策略：不关、SHADOW 由调用方指定、fail-open 放行、10ms 预算且时钟不推进。 */
    private RiskPreCheckUseCase useCase(RiskMode mode, boolean enabled) {
        return useCase(mode, enabled, true, 10);
    }

    /**
     * 本类**一律**走假时钟（默认每步推进 0ms，即永不耗尽）。
     *
     * <p>不能用生产构造器的原因不只是「可控」：生产构造器取 {@code System::nanoTime}
     * 配 10ms 预算，跑在繁忙的机器上一次 JIT 停顿或 GC 就可能让某个检查点判定耗尽，
     * 把「SHADOW 不读限频键」这类与预算毫无关系的用例变成随机红——而它红的时候
     * 打印的是调用面不符，指向的却是完全无关的方向。
     */
    private RiskPreCheckUseCase useCase(RiskMode mode, boolean enabled,
                                        boolean failOpen, int deadlineMs) {
        return new RiskPreCheckUseCase(features, blacklist, limit, RULES,
                new RiskPolicy(enabled, mode, failOpen, deadlineMs), clock::now);
    }

    // ── 三种状态的调用面 ────────────────────────────────────────────────

    @Test
    @DisplayName("enabled=false 时一个端口都不调用")
    void disabledTouchesNoPort() {
        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, false).preCheck(CONTEXT);

        assertEquals(List.of(), journal, "风控关闭时不该有任何 I/O");
        assertEquals(RiskAction.PASS, result.effectiveAction());
        assertEquals(0, result.decision().riskScore());
        assertEquals(List.of(), result.decision().matchedRules());
        // 「关闭」不是「降级」：没有证据需要拿，也就没有没拿到的证据。
        // 把关闭记成降级会让降级指标失真，运维会去查一个不存在的故障。
        assertEquals(List.of(), result.decision().degradeReasons());
        assertFalse(result.decision().degraded());
        assertEquals(ErrorCode.OK, result.errorCode());
    }

    @Test
    @DisplayName("enabled=false 时即使模式是 ENFORCE 也不生效")
    void disabledIsNotEnforcedEvenInEnforceMode() {
        // 两个开关是「与」的关系，不是「或」。总开关是关的时候，
        // 模式是什么都不该让请求被拦下来。
        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, false).preCheck(CONTEXT);

        assertFalse(result.enforced());
        assertEquals(RiskAction.PASS, result.effectiveAction());
    }

    @Test
    @DisplayName("SHADOW 下不读也不写限频键，但黑名单与采集照常")
    void shadowSkipsLimitPortButKeepsEverythingElse() {
        RiskPreCheckResult result = useCase(RiskMode.SHADOW, true).preCheck(CONTEXT);

        // 顺序也是契约：黑名单在采集之前（被拉黑的用户不该把计数打进窗口里）
        assertEquals(List.of("blacklist.query", "feature.window", "feature.cluster"), journal);
        assertEquals(0, limit.reads, "SHADOW 下连读都不该发生");
        assertEquals(0, limit.writes);
        assertFalse(result.enforced());
    }

    @Test
    @DisplayName("SHADOW 下算得出 LIMIT，但生效动作仍是 PASS")
    void shadowComputesLimitButDoesNotEnforce() {
        features.window = new UserWindow(6, 0, false);   // 6 > 5 → USER_WINDOW_SHORT → 30 → LIMIT

        RiskPreCheckResult result = useCase(RiskMode.SHADOW, true).preCheck(CONTEXT);

        // 决策必须真算：SHADOW 阶段唯一的产出就是真实流量的决策分布，
        // 全返回 PASS 等于把「先观察再切 ENFORCE」的依据丢掉
        assertEquals(RiskAction.LIMIT, result.decision().action());
        assertEquals(30, result.decision().riskScore());
        // 但业务结果不变
        assertEquals(RiskAction.PASS, result.effectiveAction());
        assertEquals(ErrorCode.OK, result.errorCode());
        assertEquals(0, limit.writes, "SHADOW 下不得写限频键：" + journal);
    }

    @Test
    @DisplayName("SHADOW 下即使该被强拦截，业务结果也不变")
    void shadowNeverBlocks() {
        blacklist.hits = new BlacklistHits(true, false, false);

        RiskPreCheckResult result = useCase(RiskMode.SHADOW, true).preCheck(CONTEXT);

        assertEquals(RiskAction.BLOCK, result.decision().action());
        assertEquals(80, result.decision().riskScore());
        assertEquals(RiskAction.PASS, result.effectiveAction());
        assertEquals(ErrorCode.OK, result.errorCode());
    }

    // ── ENFORCE 下的读写 ───────────────────────────────────────────────

    @Test
    @DisplayName("ENFORCE 下动作是 LIMIT 才写限频键")
    void enforceWritesLimitKeyForLimit() {
        features.window = new UserWindow(6, 0, false);

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(RiskAction.LIMIT, result.effectiveAction());
        assertEquals(ErrorCode.RISK_LIMITED, result.errorCode());
        // 读在最前、写在最后，中间夹着黑名单与两次采集——顺序本身就是设计 §5.1 的执行序
        assertEquals(List.of("limit.read", "blacklist.query", "feature.window",
                "feature.cluster", "limit.write"), journal);
        assertEquals(1001L, limit.markedUserId);
    }

    @Test
    @DisplayName("ENFORCE 下 REVIEW 不写限频键")
    void enforceDoesNotWriteLimitKeyForReview() {
        features.window = new UserWindow(0, 31, false);   // 31 > 30 → USER_WINDOW_LONG → 60 → REVIEW

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(RiskAction.REVIEW, result.effectiveAction());
        assertEquals(ErrorCode.RISK_REVIEW_REQUIRED, result.errorCode());
        // 读是要读的：限频键属于 ENFORCE 的每一步，不是只有 LIMIT 才读
        assertEquals(1, limit.reads);
        assertEquals(0, limit.writes, "REVIEW 的处置不是「冷一下再放」，写进去会让同一请求被两条规则各解释一次");
    }

    @Test
    @DisplayName("ENFORCE 下 BLOCK 不写限频键")
    void enforceDoesNotWriteLimitKeyForBlock() {
        features.cluster = new Cluster(9, 0, 0);          // 9 > 8 → DEVICE_ACCOUNTS_HIGH → 80 → BLOCK

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(RiskAction.BLOCK, result.effectiveAction());
        assertEquals(ErrorCode.RISK_BLOCKED, result.errorCode());
        assertEquals(1, limit.reads);
        assertEquals(0, limit.writes);
    }

    // ── 短路 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("ENFORCE 下命中限频键直接短路：不查黑名单、不采集")
    void limitHitShortCircuits() {
        limit.limited = true;

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        // 短路省掉的是**两次往返**：黑名单一次、采集两次（用户窗口与聚集各一次）。
        // 不省掉的是写——见下一条。
        assertEquals(List.of("limit.read", "limit.write"), journal);
        assertEquals(0, blacklist.calls);
        assertEquals(0, features.windowCalls);
        assertEquals(0, features.clusterCalls);

        assertEquals(List.of(RiskRuleId.LIMIT_COOLDOWN), result.decision().matchedRules());
        assertEquals(30, result.decision().riskScore());
        assertEquals(RiskAction.LIMIT, result.effectiveAction());
    }

    @Test
    @DisplayName("短路路径仍然续写限频键——冷却期是滑动的")
    void limitHitRefreshesTheCooldown() {
        // 决策照走同一个纯函数得出 LIMIT，第 6 步的条件（ENFORCE 且 action==LIMIT）于是成立，
        // 写发生，TTL 被刷新。后果是**冷却期滑动的**：持续请求的人不会在冷却期结束时
        // 挤进一次「漏网请求」。代价是每个被限频的请求多一次 SET。
        // 「短路」省掉的是第 2~4 步（设计 §5.1 的括号里逐条列了：不采集、不查黑名单），
        // 没有省掉第 6 步。
        limit.limited = true;

        useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(1, limit.writes);
    }

    // ── 降级 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("降级不是放行：黑名单失败但设备越线，仍然 BLOCK")
    void blacklistFailureDoesNotDowngradeToPass() {
        blacklist.failure = new IllegalStateException("redis 不可用");
        features.cluster = new Cluster(9, 0, 0);

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(RiskAction.BLOCK, result.decision().action(), "降级不得改判成放行");
        assertEquals(80, result.decision().riskScore());
        assertEquals(List.of(DegradeReason.BLACKLIST_FAILED), result.decision().degradeReasons());
        assertTrue(result.decision().degraded());
    }

    @Test
    @DisplayName("黑名单查不成时按三类全未命中继续，且如实标成降级")
    void blacklistFailureFallsBackToNoHits() {
        blacklist.failure = new IllegalStateException("redis 不可用");

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        // 其余规则什么都没命中，所以动作确实是 PASS——但这与「查了、没命中」
        // 必须区分得开，否则运维看不出黑名单查询正在全军覆没
        assertEquals(RiskAction.PASS, result.decision().action());
        assertEquals(List.of(), result.decision().matchedRules());
        assertTrue(result.decision().degraded());
    }

    @Test
    @DisplayName("用户窗口采集失败按计数 0 继续")
    void userWindowFailureFallsBackToZeroCounts() {
        features.windowFailure = new IllegalStateException("redis 不可用");

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(List.of(DegradeReason.USER_WINDOW_FAILED), result.decision().degradeReasons());
        assertEquals(RiskAction.PASS, result.decision().action());
        // 窗口挂了不该顺手把聚集也跳掉：那是两次独立的调用，一个失败不代表另一个也会
        assertEquals(1, features.clusterCalls);
    }

    @Test
    @DisplayName("聚集采集失败按三个维度 0 继续")
    void clusterFailureFallsBackToEmptyCluster() {
        features.clusterFailure = new IllegalStateException("redis 不可用");

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(List.of(DegradeReason.CLUSTER_FAILED), result.decision().degradeReasons());
        assertEquals(RiskAction.PASS, result.decision().action());
    }

    @Test
    @DisplayName("限频键读失败记 LIMIT_READ_FAILED，后续步骤照常")
    void limitReadFailureIsRecordedAndPreCheckContinues() {
        limit.readFailure = new IllegalStateException("redis 不可用");

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(List.of(DegradeReason.LIMIT_READ_FAILED), result.decision().degradeReasons());
        // 读失败当作「不在冷却期」，于是整条正常路径照走
        assertEquals(List.of("limit.read", "blacklist.query", "feature.window",
                "feature.cluster"), journal);
    }

    @Test
    @DisplayName("限频键写失败记 LIMIT_WRITE_FAILED，动作不改判")
    void limitWriteFailureKeepsTheDecision() {
        features.window = new UserWindow(6, 0, false);
        limit.writeFailure = new IllegalStateException("redis 不可用");

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        // 用一次写失败去改判一个已经算对的结论，是另一种意义上的伪造决策
        assertEquals(RiskAction.LIMIT, result.decision().action());
        assertEquals(30, result.decision().riskScore());
        assertEquals(List.of(DegradeReason.LIMIT_WRITE_FAILED), result.decision().degradeReasons());
    }

    @Test
    @DisplayName("多个降级原因按枚举声明序排列，而不是发现顺序")
    void degradeReasonsAreSortedByDeclarationOrder() {
        // 发现顺序是「先黑名单、后聚集」，声明序是 CLUSTER_FAILED 在前。
        // 断言成声明序，是为了让 RiskDecision 里两个列表（matchedRules / degradeReasons）
        // 遵循同一套顺序——离线分析读它们时不必记两套。
        blacklist.failure = new IllegalStateException("redis 不可用");
        features.clusterFailure = new IllegalStateException("redis 不可用");

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(List.of(DegradeReason.CLUSTER_FAILED, DegradeReason.BLACKLIST_FAILED),
                result.decision().degradeReasons());
    }

    @Test
    @DisplayName("一切正常时不标降级")
    void happyPathIsNotDegraded() {
        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(List.of(), result.decision().degradeReasons());
        assertFalse(result.decision().degraded());
    }

    // ── 时间预算与「不可用」 ────────────────────────────────────────────

    @Test
    @DisplayName("预算为 0：一个端口都不调用，但决策照常算出、日志照常记")
    void zeroBudgetSkipsEveryPort() {
        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true, true, 0).preCheck(CONTEXT);

        assertEquals(List.of(), journal, "预算耗尽后不得再发起任何采集类调用");
        // 「没采集」不等于「没决策」：决策由已经拿到的证据（一个都没有）照常给出，
        // 只是证据不全这件事必须如实记下来
        assertEquals(RiskAction.PASS, result.decision().action());
        assertEquals(List.of(DegradeReason.DEADLINE_EXCEEDED), result.decision().degradeReasons());
        assertTrue(result.decision().degraded());
        // fail-open 默认放行：降级不等于拒绝
        assertFalse(result.denied());
        assertEquals(ErrorCode.OK, result.errorCode());
    }

    @Test
    @DisplayName("中途耗尽：第 1 步花掉预算，第 2~4 步一个都不做")
    void budgetBurntByTheFirstStepSkipsTheRest() {
        clock.stepMs = 10;   // limit.read 一次就用满 10ms 预算

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true, true, 10).preCheck(CONTEXT);

        assertEquals(List.of("limit.read"), journal, "第 1 步照跑，之后全部跳过");
        assertEquals(0, blacklist.calls);
        assertEquals(0, features.windowCalls);
        assertEquals(0, features.clusterCalls);
        // 四个检查点共用一个标记，原因只能记一次——记四次会让「这次预检证据不全」
        // 在离线统计里变成四个事件
        assertEquals(List.of(DegradeReason.DEADLINE_EXCEEDED), result.decision().degradeReasons());
    }

    @Test
    @DisplayName("第 1 步失败与「预算是否耗尽」是两件事，分别记录")
    void exhaustionIsIndependentOfWhetherTheFirstStepSucceeded() {
        clock.stepMs = 10;
        limit.readFailure = new IllegalStateException("redis 不可用");

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true, true, 10).preCheck(CONTEXT);

        // 声明序：LIMIT_READ_FAILED 在 DEADLINE_EXCEEDED 之前
        assertEquals(List.of(DegradeReason.LIMIT_READ_FAILED, DegradeReason.DEADLINE_EXCEEDED),
                result.decision().degradeReasons());
        assertEquals(0, features.windowCalls, "无论第 1 步成败，耗尽之后都不再采集");
    }

    @Test
    @DisplayName("降级不是放行：预算耗尽但黑名单已命中，仍然 BLOCK")
    void deadlineDoesNotDowngradeTheDecision() {
        clock.stepMs = 5;                                    // 第 1、2 步之后用满 10ms
        blacklist.hits = new BlacklistHits(true, false, false);

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true, false, 10).preCheck(CONTEXT);

        assertEquals(List.of("limit.read", "blacklist.query"), journal, "第 3、4 步被跳过");
        assertEquals(RiskAction.BLOCK, result.decision().action(), "少一个证据不等于放弃判断");
        assertEquals(RiskAction.BLOCK, result.effectiveAction());
        assertEquals(ErrorCode.RISK_BLOCKED, result.errorCode());
        assertFalse(result.unavailable(), "已经算出 BLOCK 的决策不能被说成「风控没结论」");
        assertTrue(result.denied());
    }

    @Test
    @DisplayName("第 6 步不受检查点管辖：预算耗尽但动作是 LIMIT，限频键仍然被写")
    void limitKeyIsStillWrittenWhenTheBudgetIsGone() {
        clock.stepMs = 4;                                 // 第 3 步之后耗尽（4×3 ≥ 10）
        features.window = new UserWindow(6, 0, false);    // 6 > 5 → LIMIT

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true, false, 10).preCheck(CONTEXT);

        assertEquals(List.of("limit.read", "blacklist.query", "feature.window", "limit.write"),
                journal, "第 4 步被跳过，但第 6 步的写入必须发生");
        assertEquals(1, limit.writes,
                "被拒了却没写限频键，等于惩罚没有依据：下一次请求会立刻再来、再判一次 LIMIT，"
                        + "日志里持续出现 LIMIT 而冷却一次都没发生");
        assertEquals(RiskAction.LIMIT, result.effectiveAction());
        // 算得出动作就不该走「不可用」那条路——第 8 步的 action==PASS 条件
        assertEquals(ErrorCode.RISK_LIMITED, result.errorCode());
    }

    @Test
    @DisplayName("fail-open=false：预算耗尽且决策为 PASS 时拒绝")
    void failOpenFalseRejectsWhenTheBudgetIsGone() {
        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true, false, 0).preCheck(CONTEXT);

        assertTrue(result.denied());
        assertEquals(ErrorCode.RISK_UNAVAILABLE, result.errorCode());
        // 决策本身没变：变的只是「这次决策算不算数」
        assertEquals(RiskAction.PASS, result.effectiveAction());
        assertEquals(RiskAction.PASS, result.decision().action());
    }

    @Test
    @DisplayName("fail-open=false 在 SHADOW 下不生效——SHADOW 的定义不能被一个降级开关改写")
    void failOpenFalseDoesNotMakeShadowReject() {
        RiskPreCheckResult result = useCase(RiskMode.SHADOW, true, false, 0).preCheck(CONTEXT);

        assertEquals(List.of(), journal);
        assertFalse(result.enforced());
        assertFalse(result.denied());
        assertEquals(ErrorCode.OK, result.errorCode());
    }

    @Test
    @DisplayName("fail-open=false 只作用于预算耗尽：端口抛异常仍然放行（D7 的回归锁）")
    void failOpenFalseDoesNotRejectLocalDegradation() {
        features.clusterFailure = new IllegalStateException("redis 不可用");

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true, false, 10).preCheck(CONTEXT);

        assertTrue(result.decision().degraded(), "确实是降级了");
        assertEquals(List.of(DegradeReason.CLUSTER_FAILED), result.decision().degradeReasons());
        assertFalse(result.unavailable(), "局部降级不是「预算耗尽」");
        assertFalse(result.denied(),
                "把 fail-open 的作用面放宽，一次 Redis 抖动就会变成全站不可用——"
                        + "而它本该只是「少一个证据」");
        assertEquals(ErrorCode.OK, result.errorCode());
    }

    // ── 事件 id（P9.4 的 D2/D3）─────────────────────────────────────────

    @Test
    @DisplayName("降级但分数为 0 的 PASS 也拿到了 id——分配必须发生在附上降级原因之后")
    void eventIdIsAllocatedAfterDegradeReasonsAreAttached() {
        // 本类里唯一能验证**第 6.5 步的位置**的用例，而位置正是那一步最容易写错的地方。
        // 判据 producesEvent() 含一项 degraded()，所以一个在第 2~4 步降级、分数仍为 0 的
        // 决策，在 withDegradeReasons **之前**看起来就是「普通 PASS、不发事件」。
        // 把分配提到前面去，这一整类会静默拿到 null——而它们恰恰是聚合闸门专门留了
        // 一类要记下来的证据。上面那些断言逐条把前提摆出来，是为了让将来这条红了的时候
        // 一眼看出是「判据变了」还是「位置错了」。
        features.clusterFailure = new IllegalStateException("redis 不可用");

        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(RiskAction.PASS, result.decision().action(), "前提：动作是 PASS");
        assertEquals(0, result.decision().riskScore(), "前提：没有任何规则命中，分数是 0");
        assertTrue(result.decision().degraded(), "前提：唯一让它该发事件的是「降级」");
        assertNotNull(result.eventId(), "降级本身就是发事件的理由，它必须拿到 id");
    }

    @Test
    @DisplayName("预算耗尽那一次也有 id——它同样是「没挡，但证据不全」")
    void exhaustedPreCheckAlsoGetsAnEventId() {
        // 与上一条同类，但走的是另一条产出降级的路径（deadline 补记而不是端口抛异常）。
        // 分开钉是因为这两处将来可能被拆开改：一处改成别的判据，另一处悄悄跟着变。
        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true, false, 0).preCheck(CONTEXT);

        assertEquals(List.of(DegradeReason.DEADLINE_EXCEEDED), result.decision().degradeReasons());
        assertNotNull(result.eventId());
    }

    @Test
    @DisplayName("普通 PASS 没有 id——绝大多数请求都是它，发出去只会把队列和离线口径一起淹掉")
    void plainPassGetsNoEventId() {
        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertFalse(result.decision().producesEvent());
        assertNull(result.eventId());
    }

    @Test
    @DisplayName("enabled=false 时不分配 id——「关闭」既不挡、也没降级")
    void disabledPreCheckGetsNoEventId() {
        // 早退路径不调 allocateEventId，只传 null。这条锁的是那个字面量：
        // 早退分支将来若被人改成「顺手也分配一个」，这一行会报出一个永不存在的 id。
        RiskPreCheckResult result = useCase(RiskMode.ENFORCE, false).preCheck(CONTEXT);

        assertNull(result.eventId());
    }

    @Test
    @DisplayName("SHADOW 下算出的 BLOCK 照样有 id——事件记的是决策，不是它算不算数")
    void shadowPreCheckGetsAnEventIdForItsBlock() {
        // SHADOW 不拦截，但**完整地算**：它的产出就是真实流量的决策分布。
        // 按 denied() 或 effectiveAction() 去判要不要发事件，会让 SHADOW 期间
        // 一条事件都不产生——而那正是我们唯一能拿来决定要不要切 ENFORCE 的数据。
        blacklist.hits = new BlacklistHits(true, false, false);

        RiskPreCheckResult result = useCase(RiskMode.SHADOW, true).preCheck(CONTEXT);

        assertFalse(result.denied(), "前提：SHADOW 下请求确实放行");
        assertEquals(RiskAction.BLOCK, result.decision().action());
        assertNotNull(result.eventId());
    }

    @Test
    @DisplayName("两次预检拿到不同的 id——它标识的是「这一次事件」")
    void everyEventGetsItsOwnId() {
        // 碰撞的后果不是报错，而是关联错：两条决策的轨迹会在离线分析里被拼成一条。
        // 这里只用两次调用做最低限度的验证——UUID v4 的碰撞概率由 JDK 保证，不必自证。
        features.window = new UserWindow(6, 0, false);   // 稳定复现 LIMIT
        // 局部变量不能叫 useCase：JLS 6.3 规定局部变量的作用域**从它自己的初始化式开始**，
        // 那样 `useCase = useCase(...)` 里的名字会解析到变量本身，编译不过。
        RiskPreCheckUseCase preCheck = useCase(RiskMode.ENFORCE, true);

        String first = preCheck.preCheck(CONTEXT).eventId();
        String second = preCheck.preCheck(CONTEXT).eventId();

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
    }

    // ── 参数透传 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("上下文的五个要素原样交给端口，缺失维度传 null 而不是空串")
    void contextIsPassedThroughToPorts() {
        useCase(RiskMode.ENFORCE, true).preCheck(CONTEXT);

        assertEquals(1001L, features.seenUserId);
        assertEquals("req-1", features.seenRequestId);
        assertEquals(1001L, blacklist.seenUserId);
        assertEquals("dev-raw", blacklist.seenDeviceId);
        assertEquals("1.2.3.4", blacklist.seenClientIp);
        assertEquals("dev-raw", features.seenDeviceId);
        assertEquals("1.2.3.4", features.seenClientIp);
    }

    @Test
    @DisplayName("设备号缺失时端口收到 null——适配器据此跳过哈希与查键")
    void missingDimensionsArriveAsNull() {
        // RiskContext 已经把空白归一成 null，用例不该再引入第二种缺失形态
        // （空串）——那会让适配器的判空两处不一致，迟早在某一处漏判。
        RiskContext blank = new RiskContext(1001L, 2002L, "req-2", "   ", null);

        useCase(RiskMode.ENFORCE, true).preCheck(blank);

        assertNull(features.seenDeviceId);
        assertNull(features.seenClientIp);
        assertNull(blacklist.seenDeviceId);
        assertNull(blacklist.seenClientIp);
        // 维度缺失不影响其余维度被采集：设备那一路跳过，用户窗口照常
        assertEquals(1, features.windowCalls);
        assertEquals(1, features.clusterCalls);
    }

    // ── fake ───────────────────────────────────────────────────────────

    /**
     * 假时钟：所有 fake 共用，**每个端口方法被调用时前进 {@link #stepMs} 一格**。
     *
     * <p>于是「第几步之后耗尽」不是靠 sleep 猜的，而是测试里写下的一个数字：
     * {@code stepMs=0} 永不耗尽（绝大多数用例用的就是这个）、{@code stepMs=10} 在第 1 步
     * 之后耗尽、{@code stepMs=4} 在第 3 步之后耗尽（预算 10ms）。
     *
     * <p>每步推进固定量而不是「只推进一次」，是为了让「哪一步耗尽的」这个断言与
     * 真实语义一致：预算耗尽是**累积**的结果，不是某一个端口的属性。
     */
    private static final class FakeClock {

        private final AtomicLong nanos = new AtomicLong();

        /** 每次端口调用推进多少毫秒。由用例设置，默认 0 = 永不耗尽。 */
        private long stepMs;

        long now() {
            return nanos.get();
        }

        void tick() {
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(stepMs));
        }
    }

    private static final class JournalFeaturePort implements RiskFeaturePort {

        private final List<String> journal;
        private final FakeClock clock;
        UserWindow window = new UserWindow(0, 0, false);
        Cluster cluster = Cluster.EMPTY;
        RuntimeException windowFailure;
        RuntimeException clusterFailure;
        long seenUserId;
        String seenRequestId;
        String seenDeviceId;
        String seenClientIp;
        int windowCalls;
        int clusterCalls;

        JournalFeaturePort(List<String> journal, FakeClock clock) {
            this.journal = journal;
            this.clock = clock;
        }

        @Override
        public UserWindow trackUserWindow(long userId, String requestId) {
            journal.add("feature.window");
            clock.tick();
            windowCalls++;
            seenUserId = userId;
            seenRequestId = requestId;
            if (windowFailure != null) {
                throw windowFailure;
            }
            return window;
        }

        @Override
        public Cluster trackCluster(long userId, String deviceId, String clientIp) {
            journal.add("feature.cluster");
            clock.tick();
            clusterCalls++;
            seenDeviceId = deviceId;
            seenClientIp = clientIp;
            if (clusterFailure != null) {
                throw clusterFailure;
            }
            return cluster;
        }
    }

    private static final class JournalBlacklistPort implements RiskBlacklistPort {

        private final List<String> journal;
        private final FakeClock clock;
        BlacklistHits hits = BlacklistHits.NONE;
        RuntimeException failure;
        long seenUserId;
        String seenDeviceId;
        String seenClientIp;
        int calls;

        JournalBlacklistPort(List<String> journal, FakeClock clock) {
            this.journal = journal;
            this.clock = clock;
        }

        @Override
        public BlacklistHits query(long userId, String deviceId, String clientIp) {
            journal.add("blacklist.query");
            clock.tick();
            calls++;
            seenUserId = userId;
            seenDeviceId = deviceId;
            seenClientIp = clientIp;
            if (failure != null) {
                throw failure;
            }
            return hits;
        }
    }

    private static final class JournalLimitPort implements RiskLimitPort {

        private final List<String> journal;
        private final FakeClock clock;
        boolean limited;
        RuntimeException readFailure;
        RuntimeException writeFailure;
        long markedUserId;
        int reads;
        int writes;

        JournalLimitPort(List<String> journal, FakeClock clock) {
            this.journal = journal;
            this.clock = clock;
        }

        @Override
        public boolean isLimited(long userId) {
            journal.add("limit.read");
            clock.tick();
            reads++;
            if (readFailure != null) {
                throw readFailure;
            }
            return limited;
        }

        @Override
        public void markLimited(long userId) {
            journal.add("limit.write");
            clock.tick();
            writes++;
            markedUserId = userId;
            if (writeFailure != null) {
                throw writeFailure;
            }
        }
    }
}
