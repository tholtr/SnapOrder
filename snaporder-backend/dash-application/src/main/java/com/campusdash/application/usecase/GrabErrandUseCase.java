package com.campusdash.application.usecase;

import com.campusdash.application.risk.RiskPreCheckResult;
import com.campusdash.application.risk.RiskPreCheckUseCase;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandQueryPort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.domain.risk.model.RiskContext;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.ports.RiskEventPort;
import com.campusdash.domain.grab.model.SlotOutcome;
import com.campusdash.domain.grab.ports.CandidateQueuePort;
import com.campusdash.domain.grab.ports.GrabRateLimiterPort;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.LongSupplier;

/**
 * 抢单用例——整个项目的心脏。
 *
 * 四层防护（架构文档 6.3）：
 *   L1 限流         —— Sentinel 热点参数限流，P7 引入
 *   L1.5 风控预检   —— 规则引擎判定，P9.3 接入（蓝图 §3.7：Sentinel 之后、信用校验之前）
 *   L2 资格前置     —— 信用分门槛与在途任务数，P5 引入完整规则
 *   L3 Redis Lua    —— 原子判定：状态校验 + 名额扣减 + 抢中者写入 + 幂等去重
 *   L4 MySQL CAS    —— 最终裁决：状态与版本号双条件 + grab_record 双唯一索引
 *
 * 关键设计：L3 成功但 L4 失败时必须回滚 Redis 名额，否则名额永久泄漏。
 * 补偿动作放在事务外执行——事务已经回滚了，补偿是独立动作。
 *
 * <h2>L1.5 的位置不是随手挑的（P9.3 设计 §6.1）</h2>
 *
 * <p><b>放在 L1 之后</b>：限流是最便宜的一道（纯内存计数），被它挡下的请求连一次风控
 * 判定都不该做。反过来放，热点任务突发时会先烧掉 2000 次风控预检再全部返回限流码。
 *
 * <p><b>放在 L2 之前</b>：风控拒绝的请求不该再查信用分与在途任务数——那两次是真实的
 * 数据库查询，而结果无论如何都不会被用到。<b>「拒绝后零后续调用」由单测的三个计数器
 * 钉住</b>，不是靠这段注释。
 *
 * <p><b>返回 {@code Result.failed(code, null)}</b>，与既有失败形态一致（{@code rank} 为
 * {@code null}）。特别是**被风控拒绝的请求不进候选队列**：{@code enqueueCandidate} 只在
 * 「抢输了」时调用，而被风控拦下不是抢输了——把这些人放进候选队列，等于让风控拦下的账号
 * 继续占有流转机会。
 *
 * <p><b>这里不给 {@code preCheck} 套 try-catch</b>：端口异常已在
 * {@code RiskPreCheckUseCase} 内部逐条转成降级原因，第 8 步之外没有能抛出来的路径。
 * 在这里再兜一层会造出一个「用例层不知道的降级语义」，两处对「什么算降级」的判断迟早分叉。
 */
@Service
public class GrabErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(GrabErrandUseCase.class);

    private final GrabSlotPort grabSlotPort;
    private final CandidateQueuePort candidateQueue;
    private final ErrandRepository errandRepository;
    private final GrabTransactionalStep transactionalStep;
    private final SnowflakeIdGenerator idGenerator;
    private final CacheEvictSupport cacheEvict;
    /** P2 起：抢中后要给抢中者一个确认窗口，超时则流转给候选人 */
    private final TimeoutTransferUseCase timeoutTransferUseCase;
    /** P5 起：资格校验读后端信用分，不再信任客户端传参 */
    private final CreditRepository creditRepository;
    private final ErrandQueryPort errandQueryPort;
    private final int minCreditScore;
    private final int maxOngoing;
    private final RealtimeNotifier notifier;
    private final GrabRateLimiterPort rateLimiter;
    /** P9.3 起：L1.5 的风控预检。参数追加在末尾，免得既有 27 处构造调用点全部要改 */
    private final RiskPreCheckUseCase riskPreCheck;
    /**
     * P9.4 起：风险事件的投递口。**必须立即返回**——抢单不等 broker 确认（蓝图 §3.9）。
     * 队列满时由适配器丢弃并记 {@code DROPPED} 行，不反馈给这里。
     */
    private final RiskEventPort riskEventPort;
    /**
     * P9.4 起：事件载荷里的 {@code occurredAt}（epoch 毫秒）。
     *
     * <p>它取的是**请求线程**的时钟，不是投递线程的：{@code occurredAt} 是「决策发生的
     * 时刻」，而投递是异步的——在投递点取时间会把队列排队的那一段算进 {@code lagMs}，
     * 而那一段已经由 {@code risk_event_publish} 行的耗时单独表达了。
     *
     * <p>接缝的用途与 {@code RiskDeadline} 的假时钟相同：{@code occurredAt} 与由它推出的
     * {@code lagMs} 是要被断言的量，没有接缝就只能断言「大于 0」，而那对「单位写错」
     * （毫秒写成纳秒）、「取错线程」两种错误都不敏感。
     */
    private final LongSupplier clock;

    /**
     * 生产构造器。<b>{@code @Autowired} 不是可省的</b>：本类有第二个构造函数
     * （下面的包私有测试接缝），而 Spring 在「多个构造函数 + 没有无参构造函数」时
     * 不会自己挑一个，启动即报 {@code No default constructor found}。
     * 与 {@code RiskPreCheckUseCase} 同一手法，理由也同一句。
     */
    @Autowired
    public GrabErrandUseCase(GrabSlotPort grabSlotPort,
                             CandidateQueuePort candidateQueue,
                             ErrandRepository errandRepository,
                             GrabTransactionalStep transactionalStep,
                             SnowflakeIdGenerator idGenerator,
                             TimeoutTransferUseCase timeoutTransferUseCase,
                              CacheEvictSupport cacheEvict,
                              CreditRepository creditRepository,
                              ErrandQueryPort errandQueryPort,
                              @org.springframework.beans.factory.annotation.Value("${dash.credit.min-score:40}") int minCreditScore,
                              @org.springframework.beans.factory.annotation.Value("${dash.credit.max-ongoing:5}") int maxOngoing,
                              RealtimeNotifier notifier,
                              GrabRateLimiterPort rateLimiter,
                              RiskPreCheckUseCase riskPreCheck,
                              RiskEventPort riskEventPort) {
        this(grabSlotPort, candidateQueue, errandRepository, transactionalStep, idGenerator,
                timeoutTransferUseCase, cacheEvict, creditRepository, errandQueryPort,
                minCreditScore, maxOngoing, notifier, rateLimiter, riskPreCheck, riskEventPort,
                System::currentTimeMillis);
    }

    /**
     * 包私有：注入假时钟，供单测确定地断言 {@code occurredAt}（P9.4 设计 §5.4）。
     *
     * <p>{@code GrabErrandUseCaseTest} 与本类同包，所以这个接缝它拿得到——
     * 与 {@code RiskPreCheckUseCase} 那条不同：那边的测试在 {@code usecase} 包、
     * 接缝在 {@code risk} 包，够不着，只能配长预算绕开真时钟。
     */
    GrabErrandUseCase(GrabSlotPort grabSlotPort,
                      CandidateQueuePort candidateQueue,
                      ErrandRepository errandRepository,
                      GrabTransactionalStep transactionalStep,
                      SnowflakeIdGenerator idGenerator,
                      TimeoutTransferUseCase timeoutTransferUseCase,
                      CacheEvictSupport cacheEvict,
                      CreditRepository creditRepository,
                      ErrandQueryPort errandQueryPort,
                      int minCreditScore,
                      int maxOngoing,
                      RealtimeNotifier notifier,
                      GrabRateLimiterPort rateLimiter,
                      RiskPreCheckUseCase riskPreCheck,
                      RiskEventPort riskEventPort,
                      LongSupplier clock) {
        this.grabSlotPort = grabSlotPort;
        this.candidateQueue = candidateQueue;
        this.errandRepository = errandRepository;
        this.transactionalStep = transactionalStep;
        this.idGenerator = idGenerator;
        this.timeoutTransferUseCase = timeoutTransferUseCase;
        this.cacheEvict = cacheEvict;
        this.creditRepository = creditRepository;
        this.errandQueryPort = errandQueryPort;
        this.minCreditScore = minCreditScore;
        this.maxOngoing = maxOngoing;
        this.notifier = notifier;
        this.rateLimiter = rateLimiter;
        this.riskPreCheck = riskPreCheck;
        this.riskEventPort = riskEventPort;
        this.clock = clock;
    }

    /**
     * creditScore 参数自 P5 起被忽略：信用分改由后端从 credit_score 读取，
     * 客户端传的分值不可信（伪造高分就能绕过资格门槛、垄断流转机会）。
     * 保留该参数只为兼容既有调用，新代码请用三参构造器。
     *
     * <p>{@code deviceId} 与 {@code clientIp} 是 P9.3 为风控预检新增的两个扁平字段
     * （设计 §6.2）。它们自此处进入应用层，**代价写在明处**：P9.1 的决策 D1 要求原始
     * 设备标识与原始 IP 只应在解析层与哈希层之间短暂存在，而本 record 是一个会被到处
     * 传递的对象。两条约束随之成立——{@link #toString()} 必须脱敏（见下），而
     * {@code equals} / {@code hashCode} 仍**包含**这两个字段：它们确实是这次调用身份的
     * 一部分，去掉会制造两个「相等」但风控结果不同的 {@code Command}。
     *
     * <p><b>为什么不让 {@code Command} 直接收一个 {@code RiskContext}</b>（另一条看起来
     * 更干净的路）：{@code RiskContext} 自带 {@code errandId}，会与本 record 的
     * {@code errandId} 成为两个可能不一致的字段，而它们不一致时没有任何地方会报错——
     * 风控按 A 任务判、抢单按 B 任务抢。两个扁平字符串没有这个缝。
     */
    public record Command(long errandId, long runnerId, String requestId, int creditScore,
                          String deviceId, String clientIp) {

        /** 三参便捷构造器：不带设备与 IP，风控的这两个维度整体跳过。 */
        public Command(long errandId, long runnerId, String requestId) {
            this(errandId, runnerId, requestId, 0, null, null);
        }

        /**
         * 四参便捷构造器，同样把设备与 IP 置 {@code null}。
         *
         * <p>两个便捷构造器**必须保留**：全仓有 27 处 {@code new Command(...)}，其中大部分
         * 在 IT 里，它们是「原有抢单正确性测试」的组成部分。改签名去逼它们逐个改，等于把
         * 一次风控接入变成一次覆盖面很广的重构。既有调用点传 {@code null} 时风控仍然照跑，
         * 只是设备与 IP 两个维度缺失（{@code RiskContext} 的紧凑构造器会把空白归一成
         * {@code null}，下游跳过对应维度而不是退化成空串）。
         */
        public Command(long errandId, long runnerId, String requestId, int creditScore) {
            this(errandId, runnerId, requestId, creditScore, null, null);
        }

        /**
         * 脱敏后的字符串表示：**不含** {@code deviceId} 与 {@code clientIp}。
         *
         * <p>record 的默认 {@code toString()} 会把全部组件原样打出来，而本 record 会被
         * 到处传——{@code GrabErrandUseCase} 的 catch 块里已经有一条 {@code log.warn}。
         * 今天它只打 {@code errandId} / {@code runnerId}，但「今天恰好没打」不是约束，
         * 一行 {@code log.info("{}", cmd)} 就能把设备标识与 IP 送进日志。
         *
         * <p>覆写是几行代码换一条**结构上**的保证：要让 PII 重新出现在日志里，必须有人
         * 主动把字段加回这个方法，而那时这条注释与对应单测都会拦一下。
         * <b>两个字段的名字也不出现</b>——留一个 {@code deviceId=***} 会让人以为它是
         * 可开关的，而这里根本没有那个开关。
         */
        @Override
        public String toString() {
            return "Command[errandId=" + errandId
                    + ", runnerId=" + runnerId
                    + ", requestId=" + requestId
                    + ", creditScore=" + creditScore + "]";
        }
    }

    public record Result(ErrorCode code, boolean grabbed, Long candidateRank) {
        static Result success() {
            return new Result(ErrorCode.OK, true, null);
        }
        static Result failed(ErrorCode code, Long rank) {
            return new Result(code, false, rank);
        }
    }

    public Result grab(Command cmd) {
        // L1：热点任务限流。放在所有下游调用之前，避免过热任务继续消耗 Redis 与 DB。
        if (!rateLimiter.tryPass(cmd.errandId(), cmd.runnerId())) {
            return Result.failed(ErrorCode.GRAB_RATE_LIMITED, null);
        }

        // L1.5：风控预检（蓝图 §3.7：Sentinel 之后、信用校验之前）。
        // 拒绝即返回——不进信用校验、不进抢单 Redis、不进 CAS。
        //
        // userId 取 cmd.runnerId()，**不取**上下文里的当前用户：注入的 Command 未必来自
        // HTTP（测试与将来的内部调用直接构造），而此时「被操作的身份」是 runnerId。
        // 用 runnerId 构造则「风控看的用户」与「抢单的用户」在类型上不可能不是同一个；
        // 用外部传进来的 userId 则是两处各自维护、靠约定相等。
        //
        // 这里**不做 try-catch**：端口异常已在 preCheck 内部逐条转成降级原因，
        // 用例层再兜一层会造出第二套「什么算降级」的语义（设计 §6.1 第 4 点）。
        RiskPreCheckResult risk = riskPreCheck.preCheck(new RiskContext(
                cmd.runnerId(), cmd.errandId(), cmd.requestId(), cmd.deviceId(), cmd.clientIp()));

        // L1.5 的事件投递（P9.4 设计 §5.4）。**必须在 denied() 之前**：
        // 被风控拒绝的请求恰恰是最该留下事件的那一类，投递放到拒绝分支之后，
        // 「挡了谁」这件事就一条都记不下来。
        publishRiskEvent(risk, cmd);

        if (risk.denied()) {
            return Result.failed(risk.errorCode(), null);
        }

        // L2：资格前置校验（P5 起读后端数据，不再信任客户端传的 creditScore）。
        // 放在 Lua 判定之前：没资格的人连名额裁决都不该参与，省 Redis 压力
        int score = creditRepository.scoreOf(cmd.runnerId());
        if (score < minCreditScore) {
            return Result.failed(ErrorCode.CREDIT_TOO_LOW, null);
        }
        if (errandQueryPort.countOngoingByRunner(cmd.runnerId()) >= maxOngoing) {
            return Result.failed(ErrorCode.TOO_MANY_ONGOING, null);
        }

        // L3：Redis Lua 原子判定。绝大多数失败请求在这一步就被挡住，不会打到数据库
        SlotOutcome outcome = grabSlotPort.tryAcquire(cmd.errandId(), cmd.runnerId(), cmd.requestId());

        switch (outcome) {
            case SLOT_FULL -> {
                return Result.failed(ErrorCode.SLOT_FULL, enqueueCandidate(cmd));
            }
            case ALREADY_GRABBED -> {
                return Result.failed(ErrorCode.ALREADY_GRABBED, null);
            }
            case DUPLICATE_REQUEST -> {
                // 幂等：同一 requestId 重复提交，返回首次的成功结果，不重复占名额
                return Result.success();
            }
            case NOT_GRABBABLE -> {
                return Result.failed(ErrorCode.ERRAND_NOT_GRABBABLE, null);
            }
            case ACQUIRED -> {
                // 继续走 L4
            }
        }

        // L4：数据库 CAS 落库，最终裁决
        try {
            Errand errand = errandRepository.findById(cmd.errandId()).orElse(null);
            if (errand == null) {
                grabSlotPort.rollback(cmd.errandId(), cmd.runnerId(), cmd.requestId());
                return Result.failed(ErrorCode.ERRAND_NOT_FOUND, null);
            }

            int seq = errand.slotTaken() + 1;
            boolean ok = transactionalStep.lockAndRecord(
                    errand.campusId(), cmd.errandId(), cmd.runnerId(), errand.version(), seq, errand.round(),
                    idGenerator.nextId(), errand.status());
            if (!ok) {
                // CAS 冲突或唯一索引冲突：Redis 已扣名额，必须补回去
                grabSlotPort.rollback(cmd.errandId(), cmd.runnerId(), cmd.requestId());
                return Result.failed(ErrorCode.GRAB_CONFLICT, enqueueCandidate(cmd));
            }
            // 抢中成功，给这一轮开启确认窗口：超时未确认则自动流转给候选人。
            // 放在事务提交之后：登记本身在 Step 的独立事务里，发送是外部 IO 不该进业务事务。
            timeoutTransferUseCase.scheduleFirstTimeout(
                    cmd.errandId(), errand.round(), errand.version() + 1);
            // 状态 PUBLISHED -> LOCKED、slotTaken+1，详情缓存必须失效。
            // 只在真正抢中时失效；上面 DUPLICATE_REQUEST 的幂等重放不改状态，无需失效
            cacheEvict.evictAfterCommit(cmd.errandId());
            // 实时推送给发单人（抢单者自己已知结果）
            Errand e2 = errandRepository.findById(cmd.errandId()).orElse(null);
            if (e2 != null) {
                notifier.errandStatusChanged(e2.id(), e2.publisherId(), e2.grabberId(),
                        e2.status().name(), e2.round());
            }
            return Result.success();
        } catch (RuntimeException e) {
            // 任何未预期异常都要回滚 Redis 名额，否则名额泄漏，任务永久卡在"已被抢"
            grabSlotPort.rollback(cmd.errandId(), cmd.runnerId(), cmd.requestId());
            log.warn("抢单落库异常，已回滚 Redis 名额 errandId={} runnerId={}", cmd.errandId(), cmd.runnerId(), e);
            return Result.failed(ErrorCode.GRAB_CONFLICT, null);
        }
    }

    /**
     * 把这次预检的事件组装并投出去（P9.4 设计 §5.4）。
     *
     * <p><b>判据是 {@code producesEvent()}，不是 {@code denied()}</b>：两者在
     * {@code SHADOW} 下正好相反——SHADOW 不拦人，但**完整地算**，而它的产出就是真实流量
     * 的决策分布。按 {@code denied()} 判会让 SHADOW 期间一条事件都不产生，
     * 那正是我们唯一能拿来决定要不要切 {@code ENFORCE} 的数据。
     *
     * <p>这里问的是 {@code decision().producesEvent()} 而不是 {@code eventId() != null}：
     * 取前者是因为设计 §5.3 把「同一判据在两侧都被调用」写成了分工的一部分——
     * 判据只有一处实现（{@code RiskDecision}），两个调用点各自问一次。
     * 注意两者**不保证等价**：{@link RiskPreCheckResult} 的构造器只拦「判据成立却没有 id」
     * 那一个方向，「有 id 却不产生事件」是允许存在的形态（那种情况该不该投，由这里说了算）。
     *
     * <p><b>为什么在这里兜一层 try-catch，而 {@code preCheck} 那处刻意不兜</b>：
     * 那处的依据是「端口异常已被 {@code preCheck} 内部逐条转成降级原因」——一条已经被
     * 穷尽验证过的路径。投递不同，它是异步链路的入口，而它要保证的事情是
     * 「抢单结果与投递成败无关」（蓝图 §3.9）。这条保证不能建立在「适配器不会抛」上：
     * 适配器今天确实不抛，但那是它的实现细节，而这里要的是**结构上**成立的边界。
     * 一次队列满的时序变化、一次依赖升级都可能让它抛出来，代价是整条抢单链路 500。
     *
     * <p>与 {@link RiskEventPort} 类注释里那句「不抛领域异常」不矛盾：那是给实现方的
     * 要求，这里是给调用方的兜底。两者都写，是因为这条路径上「异常」的代价**不对称**——
     * 投递丢一条只是少一条遥测，抢单抛出去是一次用户可见的失败。
     *
     * <p>warn 而不是 error：这不是故障，是「一条遥测没发出去」。而且真发生时多半是
     * 系统性的（队列持续满），按请求打 error 会在故障期间把日志淹掉——与
     * {@code RiskPreCheckUseCase} 里「不为每次降级单独打 warn」是同一条理由。
     * 两个 id 是刻意选的：{@code cmd.toString()} 虽然已经脱敏，但这里不需要它带的那两个
     * 字段，也就没有任何理由让它们出现在一个 catch 块里。
     */
    private void publishRiskEvent(RiskPreCheckResult risk, Command cmd) {
        if (!risk.decision().producesEvent()) {
            return;
        }
        // 在进入 try 之前取：occurredAt 是**决策**发生的时刻，不该因为投递抛了异常
        // 就变成「没取到时间」——那一行日志虽然记不成 publish，风险事件本身仍然有
        // 一个有意义的时间戳（何况它还可能被适配器的重试路径再用一次）。
        long occurredAt = clock.getAsLong();
        try {
            riskEventPort.publish(risk.toEvent(RiskOperationType.GRAB, cmd.requestId(),
                    cmd.errandId(), cmd.runnerId(), occurredAt));
        } catch (RuntimeException e) {
            log.warn("风险事件投递失败，抢单结果不受影响 errandId={} runnerId={}",
                    cmd.errandId(), cmd.runnerId(), e);
        }
    }

    /**
     * 抢单失败者进候选队列，为 P2 的超时流转做准备。
     * score 越小越优先：用时间戳减去信用分加权，让高信用用户略微占先，
     * 加权上限避免高信用用户完全垄断流转机会。
     */
    private Long enqueueCandidate(Command cmd) {
        // 信用分权重从后端读（入口处已查过一次，这里再查是为拿最新值——
        // 单主键查询成本极低，换来的是不依赖入口快照的正确性）
        int credit = creditRepository.scoreOf(cmd.runnerId());
        double creditBonus = Math.min(credit, 100) * 10.0;
        double score = System.currentTimeMillis() - creditBonus;
        candidateQueue.offer(cmd.errandId(), cmd.runnerId(), score);
        return candidateQueue.size(cmd.errandId());
    }
}
