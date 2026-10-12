package com.campusdash.domain.risk.model;

/**
 * 风控规则标识，同时也是**规则分值表**（P9.2 设计 §3.2）。
 *
 * <p><b>声明顺序就是日志里 {@code matchedRules} 的顺序。</b>这不是审美问题：
 * {@code matchedRules} 是决策日志行的一部分，顺序不稳定会让同一次决策在不同环境下
 * 产出不同文本，离线脚本去重时会把它当成两条不同的记录。枚举天然给出稳定序，
 * 所以 {@code RiskRules.evaluate} 直接按这个顺序追加，日志层不再排序。
 * **调整这里的顺序等于改日志协议。**
 *
 * <p><b>为什么分值挂在这里而不是写在 {@code evaluate} 里</b>：这张表的每一行都是
 * 「规则 → 分值」的固定映射，写在枚举里，设计文档的表格和代码就是同一张表，
 * 改一处不会漏另一处。{@code evaluate} 因此只回答「哪些规则命中了」，
 * 不再关心「命中值多少分」。
 *
 * <p><b>分值为什么等于 {@link RiskAction} 的档位下界而不是各写一个整数</b>（决策 D6）：
 * 这样「档位边界」与「规则分值」的绑定在编译期就成立。若各写整数，把
 * {@code BLOCK_MIN} 从 80 调到 120 之后，黑名单的 80 分就不再够 {@code BLOCK}——
 * 规则表里写着「黑名单命中 → BLOCK」，实际却给出 {@code REVIEW}，且没有任何报错。
 */
public enum RiskRuleId {

    /** 10 秒窗口请求数超过短阈值（配置 {@code user-window.short-threshold}）。 */
    USER_WINDOW_SHORT(RiskAction.LIMIT_MIN),

    /**
     * 60 秒窗口请求数超过长阈值（配置 {@code user-window.long-threshold}）。
     *
     * <p>它与 {@link #USER_WINDOW_SHORT} 是**两个不同的计数**，所以同时命中要**累加**：
     * 10 秒内到 6 次、60 秒内到 31 次的用户得 90 分 → {@code BLOCK}。
     * 这正是最像脚本的画像，也正是取最高（而不是累加）会漏掉的画像。
     * 与之相对，设备的两档阈值是同一个计数，那边取最高——见决策 D2。
     */
    USER_WINDOW_LONG(RiskAction.REVIEW_MIN),

    /**
     * 设备关联账号数超过低阈值（配置 {@code device-window.low-threshold}）。
     *
     * <p>与 {@link #DEVICE_ACCOUNTS_HIGH} 判定的是**同一个计数**，实现为 {@code else if} 链，
     * 高档命中时这一档必然也命中，所以高档命中时**不重复计分**（决策 D2）。
     */
    DEVICE_ACCOUNTS_LOW(RiskAction.REVIEW_MIN),

    /** 设备关联账号数超过高阈值（配置 {@code device-window.threshold}）。 */
    DEVICE_ACCOUNTS_HIGH(RiskAction.BLOCK_MIN),

    /**
     * IP 关联账号数超过阈值。**唯一一个分值刻意不绑定档位的规则**（决策 D4）。
     *
     * <p>20 分低于 {@link RiskAction#LIMIT_MIN}（30），所以它单独命中时动作是
     * {@code PASS}——「仅加分，不单独定性」。原因见蓝图 §3.4：校园网出口 IP 高度共享，
     * 「同一 IP 出现 10 个账号」在日常就是常态，单独定性会大面积误伤。
     * 它只在与其他规则叠加时才把分数推过档位。
     *
     * <p>写 20 而不是引用某个档位常量，是因为它**就该**和档位解耦；
     * 反过来说，改这个数之前先想清楚它是否还小于 {@code LIMIT_MIN}——
     * 一旦越过，这条规则就变成了「单独定性」，语义与蓝图矛盾。
     * {@code RiskRulesTest} 有一条断言守着这个关系。
     */
    IP_ACCOUNTS(20),

    /** 60 秒内同一 IP 的请求数超过阈值（配置 {@code ip-rate-window.threshold}）。 */
    IP_RATE(RiskAction.LIMIT_MIN),

    /** 用户黑名单命中。 */
    BLACKLIST_USER(RiskAction.BLOCK_MIN),

    /** 设备黑名单命中。 */
    BLACKLIST_DEVICE(RiskAction.BLOCK_MIN),

    /** IP 黑名单命中。 */
    BLACKLIST_IP(RiskAction.BLOCK_MIN),

    /**
     * 限频键存在——该用户在冷却期内再次请求。
     *
     * <p>这条规则**不由 {@code RiskRules.evaluate} 判定**，而是由用例在预检第一步
     * 读键得出（决策 D10 的短路顺序）。它出现在这张表里，是为了让「冷却命中」走
     * 与其它规则完全相同的计分路径（决策 D5），而不是另开一条返回 {@code LIMIT} 的
     * 分支——另开分支的话，日志里会出现 {@code action=LIMIT riskScore=0} 这种
     * 无法解释的行。
     */
    LIMIT_COOLDOWN(RiskAction.LIMIT_MIN);

    private final int score;

    RiskRuleId(int score) {
        this.score = score;
    }

    /** 本规则命中时的分值。 */
    public int score() {
        return score;
    }
}
