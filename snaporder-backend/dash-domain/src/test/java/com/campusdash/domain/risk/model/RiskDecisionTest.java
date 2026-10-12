package com.campusdash.domain.risk.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("风控决策结果")
class RiskDecisionTest {

    @Test
    @DisplayName("动作与分数对不上时构造即失败——动作必须是总分的纯函数")
    void rejectsActionInconsistentWithScore() {
        // 这条不变式（决策 D1）让「黑名单命中却给出 PASS」「日志里 action=BLOCK
        // riskScore=0」在执行期不可能出现。绕过分值直接指定动作是这套模型
        // 唯一能自洽破坏的方式，所以堵在构造器里，而不是靠 code review。
        assertThrows(IllegalArgumentException.class, () -> new RiskDecision(
                0, RiskAction.BLOCK, List.of(RiskRuleId.BLACKLIST_USER), "1", List.of()));

        assertThrows(IllegalArgumentException.class, () -> new RiskDecision(
                RiskAction.BLOCK_MIN, RiskAction.REVIEW, List.of(), "1", List.of()));
    }

    @Test
    @DisplayName("动作与分数一致时正常构造")
    void acceptsConsistentAction() {
        var decision = new RiskDecision(RiskAction.BLOCK_MIN, RiskAction.BLOCK,
                List.of(RiskRuleId.BLACKLIST_USER), "1", List.of());

        assertEquals(RiskAction.BLOCK_MIN, decision.riskScore());
        assertEquals(RiskAction.BLOCK, decision.action());
        assertFalse(decision.degraded());
    }

    @Test
    @DisplayName("pass() 产出零分 PASS，且不把「关闭」记成「降级」")
    void passIsNotDegraded() {
        // 「关闭」与「降级」是两回事：降级意味着「本该拿到却没拿到的证据」，
        // 关闭时根本没有证据需要拿。混在一起会让降级指标失真，
        // 运维会去排查一个不存在的故障。
        RiskDecision decision = RiskDecision.pass("7");

        assertEquals(0, decision.riskScore());
        assertEquals(RiskAction.PASS, decision.action());
        assertTrue(decision.matchedRules().isEmpty());
        assertTrue(decision.degradeReasons().isEmpty());
        assertFalse(decision.degraded());
        assertEquals("7", decision.ruleVersion(), "关闭期间产出的日志也要能和开启后区分开");
    }

    @Test
    @DisplayName("degraded 是 degradeReasons 非空的派生子")
    void degradedIsDerived() {
        var clean = new RiskDecision(0, RiskAction.PASS, List.of(), "1", List.of());
        var dirty = clean.withDegradeReasons(List.of(DegradeReason.BLACKLIST_FAILED));

        assertFalse(clean.degraded());
        assertTrue(dirty.degraded());
    }

    @Test
    @DisplayName("附上降级原因不改变分数、动作与命中规则——降级不是放行")
    void attachingReasonsKeepsTheVerdict() {
        // 这是「降级不是放行」的实现形态：黑名单挂了但设备维度已经越线时，
        // 决策仍然是 BLOCK，只是在同一行日志里多出降级原因。
        // 写成「有降级就返回 PASS」会漏掉这次拦截。
        var before = new RiskDecision(RiskAction.BLOCK_MIN, RiskAction.BLOCK,
                List.of(RiskRuleId.DEVICE_ACCOUNTS_HIGH), "1", List.of());

        var after = before.withDegradeReasons(
                List.of(DegradeReason.BLACKLIST_FAILED, DegradeReason.CLUSTER_FAILED));

        assertEquals(before.riskScore(), after.riskScore());
        assertEquals(before.action(), after.action());
        assertEquals(before.matchedRules(), after.matchedRules());
        assertEquals(before.ruleVersion(), after.ruleVersion());
        assertEquals(List.of(DegradeReason.BLACKLIST_FAILED, DegradeReason.CLUSTER_FAILED),
                after.degradeReasons());
    }

    @Test
    @DisplayName("两个列表做防御性拷贝，外部改动不影响已构造的结果")
    void listsAreDefensivelyCopied() {
        // matchedRules 与 degradeReasons 擦除后都是 List，位置参数写反了编译期看不出来。
        // 拷贝挡住的是另一件事：构造完之后有人改那个 list，日志行跟着变——
        // 同一次决策在两个时间点打印出不同内容。
        List<RiskRuleId> rules = new ArrayList<>(List.of(RiskRuleId.BLACKLIST_USER));
        List<DegradeReason> reasons = new ArrayList<>(List.of(DegradeReason.BLACKLIST_FAILED));

        var decision = new RiskDecision(RiskAction.BLOCK_MIN, RiskAction.BLOCK, rules, "1", reasons);

        rules.clear();
        reasons.clear();

        assertEquals(List.of(RiskRuleId.BLACKLIST_USER), decision.matchedRules());
        assertEquals(List.of(DegradeReason.BLACKLIST_FAILED), decision.degradeReasons());
    }

    @Nested
    @DisplayName("事件触发判据 producesEvent（P9.4 决策 D2）")
    class ProducesEvent {

        @Test
        @DisplayName("四个动作 × 有无降级：全枚举，只有「PASS 且无降级」为假")
        void enumeratesEveryCombination() {
            // 这条用例的存在理由是 D2 说的「判据只有一处」：它把 8 种组合一次钉死，
            // 于是判据无论被搬到哪儿、被谁重写一遍，只要有一格变了就会红。
            // 逐格写成断言而不是「遍历枚举 + 断言非 PASS 为真」——
            // 后者在有人把判据改成恒真时仍然会通过（恒真时非 PASS 那几格照样是真的）。
            for (RiskAction action : RiskAction.values()) {
                for (boolean degraded : new boolean[]{false, true}) {
                    RiskDecision decision = decision(action, degraded);
                    boolean expected = action != RiskAction.PASS || degraded;
                    assertEquals(expected, decision.producesEvent(),
                            "action=" + action + " degraded=" + degraded);
                }
            }
        }

        @Test
        @DisplayName("普通的 PASS 不发事件——它占了绝大多数请求")
        void plainPassIsSilent() {
            assertFalse(RiskDecision.pass("1").producesEvent(),
                    "无降级的 PASS 是绝大多数请求的结果，发出去只会把队列和离线口径一起淹掉");
        }

        @Test
        @DisplayName("降级但放行仍然发事件——「没挡」不等于「没事」")
        void degradedPassIsPublished() {
            // 降级与放行是两个轴：这一次没拦住是因为证据没拿到，
            // 恰恰是最需要被记下来的情况。写成「只有非 PASS 才发」会让
            // 整个降级闸门（RiskEventGateType.DEGRADED）永远收不到东西。
            RiskDecision decision = RiskDecision.pass("1")
                    .withDegradeReasons(List.of(DegradeReason.BLACKLIST_FAILED));

            assertTrue(decision.producesEvent());
            assertEquals(RiskAction.PASS, decision.action(),
                    "它是 PASS——发事件不是因为它挡了，是因为证据不全");
        }

        @Test
        @DisplayName("风控关闭时不发事件——关闭不是降级")
        void disabledRiskPublishesNothing() {
            // pass() 的两个条件都是假：action=PASS、degraded=false。
            // 「风控关着」由 risk_decision 行的 ruleVersion 体现，不靠事件表达。
            RiskDecision disabled = RiskDecision.pass("1");

            assertFalse(disabled.degraded(), "关闭时没有证据需要拿，不该记成降级");
            assertFalse(disabled.producesEvent());
        }

        /** 造一个动作与分数一致、降级与否指定的决策。分数取该档的下界。 */
        private static RiskDecision decision(RiskAction action, boolean degraded) {
            int score = switch (action) {
                case PASS -> 0;
                case LIMIT -> RiskAction.LIMIT_MIN;
                case REVIEW -> RiskAction.REVIEW_MIN;
                case BLOCK -> RiskAction.BLOCK_MIN;
            };
            var decision = new RiskDecision(score, action, List.of(), "1", List.of());
            return degraded
                    ? decision.withDegradeReasons(List.of(DegradeReason.USER_WINDOW_FAILED))
                    : decision;
        }
    }
}
