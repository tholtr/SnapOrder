package com.campusdash.infrastructure.risk;

/**
 * 一条风险事件被判为「格式非法」的原因（P9.4 设计 §6.2 的第五条行，`reason=` 字段）。
 *
 * <p><b>它为什么存在</b>：设计只给这条行定了两个字段（{@code error} 与
 * {@code deliveryAttempt}），而 {@code error} 按同族的 {@code risk_event_publish_failed}
 * 只能是**异常类名**——于是「缺字段」「数字非法」「引号不配」「枚举名不认识」四种
 * 完全不同的坏法，在日志上会渲染成一模一样的 {@code error=IllegalArgumentException}。
 * 一条永远无法归因的 {@code risk_event_invalid}，在 P9.5 的报告里只能表现为一个计数，
 * 而排查的人需要知道的是「生产端改了格式」还是「有人往 topic 里灌了别的东西」。
 *
 * <p>实现期定档（2026-09-11 拍板）：加这个短码。
 *
 * <p><b>为什么不能改用异常消息</b>：解析器自己的消息里带着它正在解析的那段 JSON——
 * 而 {@code requestId} 就在里面。设计 §6.2 明确「不打印原始 body」，所以消息这条路
 * 是堵死的：能进日志的只有**类型**与**这个枚举值**，两者都不含载荷内容。
 *
 * <p><b>声明顺序即日志语义的优先级</b>（从「最像传输问题」到「最像代码问题」），
 * 且枚举名原样渲染进日志行——与 {@code RiskEventPublishResult} 同一个约定：
 * 它也是离线脚本要匹配的字面量，改名等于改协议。新值只能追加在末尾。
 */
public enum RiskEventInvalidReason {

    /**
     * 必填字段**不在**载荷里，或者整条消息体是 {@code null}。
     *
     * <p>「形态不对」（该是字符串的位置上是数字）不落在这里，走
     * {@link #MALFORMED_JSON} 或 {@link #BAD_NUMBER}——那两种是**值**的问题，
     * 而这一种是**字段整个不见了**。两者的排查方向不同：前者要去看生产端怎么写的这个字段，
     * 后者要去看它为什么整段都没了。
     */
    MISSING_FIELD,

    /** 数字字段（{@code errandId} / {@code runnerId} / {@code occurredAt}）解析不出一个整数。 */
    BAD_NUMBER,

    /**
     * 结构不合法：引号没配上、对象没有闭合、出现了预期外的记法。
     *
     * <p>它与 {@code BAD_NUMBER} 的边界是「坏在骨架上」还是「坏在某一个值上」——
     * 前者往往意味着生产端的拼接被改过，后者往往是单个字段的内容问题。
     */
    MALFORMED_JSON,

    /**
     * 枚举名不认识：{@code operationType} / {@code outcome} / {@code decisionAction}
     * / {@code degradeReasons} 里出现了本版本代码没有的值。
     *
     * <p>这一类的典型来源是**滚动发布**：新的 app 实例已经发出了带新枚举名的事件，
     * 而 worker 还停在旧版本。它与其他四种坏法的处理方式不同——消息本身没坏，
     * 只是这个消费者看不懂，所以它是排查时的第一优先怀疑对象。
     */
    UNKNOWN_ENUM,

    /**
     * 通过了解析，但不符合 {@code RiskEvent} 自己的不变式（空白 id、{@code degraded} 与
     * 降级原因不一致），以及**一切没能归类的** {@link IllegalArgumentException} 兜底。
     *
     * <p>兜底这一半是刻意的：处理器的判据是「{@code IllegalArgumentException} 就是格式非法」
     * （D10），所以任何一处没想到的 IAE 都必须落到一个确定的值上，而不是让那行日志少一个字段。
     */
    INCONSISTENT_EVENT;
}
