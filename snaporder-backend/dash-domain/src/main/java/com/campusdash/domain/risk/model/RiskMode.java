package com.campusdash.domain.risk.model;

/**
 * 风控的运行模式（蓝图 §3.10）。
 *
 * <p><b>{@link #SHADOW} 不等于「风控关闭」。</b>它照常采集、照常查黑名单、照常算分，
 * 只是不拦截、也不读写限频键。关闭是 {@code dash.risk.enabled=false}，那是另一回事——
 * 那个开关连 I/O 都不发生（见 P9.2 设计 §5.1 的三态对照表）。
 *
 * <p>「不读写限频键」是 {@link #SHADOW} 定义的一部分，不是实现优化：限频键一旦在
 * {@code SHADOW} 下被写入，切回 {@code ENFORCE} 时就会凭空出现一批「之前被限过频」
 * 的用户，而这些人从没被拦过。所以限频键必须整体归属 {@code ENFORCE}。
 *
 * <p>用枚举而不是 {@code boolean shadow}：调用点写 {@code if (shadow)} 时，
 * {@code true} 到底代表「影子」还是「生效」全靠读代码的人去记——本模块最防这类
 * 反向语义。枚举名自带含义，且可以在全仓 grep。
 */
public enum RiskMode {

    /** 只计算并记录风险结果，不拦截。（本轮默认值，用于先观测真实流量的误伤率。） */
    SHADOW,

    /** 完整生效：读限频键、按决策拦截、动作恰为 {@code LIMIT} 时写限频键。 */
    ENFORCE
}
