package com.campusdash.domain.risk.model;

/**
 * 一次业务操作最终挡没挡住（P9.4 设计 §3.3，蓝图 §3.9 载荷字段表里的 {@code outcome}）。
 *
 * <p><b>如实说明它本轮是冗余的</b>：{@code outcome} 可以由同一份载荷里的
 * {@code decisionAction} 推出——{@code decisionAction == PASS} 就是 {@link #PASSED}，
 * 否则是 {@link #DENIED}。两个字段装同一条信息，写错了还会互相矛盾。
 *
 * <p><b>仍然保留它的两条理由</b>：
 *
 * <ol>
 *   <li>它在蓝图 §3.9 的载荷字段表里是冻结的，删掉就是改一份已发布的契约；</li>
 *   <li>它把「挡不挡」与「挡在哪一档」分成两个轴。将来调整档位分值表时
 *       （比如 {@code LIMIT} 的分数线从 30 挪到 40），离线口径里「拦了多少次」
 *       这一列不需要跟着动——它读的是 {@code outcome}，不是具体档位。</li>
 * </ol>
 *
 * <p>本轮没有「部分通过」这种中间态，所以是两个值而不是三个。真出现时按
 * {@link RiskAction} 的先例**追加在末尾**，而不是插在中间。
 */
public enum RiskOutcome {

    /** 放行：{@code decisionAction == PASS}，且没有降级到「不可用」的地步。 */
    PASSED,

    /** 拒绝：{@code decisionAction} 是 {@code LIMIT / REVIEW / BLOCK} 之一。 */
    DENIED
}
