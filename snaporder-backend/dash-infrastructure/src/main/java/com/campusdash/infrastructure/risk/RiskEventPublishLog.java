package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskEventGateType;
import com.campusdash.shared.LogField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 风险事件**生产侧**的两行（P9.4 设计 §4.3）。字段 camelCase；离线分析脚本负责映射到
 * snake_case 指标，所以<b>字段名与顺序是跨系统的契约，改动等于改协议</b>。
 *
 * <pre>
 * risk_event_publish eventId=... requestId=... operationType=GRAB errandId=... runnerId=...
 *                    decisionAction=LIMIT degraded=false gateType=LIMIT result=SENT
 *
 * risk_event_publish_failed eventId=... topic=errand-risk-event error=<异常类名>
 * </pre>
 *
 * <p><b>两行合成一个类</b>：它们是同一次投递尝试的头尾，读的人总是一起读，
 * 且第二行的 {@code eventId} 靠第一行的 {@code eventId} 才认得出是哪一条。
 * 这与 {@code RiskDecisionLog} / {@code RiskFeatureLog} 的「一类一线族」不矛盾——
 * 那两个类各自锁的是一条独立的行；这里是**一对**。
 *
 * <p><b>为什么入参是整条 {@link RiskEvent} 而不是拆开的七个值</b>：同
 * {@code RiskDecisionLog} 的理由——{@code eventId} / {@code requestId} /
 * {@code operationType} / {@code errandId} / {@code runnerId} 之间有
 * {@link RiskEvent} 自己的不变式（比如 id 不能为空白），拆成平铺参数等于在日志这一侧
 * 把它们重新放开，而一条 {@code eventId=-} 的行是没法与 {@code risk_decision} 行对上的。
 *
 * <p><b>哪些字段过 {@link LogField#safe}</b>：只有 {@code requestId}。
 * 它是本行里唯一**来自客户端**的字段（{@code X-Request-Id}），含空格的值能凭空
 * 造出一个字段——离线脚本按空格切分取 {@code result} 时读到的会是客户端写进去的值。
 * 其余字段要么是数字，要么是枚举名，要么是应用自己生成的 id（{@code eventId}）：
 * 枚举名不含空白与控制字符，而 id 的字符集由分配它的代码决定，不经过客户端。
 * 这个区分与 {@code LogField} 的类注释是一回事，不是「顺手少扫一遍字符串」。
 *
 * <p><b>安全约束</b>（P9.1 的 D1）：本行以及所有风控日志不得包含原始
 * {@code deviceId} / {@code clientIp}。{@link RiskEvent} 里本来就没有这两个值，
 * 所以这条在类型层面即成立，而不是靠每次改代码时记得住。
 */
public final class RiskEventPublishLog {

    private static final Logger log = LoggerFactory.getLogger(RiskEventPublishLog.class);

    private RiskEventPublishLog() {
    }

    /**
     * 拼出投递行。单独拆出来是为了能在单测里**直接**断言整行文本，
     * 不必挂 appender、不必绕日志框架。
     *
     * <p>注意：这**不是**因为「本仓库没法捕获日志」——那句注释是错的，已由
     * {@code 实现期发现.md} 的 F-24 更正。logback（含
     * {@code ch.qos.logback.core.read.ListAppender}）本来就在本模块的 compile
     * classpath 上，{@code RocketMqRiskEventAdapterTest} 正是靠它覆盖 D8 的。
     * 纯函数仍然值得留：它让「格式」与「何时打这一行」两件事可以分别断言。
     *
     * <p>{@code gateType} 即使聚合开关关掉也照样有值：D7 的闸门**定档**发生在
     * 「跳过闸门」那个判断之前，所以这一行永远说得清「这条事件属于哪一类闸门」。
     * 这正是 {@code SENT_UNGATED} 不需要存在的原因之一。
     *
     * @param result 见 {@link RiskEventPublishResult}。发送**失败**不在其中，走
     *               {@link #formatFailed(String, String, Throwable)} 那一行
     */
    public static String format(RiskEvent event, RiskEventGateType gateType,
                                RiskEventPublishResult result) {
        return "risk_event_publish eventId=" + event.eventId()
                + " requestId=" + LogField.safe(event.requestId())
                + " operationType=" + event.operationType()
                + " errandId=" + event.errandId()
                + " runnerId=" + event.runnerId()
                + " decisionAction=" + event.decisionAction()
                + " degraded=" + event.degraded()
                + " gateType=" + gateType
                + " result=" + result;
    }

    /**
     * 投递尝试结束时调用。
     *
     * <p>级别是 {@code info} 而不是 {@code warn}：这一行不是「出事了」，它是一次
     * 投递尝试的**正常出口**（{@code SUPPRESSED} 与 {@code DROPPED} 都是设计内的结果）。
     * 把它记成 warn 会让「按级别告警」的人对聚合本身报错。
     */
    public static void emit(RiskEvent event, RiskEventGateType gateType,
                            RiskEventPublishResult result) {
        log.info(format(event, gateType, result));
    }

    /**
     * 拼出发送失败行（{@code sendAsync} 的回调里才知道，所以是独立的一行）。
     *
     * <p><b>入参是 {@link Throwable} 而不是异常类名字符串</b>：这条行只该出现**类型**，
     * 而拿了字符串的调用点迟早会有人写成 {@code ex.getMessage()}——异常消息里可能嵌着
     * 服务端返回的原文，那是外部输入，正是 {@code LogField} 在堵的东西。
     * 收窄成 {@code Throwable} 之后，「把消息写进日志」不再是一种可能的写法。
     *
     * <p>{@code error} 先取 {@link Class#getSimpleName()} 再过 {@link LogField#safe}：
     * 匿名类的简单名是空串，渲染出来会是一个**看起来像字段缺失**的 {@code error=}，
     * 而 {@code -} 能让「这次失败没记下类型」一眼可见。
     *
     * <p>{@code topic} 是参数而不是在这里取 {@code RiskEventPort.TOPIC_RISK_EVENT}：
     * 这行要记的是**实际用的那个 topic**。从常量反推会把「适配器发去了别处」这件事实
     * 掩盖成一条打印得很整齐的日志——而那正是排障时需要看见的东西。
     */
    public static String formatFailed(String eventId, String topic, Throwable error) {
        return "risk_event_publish_failed eventId=" + eventId
                + " topic=" + LogField.safe(topic)
                + " error=" + LogField.safe(error == null ? null : error.getClass().getSimpleName());
    }

    /**
     * 发送失败时调用（{@code CompletableFuture} 的完成回调里）。
     *
     * <p>级别同样是 {@code info}，与 {@link #emit} 一致：这两行是同一族结构化日志，
     * 消费方是离线脚本而不是人眼告警。改级别会让它们在按级别采集的管道里落到不同的
     * 去处，而脚本只认行首标记。
     */
    public static void emitFailed(String eventId, String topic, Throwable error) {
        log.info(formatFailed(eventId, topic, error));
    }
}
