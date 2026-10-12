package com.campusdash.domain.risk.model;

import java.util.List;

/**
 * 一条待投递的风险事件（P9.4 设计 §3.3）。
 *
 * <p>它是 {@link RiskDecision} 的**平行**值对象，不是它的扩展。{@code RiskDecision}
 * 是规则层的输出，它不知道 {@code eventId}、{@code operationType}、{@code occurredAt}
 * 这三样东西——那些是「**这次业务操作**」的属性，不是「**这次决策**」的属性。
 * 硬塞进去会让规则层背上它不需要的概念，P9.2 的 F-14 已经为同一类问题否掉过一次
 * 签名扩展（降级原因不能进 {@code evaluate}）。{@code RiskEvent} 是应用层把两边
 * 拼起来的产物：决策 + 操作上下文。
 *
 * <p>不做 {@code equals} 的额外约定——record 的默认等价性够用，且它不参与任何
 * 集合运算（它只被序列化一次）。
 *
 * @param eventId        本次事件的唯一标识，**在决策时分配**（D3）。它同时写进
 *                       {@code risk_decision} 行与事件载荷，是两条日志对上的唯一凭据
 * @param requestId      触发本次操作的请求 id。**来自客户端、是载荷里唯一的外部输入**，
 *                       序列化时必须转义（见 {@code RocketMqRiskEventAdapter}）
 * @param operationType  业务操作类型，本轮恒为 {@link RiskOperationType#GRAB}
 * @param errandId       任务 id
 * @param runnerId       跑腿者（用户）id。**不是原始设备号或 IP**：本模块的所有日志与
 *                       载荷都不得包含原始标识（P9.1 决策 D1）
 * @param outcome        挡没挡住，见 {@link RiskOutcome}（本轮与 {@code decisionAction} 冗余）
 * @param decisionAction 挡在哪一档，见 {@link RiskAction}
 * @param degraded       本次决策是否有证据缺失。**降级不等于放行**（蓝图 §3.8）：
 *                       它可以是 {@code true} 而 {@code decisionAction} 仍是 {@code BLOCK}
 * @param degradeReasons 缺失了哪些证据，按 {@link DegradeReason} 的声明顺序排列
 * @param occurredAt     决策发生的时刻（epoch 毫秒）。它是**事件自己的时间**，
 *                       离线脚本用它和消费时刻算出 {@code lagMs}
 */
public record RiskEvent(
        String eventId,
        String requestId,
        RiskOperationType operationType,
        long errandId,
        long runnerId,
        RiskOutcome outcome,
        RiskAction decisionAction,
        boolean degraded,
        List<DegradeReason> degradeReasons,
        long occurredAt) {

    /**
     * 载荷的三条不变式收在一处。
     *
     * <p><b>只挡一个方向</b>（{@code degraded=false} 时原因必须为空），另一个方向
     * （{@code degraded=true} 但原因为空）本轮**不挡**：这条载荷由应用层从
     * {@link RiskDecision#degraded()} 直接搬过来，那个值本身就是
     * 「原因列表非空」的派生；在这里再加一条对称约束，挡住的只会是构造错误，
     * 而不是一个真实存在的状态。若将来真的出现「有降级但没有枚举能表达的原因」，
     * 该做的是往 {@link DegradeReason} 里追加一个值，不是放宽这里。
     *
     * <p>{@code degradeReasons} 为 {@code null} 时收成空列表而不是抛异常：调用方
     * 用 {@code null} 表达「没有降级原因」是很自然的写法，而空列表才是这个记录
     * 该有的形态——一旦让它以 null 存进去，日志渲染与序列化两处都得各判一次 null。
     */
    public RiskEvent {
        requireNonBlank(eventId, "eventId");
        requireNonBlank(requestId, "requestId");
        degradeReasons = degradeReasons == null ? List.of() : List.copyOf(degradeReasons);
        if (!degraded && !degradeReasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "riskEvent 的 degraded=false，但带了 " + degradeReasons.size()
                            + " 条降级原因。两者必须一致：degraded 是"
                            + "「原因列表非空」的派生值，不一致会让同一份载荷自相矛盾，"
                            + "而离线脚本按 degraded 分组统计时会把它算错组。");
        }
    }

    /**
     * 这条事件属于哪一类聚合闸门（P9.4 设计 §2 的 D7：**动作优先于降级**）。
     *
     * <p>一次决策可以同时是「{@code LIMIT}」和「降级」（黑名单查询挂了，但用户窗口
     * 已经越线）。闸门只能用一个，取 {@code action != PASS} 的那一个：动作型事件的
     * 证据价值高于降级通告。<b>反过来是错的</b>——降级键抢到了、{@code LIMIT} 键没抢到，
     * 被抑制的恰好是攻击信号，而抑制掉的是我们最想看的东西。
     *
     * <p><b>为什么放在领域里而不是适配器里</b>：{@link RiskAction} 与
     * {@link RiskEventGateType} 都是领域枚举，两者之间这条映射是**关于事件的语义**，
     * 不是关于 RocketMQ 的。放这里还顺带消掉一个顺序问题——它是纯函数，所以「队列满丢
     * 事件」那条 {@code DROPPED} 行在**调用方线程**上就能拿到闸门类型，不必等执行器线程
     * 定完档（那两件事原本隔着一次入队）。
     *
     * <p>{@code switch} 不写 {@code default}：{@link RiskAction} 将来多一档时这里
     * **编译不过**，而不是安静地把新档位归进 {@code DEGRADED}。风控的失败模式里，
     * 「新档位没人处理」是最不该由线上流量来发现的一种（同 {@code RiskPreCheckResult#errorCode()}）。
     *
     * <p>{@code PASS} 落到 {@code DEGRADED}：走到这里的 {@code PASS} 必然带
     * {@code degraded=true}（否则 {@link RiskDecision#producesEvent()} 为假、根本不该有这条事件）。
     * 这条映射对 {@code degraded=false} 的 {@code PASS} 也会给出 {@code DEGRADED}，
     * 但那份事件不存在——适配器在定档之前就早退了。
     */
    public RiskEventGateType gateType() {
        return switch (decisionAction) {
            case PASS -> RiskEventGateType.DEGRADED;
            case LIMIT -> RiskEventGateType.LIMIT;
            case REVIEW -> RiskEventGateType.REVIEW;
            case BLOCK -> RiskEventGateType.BLOCK;
        };
    }

    /**
     * 非空白校验。用 {@code isBlank} 而不是 {@code isEmpty}：一个全是空格的
     * {@code eventId} 在日志里渲染出来就是一段空白，肉眼与按空格切分的脚本都看不见它，
     * 而它会让两条本该对上的日志对不上。
     */
    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白，当前为 " + value);
        }
    }
}
