package com.campusdash.domain.risk.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策纯函数的全部行为。这一批是本阶段最值钱的部分：Lua 没有单测抓手，
 * 但决策逻辑全是纯输入输出，绝大多数复杂度压在这里，后面的 I/O 部分就能尽量薄。
 *
 * <p>阈值取生产同形的一套：用户窗口 5/30、设备低 3 高 8、IP 账号 10、IP 速率 100。
 */
@DisplayName("风控规则判定")
class RiskRulesTest {

    private static final RiskRules RULES = new RiskRules(5, 30, 3, 8, 10, 100, 10_000, "1");

    private static final UserWindow NO_WINDOW = new UserWindow(0, 0, false);

    /** 只给用户窗口，设备/IP 全部为空。 */
    private static RiskDecision windows(int count10s, int count60s) {
        return RULES.evaluate(new UserWindow(count10s, count60s, false), Cluster.EMPTY,
                BlacklistHits.NONE, false);
    }

    /** 只给设备维度。 */
    private static RiskDecision device(int deviceAccounts) {
        return RULES.evaluate(NO_WINDOW, new Cluster(deviceAccounts, 0, 0),
                BlacklistHits.NONE, false);
    }

    /** 只给 IP 维度。 */
    private static RiskDecision ip(int ipAccounts, int ipRequests) {
        return RULES.evaluate(NO_WINDOW, new Cluster(0, ipAccounts, ipRequests),
                BlacklistHits.NONE, false);
    }

    @Nested
    @DisplayName("每条规则的单侧边界")
    class PerRuleBoundary {

        @Test
        @DisplayName("用户 10 秒窗口：等于阈值不命中，超过才命中")
        void userWindowShort() {
            // 规则是「超过 N 次」而不是「达到 N 次」。差一的方向搞反会让第 5 次
            // 正常请求就被限频——边上少一次和边上多一次，用户感知完全不同。
            assertEquals(List.of(), windows(5, 0).matchedRules(), "count10s = 5 不该命中");
            assertEquals(List.of(RiskRuleId.USER_WINDOW_SHORT), windows(6, 0).matchedRules());
        }

        @Test
        @DisplayName("用户 60 秒窗口：30 是阈值而不是档位下界，命名要带上下文")
        void userWindowLong() {
            // 这里的 30 是「60 秒窗口的阈值」，与 RiskAction.LIMIT_MIN = 30 毫无关系。
            // 两个 30 在同一个文件里出现，断言必须写清楚判的是哪一个。
            assertEquals(List.of(), windows(0, 30).matchedRules(), "count60s = 30 不该命中");
            assertEquals(List.of(RiskRuleId.USER_WINDOW_LONG), windows(0, 31).matchedRules());
        }

        @Test
        @DisplayName("设备账号低阈值：等于 3 不命中，等于 4 命中低档")
        void deviceLow() {
            assertEquals(List.of(), device(3).matchedRules());
            assertEquals(List.of(RiskRuleId.DEVICE_ACCOUNTS_LOW), device(4).matchedRules());
        }

        @Test
        @DisplayName("设备账号高阈值：等于 8 仍走低档，等于 9 才跳到高档")
        void deviceHigh() {
            assertEquals(List.of(RiskRuleId.DEVICE_ACCOUNTS_LOW), device(8).matchedRules());
            assertEquals(List.of(RiskRuleId.DEVICE_ACCOUNTS_HIGH), device(9).matchedRules());
        }

        @Test
        @DisplayName("IP 账号：等于 10 不命中，等于 11 命中")
        void ipAccounts() {
            assertEquals(List.of(), ip(10, 0).matchedRules());
            assertEquals(List.of(RiskRuleId.IP_ACCOUNTS), ip(11, 0).matchedRules());
        }

        @Test
        @DisplayName("IP 速率：等于 100 不命中，等于 101 命中")
        void ipRate() {
            assertEquals(List.of(), ip(0, 100).matchedRules());
            assertEquals(List.of(RiskRuleId.IP_RATE), ip(0, 101).matchedRules());
        }

        @Test
        @DisplayName("全部不命中时零分 PASS，且没有任何命中规则")
        void cleanRequestIsPass() {
            RiskDecision decision = RULES.evaluate(NO_WINDOW, Cluster.EMPTY, BlacklistHits.NONE, false);

            assertEquals(0, decision.riskScore());
            assertEquals(RiskAction.PASS, decision.action());
            assertEquals(List.of(), decision.matchedRules());
        }
    }

    @Nested
    @DisplayName("同一计数取最高（决策 D2）")
    class SameCounterTakesHighest {

        @Test
        @DisplayName("9 个账号产出**一条**高档命中、总分 80——不是两条、140")
        void deviceHighDoesNotDoubleCount() {
            // 这是 else-if 链的回归锁。两条独立判断再取 max 会得到
            // [LOW, HIGH]、60 + 80 = 140：动作碰巧还是 BLOCK，分数却是错的，
            // 而分数会进日志、进离线指标。这条断言守的就是那个错。
            RiskDecision decision = device(9);

            assertEquals(List.of(RiskRuleId.DEVICE_ACCOUNTS_HIGH), decision.matchedRules());
            assertEquals(RiskAction.BLOCK_MIN, decision.riskScore());
            assertEquals(RiskAction.BLOCK, decision.action());
        }

        @Test
        @DisplayName("账号数从 0 涨到 20，设备维度始终最多命中一条")
        void deviceNeverMatchesTwoRules() {
            for (int accounts = 0; accounts <= 20; accounts++) {
                long hits = device(accounts).matchedRules().stream()
                        .filter(r -> r == RiskRuleId.DEVICE_ACCOUNTS_LOW
                                || r == RiskRuleId.DEVICE_ACCOUNTS_HIGH)
                        .count();

                assertTrue(hits <= 1, "deviceAccounts = " + accounts + " 命中了 " + hits + " 条设备规则");
            }
        }
    }

    @Nested
    @DisplayName("不同计数累加（决策 D2 的另一半）")
    class DifferentCountersAccumulate {

        @Test
        @DisplayName("10 秒内 6 次 + 60 秒内 31 次 → 90 分 BLOCK")
        void burstPlusVolume() {
            // 两条用户窗口规则判的是**两个不同的计数**，所以同时命中要累加。
            // 这正是最像脚本的画像：短期内反复抢，且总数已经很多。
            // 若按「同维度取最高」处理，这个画像只能得到 60 分 REVIEW。
            RiskDecision decision = windows(6, 31);

            assertEquals(List.of(RiskRuleId.USER_WINDOW_SHORT, RiskRuleId.USER_WINDOW_LONG),
                    decision.matchedRules());
            assertEquals(RiskAction.LIMIT_MIN + RiskAction.REVIEW_MIN, decision.riskScore());
            assertEquals(RiskAction.BLOCK, decision.action());
        }

        @Test
        @DisplayName("用户窗口 30 分单独只到 LIMIT，加上 IP 速率才够 REVIEW")
        void accumulationCrossesBand() {
            // 「仅加分」的 IP 维度与用户窗口叠加后能推动档位，这是它存在的意义。
            RiskDecision shortOnly = windows(6, 0);
            assertEquals(RiskAction.LIMIT, shortOnly.action());

            RiskDecision withIp = RULES.evaluate(new UserWindow(6, 0, false),
                    new Cluster(0, 0, 101), BlacklistHits.NONE, false);
            assertEquals(RiskAction.LIMIT_MIN + RiskRuleId.IP_RATE.score(), withIp.riskScore());
            assertEquals(RiskAction.REVIEW, withIp.action());
        }
    }

    @Nested
    @DisplayName("IP 账号聚集仅加分（决策 D4）")
    class IpAccountsBonusOnly {

        @Test
        @DisplayName("11 个账号单独命中 → 20 分、动作仍是 PASS")
        void aloneItDoesNotAct() {
            // 这条是「IP_ACCOUNTS 不得被调过 LIMIT_MIN」的行为守卫。
            // 校园网出口 IP 高度共享，「同 IP 11 个账号」在平时就是常态，
            // 单独定性会大面积误伤宿舍与机房。
            RiskDecision decision = ip(11, 0);

            assertEquals(List.of(RiskRuleId.IP_ACCOUNTS), decision.matchedRules());
            assertEquals(20, decision.riskScore());
            assertEquals(RiskAction.PASS, decision.action());
        }

        @Test
        @DisplayName("它仍然加分：与设备低档叠加后 20 + 60 = 80 → BLOCK")
        void stillPushesTheScoreUp() {
            RiskDecision decision = RULES.evaluate(NO_WINDOW, new Cluster(4, 11, 0),
                    BlacklistHits.NONE, false);

            assertEquals(List.of(RiskRuleId.DEVICE_ACCOUNTS_LOW, RiskRuleId.IP_ACCOUNTS),
                    decision.matchedRules());
            assertEquals(RiskRuleId.DEVICE_ACCOUNTS_LOW.score() + 20, decision.riskScore());
            assertEquals(RiskAction.BLOCK, decision.action());
        }
    }

    @Nested
    @DisplayName("黑名单（决策 D3）")
    class Blacklist {

        @Test
        @DisplayName("三类各自单独命中都是 BLOCK，且分数等于 BLOCK 下界而不是 0")
        void eachDimensionBlocksAlone() {
            // 「分数等于 BLOCK_MIN 而不是 0」是这里的关键：黑名单的实现形态是
            // 「以最高分值参与累加」，不是一条绕过分数的旁路分支。
            // 写成旁路，日志里就会出现 action=BLOCK riskScore=0——无法解释的一行，
            // 离线统计也没法按分数给拦截分级。
            record Case(String name, BlacklistHits hits, RiskRuleId rule) {
            }
            List<Case> cases = List.of(
                    new Case("用户", new BlacklistHits(true, false, false), RiskRuleId.BLACKLIST_USER),
                    new Case("设备", new BlacklistHits(false, true, false), RiskRuleId.BLACKLIST_DEVICE),
                    new Case("IP", new BlacklistHits(false, false, true), RiskRuleId.BLACKLIST_IP));

            for (Case c : cases) {
                RiskDecision decision = RULES.evaluate(NO_WINDOW, Cluster.EMPTY, c.hits(), false);

                assertEquals(List.of(c.rule()), decision.matchedRules(), c.name() + "黑名单");
                assertEquals(RiskAction.BLOCK_MIN, decision.riskScore(), c.name() + "黑名单");
                assertEquals(RiskAction.BLOCK, decision.action(), c.name() + "黑名单");
            }
        }

        @Test
        @DisplayName("三类同时命中各自计分，不是合并成一条")
        void allThreeAreReportedSeparately() {
            // 分值相同不代表可以合并：日志里必须能区分是哪个维度命中的——
            // 「用户被封」和「IP 被封」的处置动作完全不同。
            RiskDecision decision = RULES.evaluate(NO_WINDOW, Cluster.EMPTY,
                    new BlacklistHits(true, true, true), false);

            assertEquals(List.of(RiskRuleId.BLACKLIST_USER, RiskRuleId.BLACKLIST_DEVICE,
                    RiskRuleId.BLACKLIST_IP), decision.matchedRules());
            assertEquals(RiskAction.BLOCK_MIN * 3, decision.riskScore());
        }

        @Test
        @DisplayName("黑名单与普通风险分累加，不是覆盖")
        void accumulatesWithOrdinaryRules() {
            RiskDecision decision = RULES.evaluate(new UserWindow(6, 0, false), Cluster.EMPTY,
                    new BlacklistHits(false, true, false), false);

            assertEquals(RiskRuleId.USER_WINDOW_SHORT.score() + RiskAction.BLOCK_MIN,
                    decision.riskScore());
            assertEquals(List.of(RiskRuleId.USER_WINDOW_SHORT, RiskRuleId.BLACKLIST_DEVICE),
                    decision.matchedRules());
        }
    }

    @Nested
    @DisplayName("限频冷却（决策 D5）")
    class LimitCooldown {

        @Test
        @DisplayName("冷却命中走同一套分数模型 → LIMIT、分数为 LIMIT 下界")
        void goesThroughTheScoreModel() {
            // 冷却命中不另开分支，所以它的分数是 LIMIT_MIN 而不是 0。
            // 另开分支的话日志里会出现 action=LIMIT riskScore=0。
            RiskDecision decision = RULES.evaluate(NO_WINDOW, Cluster.EMPTY,
                    BlacklistHits.NONE, true);

            assertEquals(List.of(RiskRuleId.LIMIT_COOLDOWN), decision.matchedRules());
            assertEquals(RiskAction.LIMIT_MIN, decision.riskScore());
            assertEquals(RiskAction.LIMIT, decision.action());
        }

        @Test
        @DisplayName("冷却期间再次命中其它规则，分数继续累加")
        void accumulatesWithOtherRules() {
            RiskDecision decision = RULES.evaluate(new UserWindow(0, 31, false), Cluster.EMPTY,
                    BlacklistHits.NONE, true);

            assertEquals(RiskAction.LIMIT_MIN + RiskAction.REVIEW_MIN, decision.riskScore());
            assertEquals(List.of(RiskRuleId.USER_WINDOW_LONG, RiskRuleId.LIMIT_COOLDOWN),
                    decision.matchedRules());
        }
    }

    @Nested
    @DisplayName("降级不是放行")
    class Degradation {

        @Test
        @DisplayName("黑名单查询失败时传 NONE 继续，设备维度越线仍然 BLOCK")
        void blacklistFailureDoesNotRescueTheRequest() {
            // 查询失败时调用方传 BlacklistHits.NONE，同时把 BLACKLIST_FAILED 记进原因。
            // 关键在于：这里传 false 之后，其它证据照常参与决策——
            // 若写成「有降级就返回 PASS」，这次拦截就漏了。
            RiskDecision decision = RULES.evaluate(NO_WINDOW, new Cluster(9, 0, 0),
                    BlacklistHits.NONE, false)
                    .withDegradeReasons(List.of(DegradeReason.BLACKLIST_FAILED));

            assertEquals(RiskAction.BLOCK, decision.action());
            assertEquals(RiskAction.BLOCK_MIN, decision.riskScore());
            assertTrue(decision.degraded());
            assertEquals(List.of(DegradeReason.BLACKLIST_FAILED), decision.degradeReasons());
        }

        @Test
        @DisplayName("所有证据都拿不到时分数为 0 → PASS，但原因全部记下")
        void allEvidenceMissingDegradesToPassFactually() {
            // 这是唯一的「事实性放行」：不是某个分支决定放行，而是零分的自然结果。
            RiskDecision decision = RiskDecision.pass("1")
                    .withDegradeReasons(List.of(DegradeReason.BLACKLIST_FAILED,
                            DegradeReason.CLUSTER_FAILED, DegradeReason.USER_WINDOW_FAILED));

            assertEquals(RiskAction.PASS, decision.action());
            assertTrue(decision.degraded());
            assertEquals(3, decision.degradeReasons().size());
        }

        @Test
        @DisplayName("evaluate 本身不产出降级原因——那是调用方捕获异常后附上的")
        void evaluateNeverInventsReasons() {
            // 规则层拿到的是采集结果，它看不出「计数 0」究竟是「用户真的没请求过」
            // 还是「采集挂了」。把降级塞进这个签名会逼规则层去理解 Redis 故障。
            assertFalse(RULES.evaluate(NO_WINDOW, Cluster.EMPTY, BlacklistHits.NONE, false).degraded());
        }
    }

    @Nested
    @DisplayName("matchedRules 顺序稳定")
    class OrderStability {

        @Test
        @DisplayName("九条规则同时命中时，输出顺序等于 RiskRuleId 的声明顺序")
        void fullSequenceFollowsDeclarationOrder() {
            // 顺序不是审美问题：matchedRules 是日志行的一部分，顺序不稳定会让
            // 同一次决策在不同环境下产出不同文本，离线脚本去重时把它当成两条。
            RiskDecision decision = RULES.evaluate(
                    new UserWindow(6, 31, false),
                    new Cluster(9, 11, 101),
                    new BlacklistHits(true, true, true),
                    true);

            assertEquals(List.of(
                    RiskRuleId.USER_WINDOW_SHORT,
                    RiskRuleId.USER_WINDOW_LONG,
                    RiskRuleId.DEVICE_ACCOUNTS_HIGH,
                    RiskRuleId.IP_ACCOUNTS,
                    RiskRuleId.IP_RATE,
                    RiskRuleId.BLACKLIST_USER,
                    RiskRuleId.BLACKLIST_DEVICE,
                    RiskRuleId.BLACKLIST_IP,
                    RiskRuleId.LIMIT_COOLDOWN), decision.matchedRules());
            assertEquals(RiskAction.BLOCK, decision.action());
        }

        @Test
        @DisplayName("命中规则的序号严格递增——列表里不会出现倒序")
        void ordinalsAreStrictlyIncreasing() {
            RiskDecision decision = RULES.evaluate(
                    new UserWindow(6, 31, false),
                    new Cluster(9, 11, 101),
                    new BlacklistHits(true, true, true),
                    true);

            int previous = -1;
            for (RiskRuleId rule : decision.matchedRules()) {
                assertTrue(rule.ordinal() > previous,
                        "规则 " + rule + " 出现在序号 " + previous + " 之后，顺序被打乱了");
                previous = rule.ordinal();
            }
        }
    }
}
