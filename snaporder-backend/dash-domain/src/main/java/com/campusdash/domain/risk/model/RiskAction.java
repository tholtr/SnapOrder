package com.campusdash.domain.risk.model;

/**
 * 风控四级动作（蓝图 §3.6）。
 *
 * <p><b>声明顺序即严重度</b>：{@code PASS < LIMIT < REVIEW < BLOCK}。比较严重度用
 * {@link Enum#ordinal()} 或直接比大小（枚举天然实现 {@code Comparable}），不再维护一个
 * 平行的 {@code level} 字段——那会多出一条「level 写反了但没人发现」的失败路径。
 * 调整严重度就是调整这里的顺序，编译器不会拦，但 {@code RiskRulesTest} 里有一条
 * 断言档位与顺序一致的用例。
 *
 * <p>动作是**总分的纯函数**（P9.2 决策 D1）：{@link #ofScore(int)} 是唯一的定级入口，
 * 没有任何规则能越过分值直接指定动作。黑名单之所以恒为 {@code BLOCK}，是因为它的分值
 * 取 {@link #BLOCK_MIN}，而不是因为它走了一条旁路分支（D3）——这样
 * 「黑名单命中但 {@code riskScore=0}」在结构上就不可能出现。
 */
public enum RiskAction {

    /** 继续原有流程。 */
    PASS,

    /** 当前请求拒绝，并写入用户级限频键。 */
    LIMIT,

    /** 当前请求拒绝，并记录审核事件。 */
    REVIEW,

    /** 当前请求直接拒绝，并记录强拦截原因。 */
    BLOCK;

    /**
     * 档位下界。**分值就是这三个常量本身**，而不是照抄一份字面量：
     * 调用方写 {@code score = BLOCK_MIN} 时，档位边界一改它就跟着改，
     * 不会出现「{@code BLOCK} 下界调到 120 之后黑名单静默不再拦截」。
     * 唯一的例外是 {@code RiskRuleId.IP_ACCOUNTS} 的 20 分——它故意不绑定任何档位。
     */
    public static final int LIMIT_MIN = 30;
    public static final int REVIEW_MIN = 60;
    public static final int BLOCK_MIN = 80;

    /**
     * 总分 → 动作。取**最高的**满足档位，边界值属于该档（{@code score == LIMIT_MIN}
     * 就是 {@code LIMIT}，不是 {@code PASS}）。
     *
     * <p>未命中任何档位返回 {@link #PASS}，这也是「所有证据都拿不到、分数为 0」时的
     * 自然结果——降级不是一条写着「失败就放行」的分支，而是「少一个证据」。
     * 黑名单查询失败但设备维度已经越线时，决策仍然是 {@code BLOCK}。
     */
    public static RiskAction ofScore(int score) {
        if (score >= BLOCK_MIN) {
            return BLOCK;
        }
        if (score >= REVIEW_MIN) {
            return REVIEW;
        }
        if (score >= LIMIT_MIN) {
            return LIMIT;
        }
        return PASS;
    }
}
