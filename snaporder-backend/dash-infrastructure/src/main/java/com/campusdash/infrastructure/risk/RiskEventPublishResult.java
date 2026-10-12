package com.campusdash.infrastructure.risk;

/**
 * {@code risk_event_publish} 行上 {@code result=} 的四个取值（P9.4 设计 §4.3）。
 *
 * <p><b>这四个值是离线脚本的分组口径，改一个字符等于改协议</b>：§8.3 的
 * {@code analyze_risk_events.py} 按 {@code result} 分组算出
 * {@code risk_event_publish_total} / {@code risk_event_suppressed_total} /
 * {@code risk_event_dropped_total}。枚举名原样渲染进日志行（{@code name()}），
 * 所以这里的拼写就是脚本要匹配的字符串。
 *
 * <p><b>声明顺序即日志顺序</b>，与 {@code DegradeReason} / {@code RiskEventGateType}
 * 同型：新取值只能追加到末尾。这里顺序还有一个额外用途——它是「一次投递尝试的
 * 生命周期顺序」的读法：先决定发不发（{@code SENT} / {@code SUPPRESSED} /
 * {@code SENT_GATE_FAILED}），发送的失败**不在这里**（见下）。
 *
 * <p><b>为什么不设 {@code SENT_UNGATED}（聚合开关关闭）</b>：实施计划 §9.4 定的是
 * 「同一攻击流量、同一配置时长下跑两次」的对照实验，两次运行各自有一整份日志，
 * 开关状态由运行本身确定。为它多造一个状态值，只会让「这个值算不算发送成功」
 * 多出一个需要判断的分支——而这个问题在两次运行里答案相同。
 *
 * <p><b>发送失败为什么不在这里</b>：它在 {@code CompletableFuture} 的回调里才知道，
 * 与 {@code risk_event_publish} 行的产生时刻隔着一次网络往返。要为它加第五个取值，
 * 只有两种做法——把前一行也推迟到回调里写（那样 {@code SUPPRESSED} 与
 * {@code DROPPED} 这两类**根本不会回调**的事件就没法记了），或者让它承载一个
 * 「还不知道」的值。两种都比多一行差，所以失败是独立的一行
 * {@code risk_event_publish_failed}。
 */
public enum RiskEventPublishResult {

    /**
     * 闸门放行（或聚合关闭时直接跳过闸门），已交给 {@code sendAsync}。
     *
     * <p><b>它的含义到此为止——不是「已投递」。</b>它到没到 broker 由
     * {@code risk_event_publish_failed} 的**缺席**间接说明，而缺席不是证明。
     * 本模块的验收与归档里不得把这条写成「投递成功」；要证明投递成功，
     * 只有 {@code RiskEventIT} 那条真的收到消息的用例。
     */
    SENT,

    /** 被时间窗聚合抑制：闸门键已存在，这条事件不发。 */
    SUPPRESSED,

    /**
     * 聚合判断本身失败了（Redis 抛），**仍然发送**（D8）。
     *
     * <p>单独一个取值而不是记成 {@code SENT}：聚合是保护 MQ 的手段，
     * 不是「要不要记录」的判断——Redis 出问题往往伴随流量异常，正是最需要证据的时刻。
     * 但「闸门坏了所以每条都发」与「闸门正常放行」在离线看抑制率时是完全不同的两回事，
     * 混在一起会让抑制率凭空变好看。
     */
    SENT_GATE_FAILED,

    /**
     * 执行器队列满，**没有入队**（D9）。
     *
     * <p>丢弃本身可以接受（蓝图 §3.9 定的是 best-effort），**静默丢弃不可以**——
     * 否则 P9.5 报告里「事件数比预期少」会变成一个无法归因的现象。
     */
    DROPPED
}
