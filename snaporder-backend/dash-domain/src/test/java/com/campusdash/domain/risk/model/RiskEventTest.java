package com.campusdash.domain.risk.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("风险事件值对象")
class RiskEventTest {

    private static final RiskOperationType GRAB = RiskOperationType.GRAB;

    @Test
    @DisplayName("eventId 为空白时构造即失败——两条日志靠它对上")
    void rejectsBlankEventId() {
        // eventId 是 risk_decision 行与风险事件载荷之间唯一的凭据。
        // 空白值在日志里渲染出来就是一段空白，肉眼和按空格切分的脚本都看不见它，
        // 而它会让两条本该对上的日志对不上。null 与 "" 走同一条路。
        assertThrows(IllegalArgumentException.class,
                () -> event(" ", "req-1", RiskOutcome.PASSED, false, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> event(null, "req-1", RiskOutcome.PASSED, false, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> event("evt-1", "", RiskOutcome.PASSED, false, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> event("evt-1", null, RiskOutcome.PASSED, false, List.of()));
    }

    @Test
    @DisplayName("degraded=false 却带着降级原因时构造即失败——两者必须一致")
    void rejectsReasonsWithoutDegraded() {
        var ex = assertThrows(IllegalArgumentException.class, () -> event(
                "evt-1", "req-1", RiskOutcome.PASSED, false,
                List.of(DegradeReason.BLACKLIST_FAILED)));

        assertTrue(ex.getMessage().contains("degraded=false"),
                "报错要说清是哪个方向对不上，否则排查时还得回去读代码：" + ex.getMessage());
    }

    @Test
    @DisplayName("degraded=true 且带原因时正常构造")
    void acceptsDegradedWithReasons() {
        var event = event("evt-1", "req-1", RiskOutcome.PASSED, true,
                List.of(DegradeReason.BLACKLIST_FAILED));

        assertTrue(event.degraded());
        assertEquals(List.of(DegradeReason.BLACKLIST_FAILED), event.degradeReasons());
        assertEquals(RiskAction.PASS, event.decisionAction(),
                "降级但放行是合法组合——降级不等于放行，也不等于拦截");
    }

    @Test
    @DisplayName("degradeReasons 传 null 时收成空列表")
    void nullReasonsBecomeEmptyList() {
        // 用 null 表达「没有降级原因」是很自然的写法，空列表才是这个记录该有的形态。
        // 放过 null 的话，日志渲染与序列化两处都得各判一次 null。
        var event = event("evt-1", "req-1", RiskOutcome.PASSED, false, null);

        assertEquals(List.of(), event.degradeReasons());
    }

    @Test
    @DisplayName("degradeReasons 做防御性拷贝，外部改动不影响已构造的事件")
    void reasonsAreDefensivelyCopied() {
        List<DegradeReason> reasons = new ArrayList<>(List.of(DegradeReason.CLUSTER_FAILED));

        var event = event("evt-1", "req-1", RiskOutcome.PASSED, true, reasons);

        reasons.clear();
        assertEquals(List.of(DegradeReason.CLUSTER_FAILED), event.degradeReasons());
    }

    @Test
    @DisplayName("闸门按动作定档，动作优先于降级（D7）")
    void gateTypeFollowsTheAction() {
        // 逐个动作断言，不是「断言不等于 PASS」：这条映射错了不会报错，
        // 只会让抑制发生在错误的那一类事件上——而 suppress 掉攻击信号正是 D7 要避免的。
        assertEquals(RiskEventGateType.LIMIT, withAction(RiskAction.LIMIT).gateType());
        assertEquals(RiskEventGateType.REVIEW, withAction(RiskAction.REVIEW).gateType());
        assertEquals(RiskEventGateType.BLOCK, withAction(RiskAction.BLOCK).gateType());
    }

    @Test
    @DisplayName("PASS 落到 DEGRADED——走到这里的 PASS 必然带着降级")
    void passFallsIntoTheDegradedGate() {
        // 普通的 PASS（无降级）根本不该产生事件（producesEvent 为假），所以「PASS 事件」
        // 与「降级事件」是同一批。这条同时锁住 D7 的另一半：动作缺席时才轮到降级。
        RiskEvent degradedPass = withActionDegraded(RiskAction.PASS, true);

        assertEquals(RiskEventGateType.DEGRADED, degradedPass.gateType());
    }

    @Test
    @DisplayName("动作与降级同时成立时，动作赢——降级键不得抢走 LIMIT 事件")
    void theActionWinsOverDegraded() {
        // 这是 D7 的整条理由所在：黑名单查询挂了、但用户窗口已经越线时，
        // 两个闸门都成立。若降级键赢，被抑制的是攻击信号本身。
        RiskEvent both = withActionDegraded(RiskAction.LIMIT, true);

        assertTrue(both.degraded(), "前提：这条事件确实同时是降级的");
        assertEquals(RiskEventGateType.LIMIT, both.gateType());
    }

    private static RiskEvent withAction(RiskAction action) {
        return withActionDegraded(action, action != RiskAction.PASS);
    }

    private static RiskEvent withActionDegraded(RiskAction action, boolean degraded) {
        List<DegradeReason> reasons = degraded ? List.of(DegradeReason.BLACKLIST_FAILED) : List.of();
        RiskOutcome outcome = action == RiskAction.PASS ? RiskOutcome.PASSED : RiskOutcome.DENIED;
        return new RiskEvent("evt-1", "req-1", GRAB, 1001L, 2002L, outcome, action,
                degraded, reasons, 1_700_000_000_000L);
    }

    private static RiskEvent event(String eventId, String requestId, RiskOutcome outcome,
                                   boolean degraded, List<DegradeReason> reasons) {
        RiskAction action = outcome == RiskOutcome.DENIED ? RiskAction.BLOCK : RiskAction.PASS;
        return new RiskEvent(eventId, requestId, GRAB, 1001L, 2002L, outcome, action,
                degraded, reasons, 1_700_000_000_000L);
    }
}
