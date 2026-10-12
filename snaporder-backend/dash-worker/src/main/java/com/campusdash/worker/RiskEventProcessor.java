package com.campusdash.worker;

import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.infrastructure.risk.RiskEventConsumeLog;
import com.campusdash.infrastructure.risk.RiskEventInvalidReason;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * 一条风险事件的消费处理（P9.4 设计 §6.2）。
 *
 * <h2>为什么它不是消费者的一部分</h2>
 *
 * <p>{@link RiskEventConsumer} 要连 broker，所以它的任何一条路径都要有 broker 才走得动。
 * 而本阶段真正需要被覆盖的四条路径——正常、待审、格式非法、处理失败——**全是纯逻辑**，
 * 与 broker 无关。拆出来之后，这四条路径的测试跑在一个不连任何中间件的 JVM 里，
 * 而消费者退化成三行「连上、把 body 交出去、把结果还回去」。
 *
 * <h2>D12 在代码上的形态：这个类不落任何状态</h2>
 *
 * <p>构造器里没有 {@code StringRedisTemplate}、没有 {@code JdbcTemplate}，字段里也没有。
 * 这不是「这一轮先不写」，而是重投递语义的前提：风险事件的消费端**不做任何需要去重的
 * 事**，所以同一条消息被投递两次的结果就是**两行一样的日志**——蓝图 §5.3 的
 * 「重投递只产生重复日志」在代码上就是「这个类没有任何可被写坏的东西」。
 * 去重口径由离线脚本按 {@code requestId + operationType} 做（设计 §8.3），
 * 而不是由消费端做——消费端一旦落状态，「去重」就变成了一致性问题，
 * 而这个问题在 P9.4 的范围之外。
 *
 * <p>这条性质是**结构性**的，所以它由 {@code RiskEventProcessorTest} 用反射钉住：
 * 「将来有人为了别的需求往这个构造器里塞一个 repository」是这类约束唯一会失效的方式，
 * 而它在代码评审里看起来总像一次合理的扩展。
 *
 * <h2>两个 try 块的边界</h2>
 *
 * <p>解析单独一块，后面两块合一块。理由只有一个：**判据是异常类型**（D10
 * 把「格式非法」定义成 {@code IllegalArgumentException}）。若把三段合进同一个
 * {@code catch}，一次**可重投**的处理失败如果恰好也抛了 {@code IllegalArgumentException}，
 * 就会被静默地 ACK 掉——那是一条比「多写一行 invalid 日志」严重得多的错误。
 * 分开之后，「解析期抛的 IAE」= 消息本身坏了，一律丢弃；「解析之后抛的任何异常」=
 * 这次处理没成功，交给 broker 重投。
 *
 * <h2>日志里不放 body</h2>
 *
 * <p>四条出口都会记日志，但**没有一条打印原始 body**（设计 §6.2）：body 里有
 * {@code requestId}，而原始 body 的长度与转义都不受控，往日志里塞一段外部文本正是
 * D13 要堵的通路。要排查内容，拿 {@code eventId} 去 topic 上查那条消息。
 *
 * <p>本类**自己**只补了一行 {@code log.error}（处理失败时），它不是设计里的五条日志行——
 * 设计只说了「其余异常 → {@code FAILURE}」，没有说这一路上要不要留下痕迹。补它的理由是：
 * 若不补，一次处理失败在日志上**完全没有痕迹**，只有 broker 的重试计数会涨，
 * 而重试计数在三次之后就不见了（D11）。它只带 {@code eventId} 与 {@code deliveryAttempt}，
 * 两者都是应用自己生成/框架给的值，不含客户端内容。
 */
@Component
public class RiskEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(RiskEventProcessor.class);

    private final LongSupplier clock;

    /**
     * 生产构造器。<b>{@code @Autowired} 不是可省的</b>：本类有第二个构造函数
     * （下面的包私有测试接缝），Spring 在「多个构造函数 + 没有无参构造函数」时不会自己挑，
     * 启动即报 {@code No default constructor found}。与 {@code GrabErrandUseCase} 同一手法。
     */
    @Autowired
    public RiskEventProcessor() {
        this(System::currentTimeMillis);
    }

    /**
     * 包私有：注入假时钟，供单测确定地断言 {@code lagMs}，也是唯一能让「处理失败」
     * 这条路径被走到的东西——本类没有别的可注入依赖，而失败必须来自某处。
     */
    RiskEventProcessor(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * 处理一条消息，返回该给 broker 的应答。
     *
     * <p>包级可见：调用它的只有同包的 {@link RiskEventConsumer} 与测试（设计 §6.2 的签名
     * 没有修饰符）。这不是「内部实现」——它是这个类唯一有意义的行为，可见性只表达
     * 「除了这个进程里的消费者，没人该直接喂它一条消息」。
     *
     * @param deliveryAttempt broker 给的投递次数，原样进日志行：重投递的第 2 次必须
     *                        与第 1 次分得开，否则「同一条消息出现了两次」既可能是重投递、
     *                        也可能是生产端重复发，两种情况的处置完全不同
     */
    ConsumeResult process(String body, int deliveryAttempt) {
        RiskEvent event;
        try {
            event = RiskEventPayload.parse(body);
        } catch (IllegalArgumentException e) {
            // D10：格式非法**丢弃**。重投一条字段缺失的消息，三次之后它还是同一条消息，
            // 走完重试链只会把重试队列堵住（D11 的取值理由正是这一段）。
            RiskEventConsumeLog.emitInvalid(e, reasonOf(e), deliveryAttempt);
            return ConsumeResult.SUCCESS;
        }

        try {
            RiskEventConsumeLog.emit(event, clock.getAsLong() - event.occurredAt(), deliveryAttempt);
            if (event.decisionAction() == RiskAction.REVIEW) {
                // 额外一行，不是替代关系：上面那行照常写，这一行是「有人该去看一眼」的载体。
                RiskEventConsumeLog.emitReviewPending(event);
            }
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException e) {
            // 解析之后的任何异常都当作「这次处理没成功」。事件本身没坏（它已经解析出来了），
            // 坏的是一次性的东西——时钟、日志框架、将来的某个下游。这些都值得重投。
            log.error("风险事件处理失败，交给 broker 重投 eventId={} deliveryAttempt={}",
                    event.eventId(), deliveryAttempt, e);
            return ConsumeResult.FAILURE;
        }
    }

    /**
     * 把异常翻译成日志行上的 {@code reason=}。
     *
     * <p>兜底那一支不是防御性编程：D10 的判据是异常**类型**，所以任何一处没想到的
     * {@link IllegalArgumentException} 都会走到这条路径上，而它必须落在一个确定的值上，
     * 否则日志行会少一个字段——见 {@link RiskEventInvalidReason#INCONSISTENT_EVENT}。
     *
     * <p>包级可见是为了能直接测这个兜底：{@code RiskEventPayload} 今天抛出的每一个
     * {@code IAE} 都是它自己的 {@code Invalid}，所以那一支**从 {@code process} 走不到**。
     * 与其留一段没被执行过的代码，不如给它一个入口——它存在的意义正是「将来某个
     * 不是 {@code Invalid} 的 IAE 逃出来时，这一行仍然完整」。
     */
    static RiskEventInvalidReason reasonOf(IllegalArgumentException e) {
        if (e instanceof RiskEventPayload.Invalid invalid) {
            return invalid.reason();
        }
        return RiskEventInvalidReason.INCONSISTENT_EVENT;
    }
}
