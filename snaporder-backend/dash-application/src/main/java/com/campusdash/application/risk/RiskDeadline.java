package com.campusdash.application.risk;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 一次预检的时间预算（P9.3 设计 §5.1）。
 *
 * <p><b>包私有，不进领域层</b>：它不是风控的领域概念——不是分数、不是动作、也不是证据，
 * 只是编排内部的一个执行预算。它与 {@code LogField} 是**相反的两类东西**，别照着
 * 后者搬家：{@code LogField} 被三个模块的日志行共用，所以 P9.4 把它下沉到了
 * {@code dash-shared}；这个类只有本包这一条链路用，包私有就是它该有的可见性。
 *
 * <p><b>预算是协作式的，不改端口签名</b>（P9.3 决策 D5，覆盖 P9.2 的 D11）：
 * 用例在每一步之前问一次 {@link #expired()}，没有余额就跳过该步。适配器即便拿到了
 * 这个值，也只能在某条命令**发出之前**判一次余量——那与用例在两步之间判完全等价，
 * 却要让三个端口、三个适配器和一批测试 fake 全部多背一个参数。
 *
 * <p><b>只暴露 {@link #expired()}，不暴露 {@code remainingMs()}</b>：余量的消费者
 * 只有这一个判断。暴露成数字只会招来「顺手记一下还剩多少」这类没有消费者的用法
 * （P9.1 已经否过一次同型的设计）。
 *
 * <p>判断用纳秒差值的**符号**（{@code 已用 >= 预算}），不做毫秒换算：预算是 10ms
 * 量级，纳秒到毫秒的整除会把「还剩 0.9ms」和「已经超了 0.1ms」压成同一个 0。
 * 边界取 {@code >=} 而不是 {@code >}，是为了让 {@code after(0, …)} **生来就过期**——
 * 这正是 {@code deadline-ms=0} 的语义「一步都不许跑」（P9.3 决策 D11）；
 * 换成 {@code >} 会让「0 预算」退化成「第一步照跑」。
 */
final class RiskDeadline {

    private final long budgetNanos;
    private final LongSupplier clock;
    private final long startNanos;

    /**
     * @param budgetMs 预算毫秒数。**负数不在这里校验**：生产上唯一的来源是
     *                 {@code RiskProperties.deadlineMs()}，它已在绑定期拒掉负数
     *                 （{@code requireNonNegativeDeadline}），这里再判一次只是重复。
     * @param clock    时钟。做成参数而不是内部直接取 {@code System::nanoTime}：
     *                 用例的构造函数已经把它收成一个字段（生产传真时钟、单测传假时钟），
     *                 这里再默认一次就等于「这次预检用的是哪个时钟」有两个决定点。
     */
    static RiskDeadline after(long budgetMs, LongSupplier clock) {
        return new RiskDeadline(budgetMs, clock);
    }

    private RiskDeadline(long budgetMs, LongSupplier clock) {
        this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMs);
        this.clock = clock;
        // 起点在**构造的这一刻**取，而不是在第一次 expired() 时取：后者会让
        // 「构造」这个动作本身不花预算，等于把预算悄悄放宽一个步骤。
        this.startNanos = clock.getAsLong();
    }

    /** 预算是否已经用完。只增不减——一旦为真，后面的检查点不会再让它变回假。 */
    boolean expired() {
        return clock.getAsLong() - startNanos >= budgetNanos;
    }
}
