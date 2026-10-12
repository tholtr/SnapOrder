package com.campusdash.domain.risk.model;

/**
 * 风险事件的聚合闸门类型（P9.4 决策 D4、D5、D7）。
 *
 * <p>括号里的窗口秒数是蓝图 §3.9 那张表：同一用户 10 秒内最多 1 条 {@code LIMIT}、
 * 60 秒 1 条 {@code REVIEW}、60 秒 1 条 {@code BLOCK}；降级事件按**实例**聚合，
 * 10 秒 1 条。窗口值是**领域常量、不做配置**（D5）——它是「离线指标还能不能看」
 * 的口径，不是一个可以随环境调优的运行参数。
 *
 * <p><b>为什么做成枚举而不是四个常量</b>：键名段与窗口必须**同源**。键名里的
 * {@code limit/review/block/degraded} 由枚举名小写得到，TTL 由 {@link #windowSeconds()}
 * 给出，两者不可能漂移。将来加第五类闸门时，{@code RiskKeys} 里那个 {@code switch}
 * 会因为不穷尽而**编译失败**——这正是我们要的：加闸门的人一定会被编译器按着头来看
 * 键名与 TTL 两处。
 *
 * <p><b>不提供把枚举映射成 {@link DegradeReason} 的方法</b>：{@code DEGRADED} 是一个
 * **闸门**，不是一个**降级原因**。本轮的降级原因可以是 {@link DegradeReason} 六个值中的
 * 任何一个，两者是正交的两个轴。把它们合成一个轴，正是 P9.2 已经否决过一次的那个错误
 * （见 {@code RiskDecision.withDegradeReasons} 的注释：降级原因是「哪一步抛了异常」，
 * 只有捕获异常的那一层知道）。
 *
 * <p><b>声明顺序即日志顺序</b>（同 {@link DegradeReason}），且 {@link #ordinal()} 不参与
 * 任何严重度比较——严重度那个轴属于 {@link RiskAction}，这里四个值是四种**闸门**，
 * 彼此独立、互不覆盖（一个用户同一秒内可以四类都被放行，各发一条）。
 */
public enum RiskEventGateType {

    /** {@code LIMIT} 动作的闸门：同一用户 10 秒一条。 */
    LIMIT(10),

    /** {@code REVIEW} 动作的闸门：同一用户 60 秒一条。 */
    REVIEW(60),

    /** {@code BLOCK} 动作的闸门：同一用户 60 秒一条。 */
    BLOCK(60),

    /**
     * 降级事件的闸门：**按实例**聚合，10 秒一条。
     *
     * <p>它的主体是实例标识而不是用户 id（见 {@code RiskKeys.eventGate} 的两个重载），
     * 因为降级是「这个实例的某个依赖挂了」，不是「这个用户有问题」——按用户聚合会让
     * 一次 Redis 抖动在每个用户身上各发一条，把降级事件变成最吵的那一类。
     *
     * <p>代价必须写出来：N 个实例下，同一个降级原因每 10 秒最多产生 **N** 条事件，
     * 不是 1 条。P9.5 读抑制率时分母要按实例数放大。
     */
    DEGRADED(10);

    private final int windowSeconds;

    RiskEventGateType(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    /**
     * 聚合窗口长度，同时是闸门键的 TTL。
     *
     * <p>只有一个访问器是刻意的：多一个访问器就多一种「键名认这个、TTL 认那个」的
     * 可能。键名段请用 {@code name().toLowerCase()} 在 {@code RiskKeys} 里现算。
     */
    public int windowSeconds() {
        return windowSeconds;
    }
}
