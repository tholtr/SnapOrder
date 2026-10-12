package com.campusdash.application.usecase;

import com.campusdash.application.risk.RiskPreCheckUseCase;
import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditScore;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandQueryPort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.model.SlotOutcome;
import com.campusdash.domain.grab.ports.CandidateQueuePort;
import com.campusdash.domain.grab.ports.GrabRateLimiterPort;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.risk.model.BlacklistHits;
import com.campusdash.domain.risk.model.Cluster;
import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskMode;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.model.RiskOutcome;
import com.campusdash.domain.risk.model.RiskPolicy;
import com.campusdash.domain.risk.model.RiskRules;
import com.campusdash.domain.risk.model.UserWindow;
import com.campusdash.domain.risk.ports.RiskBlacklistPort;
import com.campusdash.domain.risk.ports.RiskEventPort;
import com.campusdash.domain.risk.ports.RiskFeaturePort;
import com.campusdash.domain.risk.ports.RiskLimitPort;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P9.3 接入点（L1.5）的行为锁：**风控在哪一步生效、拒绝之后还有没有别的调用**。
 *
 * <p>本类的断言重心是**调用流水**（每个 fake 把每一次调用记进同一个 journal），
 * 而不是返回值。理由：风控接错位置的典型后果不是「返回了错的码」，而是
 * 「拒绝是拒绝了，但信用分已经查了、Redis 名额已经扣了」——只看返回值这一层根本看不出来。
 * 与 {@code RiskPreCheckUseCaseTest} 同一手法。
 *
 * <p><b>不 fake {@link RiskPreCheckUseCase} 本身</b>：它是个具体类，子类化要调一个塞满
 * {@code null} 的父构造器，而那会把「接入点的顺序」测成「我 stub 了什么」。这里用
 * 真编排 + fake 端口，被测的是真实的那条链路，包括端口抛异常时它内部的降级处理——
 * 于是「异常不外溢」那条测的是真行为，不是「我没让它抛」。
 *
 * <p>不使用 Mockito（全仓无该依赖），fake 全部手写。
 */
@DisplayName("抢单用例（风控接入）")
class GrabErrandUseCaseTest {

    /** 与 yaml 生产默认值同源。阈值本身由 {@code RiskRulesTest} 锁，这里不重复。 */
    private static final RiskRules RULES = new RiskRules(5, 30, 3, 8, 10, 100, 10_000, "1");

    @Test
    @DisplayName("热点限流拒绝后直接返回限流码，不再访问信用分和名额裁决")
    void grab_returns_rate_limited_before_downstream_calls() {
        Fixture f = new Fixture();
        f.rateLimiter.pass = false;
        // 风控这块配成「会拒绝」：把顺序写反（风控跑到限流前面）时，
        // 返回的会是 RISK_BLOCKED 而不是 GRAB_RATE_LIMITED，这条当场红。
        f.risk.blacklist = new BlacklistHits(true, false, false);

        GrabErrandUseCase.Result result = f.useCase().grab(
                new GrabErrandUseCase.Command(10001L, 2001L, "req-limited"));

        assertFalse(result.grabbed());
        assertEquals(ErrorCode.GRAB_RATE_LIMITED, result.code());
        assertEquals(List.of("rateLimiter.tryPass"), f.journal, "限流之后不该再有调用，风控也不该跑");
        assertEquals(1, f.rateLimiter.calls);
        assertEquals(0, f.credit.scoreCalls, "限流后不应查询信用分");
        assertEquals(0, f.slot.tryAcquireCalls, "限流后不应打 Redis Lua");
    }

    @Test
    @DisplayName("风控拒绝：返回风控码，且信用仓储、Redis 名额、事务落库三个计数器全为 0")
    void riskDenialTouchesNothingDownstream() {
        Fixture f = new Fixture();
        // 用户黑名单命中 = 80 分 = BLOCK 档（分值即档位下界，见 RiskAction.BLOCK_MIN）。
        // 走黑名单而不是限频键，是因为限频键只在 ENFORCE 下才读，用它会把这条用例
        // 和模式耦合在一起；黑名单两种模式下都查，测的才是「拒绝点的位置」本身。
        f.risk.blacklist = new BlacklistHits(true, false, false);

        GrabErrandUseCase.Result result = f.useCase().grab(new GrabErrandUseCase.Command(
                10001L, 2001L, "req-blocked", 60, "dev-raw", "1.2.3.4"));

        assertFalse(result.grabbed());
        assertEquals(ErrorCode.RISK_BLOCKED, result.code());
        assertNull(result.candidateRank(), "被风控拒绝不是抢输了，不该进候选队列");
        assertEquals(0, f.credit.scoreCalls, "风控拒绝后不该查信用分");
        assertEquals(0, f.slot.tryAcquireCalls, "风控拒绝后不该打 Redis Lua");
        assertEquals(0, f.tx.lockCalls, "风控拒绝后不该落库");

        // 反过来也要钉住：上面四个 0 不能靠「什么都没跑」来实现。
        // 风控自己的采集链路必须完整跑完——拒绝是**算出来**的，不是短路出来的。
        //
        // 末位的 risk.publish 就是 P9.4 那条投递（在 denied() 分支**之前**）：
        // 把它挪到拒绝分支之后，这一条会红——而被挡下的请求恰恰是最该留下事件的那一类。
        assertEquals(List.of(
                "rateLimiter.tryPass",
                "risk.limit.isLimited",
                "risk.blacklist.query",
                "risk.feature.window",
                "risk.feature.cluster",
                "risk.publish"), f.journal);
    }

    @Test
    @DisplayName("风控放行：原有链路逐步照常走完，一路推进到 L4 落库")
    void riskPassKeepsTheOriginalChain() {
        Fixture f = new Fixture();

        GrabErrandUseCase.Result result = f.useCase().grab(new GrabErrandUseCase.Command(
                10001L, 2001L, "req-pass", 60, "dev-raw", "1.2.3.4"));

        // 断言的是**每一步都按原顺序发生**，不是只看到达了终点：
        // 少一步（比如风控顺手把在途任务数那次查询省掉）同样会让下面的列表对不上。
        assertEquals(List.of(
                "rateLimiter.tryPass",
                "risk.limit.isLimited",
                "risk.blacklist.query",
                "risk.feature.window",
                "risk.feature.cluster",
                "credit.scoreOf",
                "errand.countOngoing",
                "slot.tryAcquire",
                "errand.findById",
                "tx.lockAndRecord",
                "slot.rollback",
                "credit.scoreOf",
                "candidate.offer",
                "candidate.size"), f.journal);

        // fake 的 CAS 返回 false，所以到这里是 GRAB_CONFLICT——**这正是重点**：
        // 它证明风控放行后请求真的走到了 L4 落库，而不是在某一层被风控的返回值挡住了。
        // 落库成功后的三个协作者（确认窗口、缓存失效、实时推送）需要另外两个重依赖的
        // fake 才能到达，而它们与「风控接入」无关，由 IT 覆盖（设计 §10）。
        assertEquals(ErrorCode.GRAB_CONFLICT, result.code());
        assertEquals(1, f.tx.lockCalls);
        // 一路放行的普通 PASS 没有事件可发：它是绝大多数请求的结果，发出去只会把
        // 队列和离线口径一起淹掉。上面那份 journal 里因此没有 risk.publish。
        assertEquals(List.of(), f.riskEvents.published);
    }

    @Test
    @DisplayName("风控关闭：即使黑名单用户也照常走完，行为与接入前一致")
    void disabledRiskKeepsTheOriginalChain() {
        Fixture f = new Fixture();
        f.risk.enabled = false;
        // 与「风控拒绝」那条用同一份黑名单命中数据，只翻总开关。
        // 两条用例的 journal 必须一模一样——这正是「关闭 = 接入前」的定义。
        f.risk.blacklist = new BlacklistHits(true, false, false);

        GrabErrandUseCase.Result result = f.useCase().grab(new GrabErrandUseCase.Command(
                10001L, 2001L, "req-disabled", 60, "dev-raw", "1.2.3.4"));

        assertEquals(List.of(
                "rateLimiter.tryPass",
                "credit.scoreOf",
                "errand.countOngoing",
                "slot.tryAcquire",
                "errand.findById",
                "tx.lockAndRecord",
                "slot.rollback",
                "credit.scoreOf",
                "candidate.offer",
                "candidate.size"), f.journal, "关闭时一个风控端口都不该被调用");
        assertEquals(ErrorCode.GRAB_CONFLICT, result.code());
    }

    @Test
    @DisplayName("风控端口抛异常不外溢：grab 照常返回，链路继续走完")
    void riskPortFailureDoesNotLeakOutOfGrab() {
        Fixture f = new Fixture();
        f.risk.blacklistFailure = new IllegalStateException("Redis 不可用");

        // 用例里**没有** try-catch 包着 preCheck（设计 §6.1 第 4 点）。
        // 异常必须在 preCheck 内部被转成降级原因——这条断言就是那句声明的锁：
        // 一旦有人在 GrabErrandUseCase 里补一层兜底，这里不会红，但
        // RiskPreCheckUseCaseTest 的降级断言会先红，两处语义不会各走各的。
        GrabErrandUseCase.Result result = assertDoesNotThrow(() -> f.useCase().grab(
                new GrabErrandUseCase.Command(10001L, 2001L, "req-degraded", 60, "dev-raw", "1.2.3.4")));

        assertEquals(ErrorCode.GRAB_CONFLICT, result.code(), "降级后决策是 PASS，请求照常放行");
        assertTrue(f.journal.contains("risk.blacklist.query"),
                "必须能看出这次降级是「查了但失败了」，而不是「压根没查」");
        assertEquals(1, f.tx.lockCalls, "黑名单挂了只丢一个加分项，不该拦住整个请求");
    }

    // ── 事件投递（P9.4 设计 §5.4）───────────────────────────────────────

    @Test
    @DisplayName("被拦下的那次投出一条事件，字段逐个来自这次调用")
    void deniedGrabPublishesOneEvent() {
        Fixture f = new Fixture();
        f.risk.blacklist = new BlacklistHits(true, false, false);
        f.clock.nowMs = 1_757_600_000_123L;

        f.useCase().grab(new GrabErrandUseCase.Command(
                10001L, 2001L, "req-blocked", 60, "dev-raw", "1.2.3.4"));

        assertEquals(1, f.riskEvents.published.size(), "判据成立时**恰好一次**");
        RiskEvent event = f.riskEvents.published.get(0);
        // 逐字段断言，不是只看列表非空：载荷是跨系统的契约，字段串位（比如
        // errandId 与 runnerId 写反）在两端都不会报错，只会在离线统计里表现为
        // 「某个任务的风险特别高」。
        assertEquals("req-blocked", event.requestId());
        assertEquals(RiskOperationType.GRAB, event.operationType());
        assertEquals(10001L, event.errandId());
        assertEquals(2001L, event.runnerId());
        assertEquals(RiskOutcome.DENIED, event.outcome());
        assertEquals(RiskAction.BLOCK, event.decisionAction());
        assertFalse(event.degraded());
        assertEquals(List.of(), event.degradeReasons());
        assertEquals(1_757_600_000_123L, event.occurredAt());
        // eventId 非空白即可：它是预检分配的那个 UUID，本类看不见预检的返回值。
        // 「载荷里的 id 就是结果里那个、不是新生成的」由 RiskPreCheckResultTest 锁
        // （toEvent 只从自己的 eventId 组件取值），两边不重复。
        assertFalse(event.eventId().isBlank(), "没有 id 就没法和 risk_decision 行对上");
    }

    @Test
    @DisplayName("降级但放行：事件照发，outcome=PASSED 且 degraded=true")
    void degradedPassPublishesAPassedEvent() {
        // 这是判据里最容易漏掉的一半：**没挡，但证据不全**。按 action 判要不要发事件
        // 会让这一整类消失，而聚合闸门专门留了 DEGRADED 一类，正是为了记它。
        Fixture f = new Fixture();
        f.risk.blacklistFailure = new IllegalStateException("Redis 不可用");

        GrabErrandUseCase.Result result = f.useCase().grab(new GrabErrandUseCase.Command(
                10001L, 2001L, "req-degraded", 60, "dev-raw", "1.2.3.4"));

        assertEquals(ErrorCode.GRAB_CONFLICT, result.code(), "前提：请求照常放行到了 L4");
        assertEquals(1, f.riskEvents.published.size());
        RiskEvent event = f.riskEvents.published.get(0);
        assertEquals(RiskOutcome.PASSED, event.outcome());
        assertEquals(RiskAction.PASS, event.decisionAction());
        assertTrue(event.degraded());
        assertEquals(List.of(DegradeReason.BLACKLIST_FAILED), event.degradeReasons());
    }

    @Test
    @DisplayName("SHADOW：请求放行，但事件记的是决策真算出来的 BLOCK")
    void shadowGrabPublishesTheComputedBlock() {
        // 这条钉的是 outcome 的**判据**：它跟 decisionAction 走，不跟 denied() 走。
        // 按 denied() 写，SHADOW 下会是 outcome=PASSED 而 decisionAction=BLOCK——
        // 一份自相矛盾的载荷，而 SHADOW 期间全部事件都会长成这样。
        // 而 SHADOW 的产出（真实流量的决策分布）正是切 ENFORCE 的唯一依据，
        // 恰恰是这一段时间的载荷最不能出错。
        Fixture f = new Fixture();
        f.risk.mode = RiskMode.SHADOW;
        f.risk.blacklist = new BlacklistHits(true, false, false);

        GrabErrandUseCase.Result result = f.useCase().grab(new GrabErrandUseCase.Command(
                10001L, 2001L, "req-shadow", 60, "dev-raw", "1.2.3.4"));

        assertEquals(ErrorCode.GRAB_CONFLICT, result.code(), "前提：SHADOW 不拦人，请求走完了");
        assertEquals(1, f.riskEvents.published.size(), "SHADOW 的产出就是这些事件，一条都不能少");
        RiskEvent event = f.riskEvents.published.get(0);
        assertEquals(RiskAction.BLOCK, event.decisionAction());
        assertEquals(RiskOutcome.DENIED, event.outcome(), "outcome 跟 decisionAction，不跟 denied()");
    }

    @Test
    @DisplayName("投递抛异常不外溢：抢单照常返回，链路继续走完")
    void publishFailureDoesNotAffectGrab() {
        // 蓝图 §3.9 要求「事件投递不得阻塞抢单」，而这条比「不阻塞」更强：
        // 它要在投递**失败**时也成立。GrabErrandUseCase 里那层 try-catch 就是为它写的，
        // 而 RiskEventPort 的类注释里那句「不抛异常」是给实现方的要求，不是这里的依据——
        // 要求靠不住的时候，兜底必须在调用方这一侧。
        Fixture f = new Fixture();
        f.risk.blacklist = new BlacklistHits(true, false, false);
        f.riskEvents.failure = new IllegalStateException("队列满之后适配器又抛了");

        GrabErrandUseCase.Result result = assertDoesNotThrow(() -> f.useCase().grab(
                new GrabErrandUseCase.Command(10001L, 2001L, "req-boom", 60, "dev-raw", "1.2.3.4")));

        // 用「拒绝后零下游调用」那条的同一份配置：投递抛了异常，风控该拒的仍然拒，
        // 而且拒绝之后仍然一个下游调用都没有。
        assertEquals(ErrorCode.RISK_BLOCKED, result.code());
        assertEquals(List.of(
                "rateLimiter.tryPass",
                "risk.limit.isLimited",
                "risk.blacklist.query",
                "risk.feature.window",
                "risk.feature.cluster",
                "risk.publish"), f.journal);
        assertEquals(0, f.credit.scoreCalls);
        assertEquals(0, f.slot.tryAcquireCalls);
        assertEquals(0, f.tx.lockCalls);
    }

    // ── 装配 ────────────────────────────────────────────────────────────

    /**
     * 一次抢单的完整装配。所有 fake 共用一个 journal，于是「哪一步发生过、以什么顺序」
     * 从注释里的声明变成可断言的事实。
     *
     * <p>默认配置是「一路放行并推进到 L4」：限流通过、风控无命中、
     * 名额裁决返回 {@code ACQUIRED}、任务存在、CAS 冲突（{@code tx} 默认返回 false，
     * 停在落库这一步，不碰需要重依赖的两个后置协作者）。
     */
    private static final class Fixture {
        final List<String> journal = new ArrayList<>();
        final FakeRiskPorts risk = new FakeRiskPorts(journal);
        final RecordingCreditRepository credit = new RecordingCreditRepository(journal);
        final RecordingGrabSlotPort slot = new RecordingGrabSlotPort(journal);
        final RecordingTransactionalStep tx = new RecordingTransactionalStep(journal);
        final RecordingErrandRepository errands = new RecordingErrandRepository(journal);
        final RecordingErrandQueryPort query = new RecordingErrandQueryPort(journal);
        final RecordingCandidateQueue candidates = new RecordingCandidateQueue(journal);
        final RecordingRateLimiter rateLimiter = new RecordingRateLimiter(journal);
        final RecordingRiskEventPort riskEvents = new RecordingRiskEventPort(journal);
        final FakeClock clock = new FakeClock();

        GrabErrandUseCase useCase() {
            return new GrabErrandUseCase(
                    slot, candidates, errands, tx,
                    new SnowflakeIdGenerator(1),
                    // 确认窗口与缓存失效停在「落库成功之后」，本类的用例都到不了那里
                    // （tx 默认返回 false）。传 null 是**刻意**的：它们若被调用会立刻
                    // NPE，于是「某个用例意外走过了落库」这件事不会静默通过。
                    null,
                    null,
                    credit, query, 40, 5, new NoopRealtimeNotifier(),
                    rateLimiter,
                    risk.useCase(),
                    riskEvents,
                    // 假时钟：occurredAt 是要被逐字断言的量，用真时钟就只能断言「大于 0」
                    clock);
        }
    }

    /**
     * 投递口的假实现：只记录，**不做任何异步**。
     *
     * <p>真适配器的「立即返回」「队列满即丢」在这里验证不了（那要 broker 才谈得上），
     * 本类能锁的是**调用面**——判据成立时恰好一次、不成立时零次、事件字段是什么。
     * 跨线程与闸门那部分由 {@code RocketMqRiskEventAdapterTest} 覆盖，两者不重复。
     */
    private static final class RecordingRiskEventPort implements RiskEventPort {
        private final List<String> journal;
        final List<RiskEvent> published = new ArrayList<>();
        /** 置上就让 {@link #publish} 抛——用来验「投递失败不影响抢单结果」。 */
        RuntimeException failure;

        RecordingRiskEventPort(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public void publish(RiskEvent event) {
            // 先记流水再抛，与 FakeBlacklistPort 同一手法：「发过但失败了」与「压根没发」
            // 必须能区分开——而投递失败恰恰是最容易被读成「没发」的一种。
            journal.add("risk.publish");
            published.add(event);
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * 假时钟：一个能被测试写死的毫秒值。
     *
     * <p>默认值是个真实的 epoch 毫秒（2025-09-11T…Z），不是 0：{@code occurredAt} 若被写成
     * 纳秒或秒，与它做减法的 {@code lagMs} 会得到荒谬的值，而「非 0」这个断言看不出区别。
     */
    private static final class FakeClock implements LongSupplier {
        long nowMs = 1_757_600_000_000L;

        @Override
        public long getAsLong() {
            return nowMs;
        }
    }

    // ── 风控侧 fake ─────────────────────────────────────────────────────

    /**
     * 风控侧的三块假端口 + **真规则、真编排**。
     *
     * <p>预算取 10 秒而不是生产默认的 10ms：本类测的是**接入点**，不是预算。
     * 10ms 配真时钟（包私有构造器在别的包，单测拿不到那个假时钟接缝），跑在繁忙机器上
     * 一次 GC 停顿就可能让某个检查点判定耗尽，把「拒绝后零后续调用」变成随机红——
     * 而它红的时候打印的是调用面不符，指向完全无关的方向。
     */
    private static final class FakeRiskPorts {
        private final List<String> journal;
        BlacklistHits blacklist = BlacklistHits.NONE;
        RuntimeException blacklistFailure;
        boolean enabled = true;
        RiskMode mode = RiskMode.ENFORCE;

        FakeRiskPorts(List<String> journal) {
            this.journal = journal;
        }

        RiskPreCheckUseCase useCase() {
            return new RiskPreCheckUseCase(
                    new FakeFeaturePort(journal),
                    new FakeBlacklistPort(journal, this),
                    new FakeLimitPort(journal),
                    RULES,
                    new RiskPolicy(enabled, mode, true, 10_000));
        }
    }

    private static final class FakeFeaturePort implements RiskFeaturePort {
        private final List<String> journal;

        FakeFeaturePort(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public UserWindow trackUserWindow(long userId, String requestId) {
            journal.add("risk.feature.window");
            return new UserWindow(0, 0, false);
        }

        @Override
        public Cluster trackCluster(long userId, String deviceId, String clientIp) {
            journal.add("risk.feature.cluster");
            return Cluster.EMPTY;
        }
    }

    private static final class FakeBlacklistPort implements RiskBlacklistPort {
        private final List<String> journal;
        private final FakeRiskPorts owner;

        FakeBlacklistPort(List<String> journal, FakeRiskPorts owner) {
            this.journal = journal;
            this.owner = owner;
        }

        @Override
        public BlacklistHits query(long userId, String deviceId, String clientIp) {
            // 先记流水再抛：故障场景下也要能区分「查了但失败」与「压根没查」。
            journal.add("risk.blacklist.query");
            if (owner.blacklistFailure != null) {
                throw owner.blacklistFailure;
            }
            return owner.blacklist;
        }
    }

    private static final class FakeLimitPort implements RiskLimitPort {
        private final List<String> journal;

        FakeLimitPort(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public boolean isLimited(long userId) {
            journal.add("risk.limit.isLimited");
            return false;
        }

        @Override
        public void markLimited(long userId) {
            journal.add("risk.limit.markLimited");
        }
    }

    // ── 下游 fake ───────────────────────────────────────────────────────

    private static final class RecordingRateLimiter implements GrabRateLimiterPort {
        private final List<String> journal;
        int calls;
        /** 默认放行；置 false 用来测「限流在最前」那条顺序。 */
        boolean pass = true;

        RecordingRateLimiter(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public boolean tryPass(long errandId, long runnerId) {
            calls++;
            journal.add("rateLimiter.tryPass");
            return pass;
        }
    }

    private static final class RecordingCreditRepository implements CreditRepository {
        private final List<String> journal;
        int scoreCalls;

        RecordingCreditRepository(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public int scoreOf(long userId) {
            scoreCalls++;
            journal.add("credit.scoreOf");
            return 60;
        }

        @Override public Optional<CreditScore> find(long userId) { return Optional.empty(); }
        @Override public boolean applyEvent(CreditEvent event) { return false; }
        @Override public List<CreditEvent> recentEvents(long userId, int days, int limit) { return List.of(); }
        @Override public int windowDelta(long userId, int windowDays) { return 0; }
        @Override public int calibrateScores(int windowDays, int limit) { return 0; }
    }

    private static final class RecordingGrabSlotPort implements GrabSlotPort {
        private final List<String> journal;
        int tryAcquireCalls;

        RecordingGrabSlotPort(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public SlotOutcome tryAcquire(long errandId, long runnerId, String requestId) {
            tryAcquireCalls++;
            journal.add("slot.tryAcquire");
            return SlotOutcome.ACQUIRED;
        }

        @Override
        public void rollback(long errandId, long runnerId, String requestId) {
            journal.add("slot.rollback");
        }

        @Override public void initSlot(long errandId, int slotTotal, long ttlSeconds) {}
        @Override public long remainingSlot(long errandId) { return 0; }
    }

    /**
     * {@link GrabTransactionalStep} 是具体类，所以这里继承并覆写它唯一的公开方法。
     *
     * <p>父构造器要一个 {@code GrabRecordRepository}，传 {@code null} 是安全的：
     * 用到它的只有被覆写的那个方法。默认返回 {@code false}（CAS 冲突），
     * 于是用例停在落库这一步，不触碰需要重依赖的确认窗口与缓存失效。
     */
    private static final class RecordingTransactionalStep extends GrabTransactionalStep {
        private final List<String> journal;
        int lockCalls;

        RecordingTransactionalStep(List<String> journal) {
            super(new RecordingErrandRepository(journal), null);
            this.journal = journal;
        }

        @Override
        public boolean lockAndRecord(long campusId, long errandId, long runnerId, long expectedVersion,
                                     int seq, int round, long recordId, ErrandStatus fromStatus) {
            lockCalls++;
            journal.add("tx.lockAndRecord");
            return false;
        }
    }

    private static final class RecordingErrandRepository implements ErrandRepository {
        private final List<String> journal;
        /** 默认存在一个 PUBLISHED 的单名额任务，好让链路能走到落库那一步。 */
        private final Errand errand = Errand.rehydrate(10001L, 1L, 900L, ErrandType.DELIVERY,
                "帮取快递", Money.ofCents(500), 1, null, ErrandStatus.PUBLISHED, 0, 0, 0L, null);

        RecordingErrandRepository(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public Optional<Errand> findById(long errandId) {
            journal.add("errand.findById");
            return Optional.of(errand);
        }

        @Override public void insert(Errand errand) {}
        @Override public int casLockForRunner(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casPublish(long errandId, long expectedVersion) { return 0; }
        @Override public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to, int round, long operatorId) {}
        @Override public int casAccept(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casTransferToNext(long errandId, long nextRunnerId, long expectedVersion, int expectedRound) { return 0; }
        @Override public int casRevertToPublished(long errandId, long expectedVersion, int expectedRound) { return 0; }
        @Override public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) { return List.of(); }
        @Override public int casPickUp(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casDeliver(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casSettle(long errandId, long expectedVersion) { return 0; }
        @Override public int casRefundFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casCancel(long errandId, long expectedVersion) { return 0; }
        @Override public int casDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casSettleFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) { return List.of(); }
    }

    private static final class RecordingErrandQueryPort implements ErrandQueryPort {
        private final List<String> journal;

        RecordingErrandQueryPort(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public int countOngoingByRunner(long runnerId) {
            journal.add("errand.countOngoing");
            return 0;
        }

        @Override public List<Errand> list(long campusId, String status, int page, int size) { return List.of(); }
        @Override public List<CursorItem> listByCursor(long campusId, String status, java.time.Instant beforeCreatedAt, Long beforeId, int size) { return List.of(); }
        @Override public List<Errand> listByPublisher(long publisherId, int page, int size) { return List.of(); }
        @Override public List<Errand> listByRunner(long runnerId, int page, int size) { return List.of(); }
        @Override public List<StatusChange> statusLog(long campusId, long errandId) { return List.of(); }
        @Override public List<Long> sampleIds(int limit) { return List.of(); }
    }

    private static final class RecordingCandidateQueue implements CandidateQueuePort {
        private final List<String> journal;

        RecordingCandidateQueue(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public void offer(long errandId, long runnerId, double score) {
            journal.add("candidate.offer");
        }

        @Override
        public Optional<Long> pollBest(long errandId) {
            return Optional.empty();
        }

        @Override
        public long size(long errandId) {
            journal.add("candidate.size");
            return 0;
        }
    }

    private static final class NoopRealtimeNotifier implements RealtimeNotifier {
        @Override public void errandStatusChanged(long errandId, long publisherId, Long grabberId, String status, int round) {}
        @Override public void notificationArrived(long userId, long errandId, String type, String content) {}
        @Override public void creditChanged(long userId, int newScore, int delta, String reason) {}
    }
}
