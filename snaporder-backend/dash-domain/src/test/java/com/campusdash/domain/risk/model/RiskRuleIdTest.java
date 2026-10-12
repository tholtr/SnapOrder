package com.campusdash.domain.risk.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则分值表的不变式（决策 D6）。
 *
 * <p>这些关系是**代码常量之间的关系**，不在 {@code RiskProperties} 里做运行时校验：
 * 它们不随配置变化，跑一遍单测就能锁住，没必要让应用启动多做一次检查。
 * 但它们必须被某处守住——调错一个数字不会报任何错，只会让规则表里写的档位
 * 与实际的档位悄悄不一致。
 */
@DisplayName("风控规则分值表")
class RiskRuleIdTest {

    @Test
    @DisplayName("IP 账号聚集的分值必须低于 LIMIT 下界——它只能加分，不能单独定性")
    void ipAccountsStaysBelowLimitBand() {
        // 这是「仅加分」这条语义的**唯一守卫**（决策 D4）。校园网出口 IP 高度共享，
        // 「同一 IP 出现 10 个账号」在平时就是常态。这个分数一旦越过 LIMIT_MIN，
        // 规则就从「叠加时才起作用」变成「单独就限频」，大面积误伤学校宿舍与机房。
        assertTrue(RiskRuleId.IP_ACCOUNTS.score() < RiskAction.LIMIT_MIN,
                "IP_ACCOUNTS 分值 " + RiskRuleId.IP_ACCOUNTS.score()
                        + " 已不低于 LIMIT 下界 " + RiskAction.LIMIT_MIN
                        + "，它就不再是「仅加分」了");
    }

    @Test
    @DisplayName("三类黑名单的分值都不低于 BLOCK 下界——黑名单恒为 BLOCK")
    void blacklistRulesReachBlockBand() {
        // 黑名单的实现形态是「以最高分值参与累加」而不是「覆盖分数」（决策 D3）。
        // 分值一旦低于 BLOCK_MIN，黑名单命中就会降级成 REVIEW，
        // 而规则表里写着 BLOCK——没有任何报错。这条断言把它锁在编译后的运行期。
        for (RiskRuleId rule : new RiskRuleId[]{
                RiskRuleId.BLACKLIST_USER, RiskRuleId.BLACKLIST_DEVICE, RiskRuleId.BLACKLIST_IP}) {
            assertTrue(rule.score() >= RiskAction.BLOCK_MIN,
                    rule + " 分值 " + rule.score() + " 低于 BLOCK 下界 " + RiskAction.BLOCK_MIN);
        }
    }

    @Test
    @DisplayName("每条规则的分值都为正——0 分的规则等于不存在")
    void everyRuleScoresPositive() {
        for (RiskRuleId rule : RiskRuleId.values()) {
            assertTrue(rule.score() > 0, rule + " 分值为 " + rule.score() + "，等于这条规则永远不会影响决策");
        }
    }

    @Test
    @DisplayName("两个用户窗口规则的分值之和为 BLOCK——「10 秒爆发 + 60 秒超量」必须强拦截")
    void userWindowRulesAccumulateToBlock() {
        // 它是「不同计数累加」（决策 D2）在分值表上的体现：两条规则判的是两个
        // 不同的计数，会同时命中。这两条加起来若到不了 BLOCK，最像脚本的那个
        // 画像（短期内反复抢、且次数已经很多）就只会得到 REVIEW。
        int sum = RiskRuleId.USER_WINDOW_SHORT.score() + RiskRuleId.USER_WINDOW_LONG.score();

        assertTrue(sum >= RiskAction.BLOCK_MIN, "两条用户窗口规则相加只有 " + sum + " 分");
        assertEquals(RiskAction.BLOCK, RiskAction.ofScore(sum));
    }

    @Test
    @DisplayName("规则表里十条规则都在，数量不对说明加规则时漏了声明")
    void ruleTableIsComplete() {
        assertEquals(10, RiskRuleId.values().length);
    }
}
