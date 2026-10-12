package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.shared.LogField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 风险事件**消费侧**的三行（P9.4 设计 §4.3、§6.2）。字段 camelCase；离线分析脚本负责
 * 映射到 snake_case 指标，所以<b>字段名与顺序是跨系统的契约，改动等于改协议</b>。
 *
 * <pre>
 * risk_event_processed eventId=... requestId=... operationType=GRAB errandId=... runnerId=...
 *                      outcome=DENIED decisionAction=LIMIT degraded=false degradeReasons=-
 *                      occurredAt=1757600000000 lagMs=42 deliveryAttempt=1
 *
 * risk_review_pending eventId=... requestId=... operationType=GRAB errandId=... runnerId=...
 *                     occurredAt=1757600000000
 *
 * risk_event_invalid error=IllegalArgumentException reason=MALFORMED_JSON deliveryAttempt=1
 * </pre>
 *
 * <p><b>三行合成一个类</b>，理由与生产侧那个类相同：它们是同一次消费尝试的三个出口，
 * 读的人总是一起读，且另外两行的 {@code eventId} 靠第一行的 {@code eventId} 才认得出
 * 是哪一条。第三行是个例外——格式非法的消息**解析不出 {@code eventId}**，
 * 所以它是三行里唯一没有 id 的一行，这不是遗漏。
 *
 * <p><b>为什么入参是整条 {@link RiskEvent} 而不是拆开的十个值</b>：同
 * {@code RiskEventPublishLog} 的理由——那些字段之间有 {@link RiskEvent} 自己的不变式，
 * 拆成平铺参数等于在日志这一侧把它们重新放开。消费侧的解析器按设计 §6.1 直接产出
 * {@code RiskEvent}，于是「解析成功」这件事与「能写这一行」是同一件事，不需要在这之间
 * 再插一个只在内部流转的中间类型。
 *
 * <p><b>哪些字段过 {@link LogField#safe}</b>：只有 {@code requestId}。它是三行里唯一
 * **来自客户端**的字段（{@code X-Request-Id}），含空格的值能凭空造出一个字段——
 * §8.3 的脚本按空格切分取 {@code outcome} 时读到的会是客户端写进去的值。
 * 其余字段要么是数字，要么是枚举名，要么是应用自己生成的 id（{@code eventId}），
 * 理由与 {@code RiskEventPublishLog} 逐条相同。
 *
 * <p><b>安全约束</b>（P9.1 的 D1）：本行以及所有风控日志不得包含原始
 * {@code deviceId} / {@code clientIp}。{@link RiskEvent} 里本来就没有这两个值，
 * 所以这条在类型层面即成立。第三行另有一条同族的约束：**不得包含原始 body**
 * （那里面有 {@code requestId}），所以它只收 {@link Throwable} 与原因枚举，见
 * {@link #formatInvalid(Throwable, RiskEventInvalidReason, int)}。
 */
public final class RiskEventConsumeLog {

    private static final Logger log = LoggerFactory.getLogger(RiskEventConsumeLog.class);

    private RiskEventConsumeLog() {
    }

    /**
     * 拼出消费行。单独拆出来是为了能在单测里**直接**断言整行文本，
     * 不必挂 appender、不必绕日志框架（理由与 {@code RiskEventPublishLog} 相同）。
     *
     * <p><b>{@code lagMs} 为负数时按 {@code 0} 记</b>（设计 §6.2 第 2 步）：它等于
     * 「消费时刻 − 事件时刻」，两个时钟不在同一台机器上，负值是时钟漂移的**正常**表现，
     * 不是一个该拒绝这条日志的理由。夹在这里而不是调用点：那是个「怎么呈现」的决定，
     * 而调用点只该回答「现在几点」；夹在这一处，格式锁测试也就顺带把它锁住了。
     *
     * <p>被夹掉的原值**不另开字段**：{@code occurredAt} 照原样打印，漂移了多少从
     * 「事件时刻 vs 行自身的日志时间戳」就能看出来，再加一个字段只会多一个要维护的口径。
     *
     * <p><b>{@code degradeReasons} 空集渲染成 {@code -}</b>，非空按声明序以 {@code |} 连接。
     * 这与载荷里的编码（空串）不同：{@code -} 是本模块日志行的占位约定，
     * 而载荷里必须区分「没有降级原因」与「有一个叫 {@code -} 的原因」。
     */
    public static String format(RiskEvent event, long lagMs, int deliveryAttempt) {
        return "risk_event_processed eventId=" + event.eventId()
                + " requestId=" + LogField.safe(event.requestId())
                + " operationType=" + event.operationType()
                + " errandId=" + event.errandId()
                + " runnerId=" + event.runnerId()
                + " outcome=" + event.outcome()
                + " decisionAction=" + event.decisionAction()
                + " degraded=" + event.degraded()
                + " degradeReasons=" + reasonField(event.degradeReasons())
                + " occurredAt=" + event.occurredAt()
                + " lagMs=" + Math.max(0, lagMs)
                + " deliveryAttempt=" + deliveryAttempt;
    }

    /**
     * 一条事件处理完成时调用。
     *
     * <p>级别是 {@code info}：这一行是**正常出口**，不是告警。一条被重复投递的消息
     * 会产生第二行同样的日志（蓝图 §5.3 的「重投递只产生重复日志」），所以「同一个
     * {@code eventId} 出现多次」是设计内的情况，不是异常——按级别告警会让重投递变成
     * 一个假故障。真正的重复统计由 §8.3 的脚本按 {@code requestId + operationType} 做。
     */
    public static void emit(RiskEvent event, long lagMs, int deliveryAttempt) {
        log.info(format(event, lagMs, deliveryAttempt));
    }

    /**
     * 拼出待审行（{@code decisionAction == REVIEW} 时**额外**写的一行）。
     *
     * <p><b>不重复 {@code decisionAction}</b>（设计 §4.3）：这条行的存在本身就意味着
     * {@code REVIEW}，写上就是一个恒为常量的字段，而手工审核入口要的是
     * {@code eventId} 与 {@code errandId}，其余从上面的 {@code risk_event_processed}
     * 行按 {@code eventId} 取。
     *
     * <p>本轮**不建审核表**（设计 §8.4）：这条行是「有人该去看一眼」的全部载体。
     */
    public static String formatReviewPending(RiskEvent event) {
        return "risk_review_pending eventId=" + event.eventId()
                + " requestId=" + LogField.safe(event.requestId())
                + " operationType=" + event.operationType()
                + " errandId=" + event.errandId()
                + " runnerId=" + event.runnerId()
                + " occurredAt=" + event.occurredAt();
    }

    /** 见 {@link #formatReviewPending(RiskEvent)}。级别同为 {@code info}，理由同上。 */
    public static void emitReviewPending(RiskEvent event) {
        log.info(formatReviewPending(event));
    }

    /**
     * 拼出格式非法行（第五条行，设计 §6.2）。
     *
     * <p><b>入参是 {@link Throwable} 而不是异常类名字符串</b>：与
     * {@code RiskEventPublishLog#formatFailed} 同一条理由——拿了字符串的调用点迟早
     * 会有人写成 {@code ex.getMessage()}，而解析器的消息里正带着它解析失败的那段原始
     * JSON（{@code requestId} 就在里面）。收窄成 {@code Throwable} 之后，
     * 「把 body 写进日志」不再是一种可能的写法。
     *
     * <p><b>为什么还有一个 {@code reason}</b>：{@code error} 只能是异常类名，
     * 而四种不同的坏法都会是 {@code IllegalArgumentException}。原因枚举不含载荷内容，
     * 所以加它不破上面那条约束——详见 {@link RiskEventInvalidReason}。
     *
     * <p>{@code error} 先取 {@link Class#getSimpleName()} 再过 {@link LogField#safe}：
     * 匿名类的简单名是空串，渲染出来会是一个看起来像字段缺失的 {@code error=}。
     */
    public static String formatInvalid(Throwable error, RiskEventInvalidReason reason,
                                       int deliveryAttempt) {
        return "risk_event_invalid error=" + LogField.safe(error == null ? null
                : error.getClass().getSimpleName())
                + " reason=" + reason
                + " deliveryAttempt=" + deliveryAttempt;
    }

    /**
     * 格式非法时调用。**调用方随后必须返回 {@code SUCCESS}**（D10）：一条字段缺失的
     * 消息重投三次之后仍然是同一条消息，走完重试链只会把重试队列堵住。
     *
     * <p>级别是 {@code warn} 而不是 {@code info}：与另外两行不同，这一行**不是**设计内的
     * 正常出口——正常的消费路径不会产生它。它也不该是 {@code error}：一条格式非法的消息
     * 不需要人半夜起来处理，它需要一个计数器（{@code risk_event_invalid_total}）
     * 和一个能归因的 {@code reason}。
     */
    public static void emitInvalid(Throwable error, RiskEventInvalidReason reason,
                                   int deliveryAttempt) {
        log.warn(formatInvalid(error, reason, deliveryAttempt));
    }

    /**
     * 按**给定顺序**以 {@code |} 连接；空列表得到占位符 {@code -}。
     *
     * <p>这里不排序、也不去重——声明序那条约定属于 {@link DegradeReason} 与产出这个
     * 列表的地方（消费侧是载荷里的编码顺序，也就是生产端写下的顺序），不在这里实现第二遍。
     */
    private static String reasonField(List<DegradeReason> reasons) {
        if (reasons.isEmpty()) {
            return LogField.EMPTY;
        }
        StringBuilder joined = new StringBuilder();
        for (var reason : reasons) {
            if (joined.length() > 0) {
                joined.append('|');
            }
            joined.append(reason.name());
        }
        return joined.toString();
    }
}
