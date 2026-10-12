package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.model.RiskOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消费侧三行的**逐字**契约（P9.4 §4.3、§6.2）。
 *
 * <p>本类与 {@code RiskEventPublishLogTest} 是一对：那半边锁「写下去的载荷长什么样」，
 * 这半边锁「读出来的行长什么样」。§8.3 的 {@code analyze_risk_events.py} 按行首标记
 * 定位、按字段名取值、按 {@code decisionAction} 分组，所以下面每条断言的都是**整行文本**。
 *
 * <p>三行里最容易被忽视的是第三行：它没有 {@code eventId}（解析失败时根本拿不到），
 * 所以「四行都有 id」这种从生产侧得来的直觉在这里不成立——那条断言必须显式写成反面。
 */
@DisplayName("风控事件消费侧日志行")
class RiskEventConsumeLogTest {

    private static final long OCCURRED_AT = 1_757_600_000_000L;

    /** 一条普通事件：{@code LIMIT}、未降级，所以 {@code degradeReasons} 必须是空列表。 */
    private static RiskEvent event() {
        return new RiskEvent("evt-1", "req-abc", RiskOperationType.GRAB, 3003L, 2001L,
                RiskOutcome.DENIED, RiskAction.LIMIT, false, List.of(), OCCURRED_AT);
    }

    /**
     * 一条降级事件。两个原因**故意逆着 {@link DegradeReason} 的声明序给**：
     * 「按给定顺序连接」这条约定，只有在给定顺序与排序顺序不同时才验得出来——
     * 给一个原因，或者给两个恰好有序的原因，那么无论实现是照抄还是排序都能通过。
     *
     * <p>消费侧拿到的顺序就是生产端写进载荷的顺序，这里照原样印出来，**不重新排序**：
     * 排序那条约定属于产出这个列表的地方（{@code RiskDecision} 的声明序），
     * 在第二个地方实现一遍的结果不是更保险，而是将来只会有一处被改。
     */
    private static RiskEvent degradedEvent() {
        return new RiskEvent("evt-2", "req-def", RiskOperationType.GRAB, 3003L, 2001L,
                RiskOutcome.PASSED, RiskAction.PASS, true,
                List.of(DegradeReason.BLACKLIST_FAILED, DegradeReason.USER_WINDOW_FAILED), OCCURRED_AT);
    }

    // ------------------------------------------------------------------
    // risk_event_processed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("消费行逐字匹配设计 §4.3 的格式")
    void processedLineMatchesSpecExactly() {
        String line = RiskEventConsumeLog.format(event(), 42L, 1);

        assertEquals("risk_event_processed eventId=evt-1 requestId=req-abc"
                + " operationType=GRAB errandId=3003 runnerId=2001"
                + " outcome=DENIED decisionAction=LIMIT degraded=false degradeReasons=-"
                + " occurredAt=1757600000000 lagMs=42 deliveryAttempt=1", line);
    }

    @Test
    @DisplayName("消费行的十二个字段都在，且顺序固定")
    void processedFieldOrderIsStable() {
        // 顺序不是审美问题：分析脚本若按位置切分，换序会静默产出错指标。
        String[] expected = {"eventId=", "requestId=", "operationType=", "errandId=",
                "runnerId=", "outcome=", "decisionAction=", "degraded=", "degradeReasons=",
                "occurredAt=", "lagMs=", "deliveryAttempt="};

        int cursor = -1;
        for (String field : expected) {
            int at = RiskEventConsumeLog.format(event(), 42L, 1).indexOf(field);
            assertTrue(at > cursor, "字段 " + field + " 缺失或顺序不对");
            cursor = at;
        }
    }

    @Test
    @DisplayName("lagMs 为负时按 0 记，occurredAt 保留原值")
    void negativeLagIsClampedButOccurredAtIsKept() {
        // 两个时钟不在同一台机器上，负值是时钟漂移的正常表现，不该被读成「这条日志有问题」。
        // occurredAt 必须照原样打印：把「漂移了多少」从数据里抹掉，就没有任何地方能再看出
        // 漂移存在了——而 P9.5 的 P99 延迟恰恰要的就是这两个时间的关系。
        String line = RiskEventConsumeLog.format(event(), -1234L, 1);

        assertTrue(line.endsWith(" occurredAt=1757600000000 lagMs=0 deliveryAttempt=1"), line);
        assertFalse(line.contains("-1234"), line);
    }

    @Test
    @DisplayName("零延迟照常渲染成 0，不是 -——它不是缺失值")
    void zeroLagRendersAsZero() {
        // 这是夹取改动最容易顺手做错的地方：把「≤0」写成「≤0 就当没有值」。
        // lagMs 恒有值（它就是两个时间戳的差），- 在这个字段上不可达。
        String line = RiskEventConsumeLog.format(event(), 0L, 1);

        assertTrue(line.contains(" lagMs=0 "), line);
        assertFalse(line.contains("lagMs=-"), line);
    }

    @Test
    @DisplayName("降级原因按给定顺序用 | 连接，不是排序后的顺序")
    void degradeReasonsKeepGivenOrder() {
        String line = RiskEventConsumeLog.format(degradedEvent(), 42L, 1);

        assertTrue(line.contains(" degraded=true degradeReasons=BLACKLIST_FAILED|USER_WINDOW_FAILED "),
                line);
        assertTrue(line.contains(" outcome=PASSED decisionAction=PASS "), line);
    }

    @Test
    @DisplayName("空原因集渲染成 -，而不是留下一个空的 degradeReasons=")
    void emptyReasonsRenderAsDash() {
        // degradeReasons=- 是这一行上「空值渲染 -」唯一的落点：eventId 与 requestId
        // 都被 RiskEvent 的紧凑构造器挡过空白，所以那两个字段上的 - 不可达。
        String line = RiskEventConsumeLog.format(event(), 42L, 1);

        assertTrue(line.contains(" degradeReasons=- "), line);
        assertFalse(line.contains("degradeReasons= "), line);
    }

    @Test
    @DisplayName("requestId 带空格时被中性化——客户端伪造不出一个 decisionAction 字段")
    void requestIdWithSpacesIsNeutralized() {
        // D13 在**这条行**上的调用路径锁。与生产侧那条行是同一个通路的两端：
        // 值在那边被净化、在这一侧被同一个净化器挡第二次——两次都必要，因为
        // 净化器的调用点分散在三个模块里，谁都不是另一方的保险。
        RiskEvent forged = new RiskEvent("evt-1", "x decisionAction=BLOCK", RiskOperationType.GRAB,
                3003L, 2001L, RiskOutcome.DENIED, RiskAction.LIMIT, false, List.of(), OCCURRED_AT);

        String line = RiskEventConsumeLog.format(forged, 42L, 1);

        assertTrue(line.contains("requestId=x_decisionAction=BLOCK"), line);
        // 逐 token 断言：伪字段不能是一个独立的 token。用 contains 会漏掉
        // 「……requestId=x decisionAction=BLOCK ……」这种只多一个空格的形态。
        assertFalse(List.of(line.split(" ")).contains("decisionAction=BLOCK"), line);
        assertEquals(1, List.of(line.split(" ")).stream()
                .filter(t -> t.startsWith("decisionAction=")).count(), line);
    }

    @Test
    @DisplayName("投递次数原样透传——重投递的第二次必须与第一次分得开")
    void deliveryAttemptIsPassedThrough() {
        assertTrue(RiskEventConsumeLog.format(event(), 42L, 3).endsWith(" deliveryAttempt=3"));
    }

    @Test
    @DisplayName("消费行只有一行——客户端塞不进换行")
    void processedLineIsAlwaysSingleLine() {
        assertFalse(RiskEventConsumeLog.format(event(), 42L, 1).contains("\n"));
    }

    // ------------------------------------------------------------------
    // risk_review_pending
    // ------------------------------------------------------------------

    @Test
    @DisplayName("待审行逐字匹配设计 §4.3 的格式")
    void reviewPendingLineMatchesSpecExactly() {
        RiskEvent review = new RiskEvent("evt-3", "req-ghi", RiskOperationType.GRAB, 3003L, 2001L,
                RiskOutcome.DENIED, RiskAction.REVIEW, false, List.of(), OCCURRED_AT);

        String line = RiskEventConsumeLog.formatReviewPending(review);

        assertEquals("risk_review_pending eventId=evt-3 requestId=req-ghi"
                + " operationType=GRAB errandId=3003 runnerId=2001"
                + " occurredAt=1757600000000", line);
    }

    @Test
    @DisplayName("待审行不带 decisionAction 与 degradeReasons——它是「去查这一单」的最小集合")
    void reviewPendingLineCarriesTheMinimum() {
        // decisionAction 在这条行上恒为 REVIEW（行的存在本身就是那个判断），
        // 写上就是个恒为常量的字段；degradeReasons 从配套的 processed 行按 eventId 取。
        // 谁「顺手」把它俩加进来，就必须同时想清楚它们在按行切分的脚本里算谁的。
        RiskEvent review = new RiskEvent("evt-3", "req-ghi", RiskOperationType.GRAB, 3003L, 2001L,
                RiskOutcome.DENIED, RiskAction.REVIEW, false, List.of(), OCCURRED_AT);

        String line = RiskEventConsumeLog.formatReviewPending(review);

        assertFalse(line.contains("decisionAction="), line);
        assertFalse(line.contains("degradeReasons="), line);
        assertFalse(line.contains("lagMs="), line);
    }

    @Test
    @DisplayName("待审行的六个字段都在，且顺序固定")
    void reviewPendingFieldOrderIsStable() {
        RiskEvent review = new RiskEvent("evt-3", "req-ghi", RiskOperationType.GRAB, 3003L, 2001L,
                RiskOutcome.DENIED, RiskAction.REVIEW, false, List.of(), OCCURRED_AT);
        String[] expected = {"eventId=", "requestId=", "operationType=", "errandId=",
                "runnerId=", "occurredAt="};

        int cursor = -1;
        for (String field : expected) {
            int at = RiskEventConsumeLog.formatReviewPending(review).indexOf(field);
            assertTrue(at > cursor, "字段 " + field + " 缺失或顺序不对");
            cursor = at;
        }
    }

    // ------------------------------------------------------------------
    // risk_event_invalid
    // ------------------------------------------------------------------

    @Test
    @DisplayName("非法行逐字匹配实现期定档的格式（设计只给了两个字段名，reason 是补的）")
    void invalidLineMatchesSpecExactly() {
        String line = RiskEventConsumeLog.formatInvalid(new IllegalArgumentException("坏消息"),
                RiskEventInvalidReason.MALFORMED_JSON, 1);

        assertEquals("risk_event_invalid error=IllegalArgumentException"
                + " reason=MALFORMED_JSON deliveryAttempt=1", line);
    }

    @Test
    @DisplayName("非法行只记异常**类型**，异常消息一个字都不进日志")
    void invalidLineRecordsTheTypeOnly() {
        // 解析器的消息里带着它正在解析的那段原始 JSON——而 requestId 就在里面。
        // 入参收窄成 Throwable 就是为了让「顺手把 getMessage() 写进日志」不再是一种
        // 可能的写法，这条断言是那个收窄的可见证据。
        Throwable error = new IllegalArgumentException("字段 requestId 缺失: {\"requestId\":\"x\"}");

        String line = RiskEventConsumeLog.formatInvalid(error,
                RiskEventInvalidReason.MISSING_FIELD, 1);

        assertTrue(line.contains("error=IllegalArgumentException"), line);
        assertFalse(line.contains("requestId"), line);
        assertFalse(line.contains("缺失"), line);
    }

    @Test
    @DisplayName("异常类型取不到时渲染成 -，而不是留一个空的 error=")
    void blankErrorTypeRendersAsDash() {
        Throwable anonymous = new IllegalArgumentException() {
        };

        String line = RiskEventConsumeLog.formatInvalid(anonymous,
                RiskEventInvalidReason.INCONSISTENT_EVENT, 1);

        assertTrue(line.contains(" error=- "), line);
    }

    @Test
    @DisplayName("非法行的三个字段都在，且顺序固定")
    void invalidFieldOrderIsStable() {
        String[] expected = {"error=", "reason=", "deliveryAttempt="};

        String line = RiskEventConsumeLog.formatInvalid(new IllegalArgumentException("x"),
                RiskEventInvalidReason.BAD_NUMBER, 2);

        int cursor = -1;
        for (String field : expected) {
            int at = line.indexOf(field);
            assertTrue(at > cursor, "字段 " + field + " 缺失或顺序不对：" + line);
            cursor = at;
        }
    }

    @Test
    @DisplayName("非法行没有 eventId——解析失败时根本拿不到它，这不是缺字段")
    void invalidLineCarriesNoEventId() {
        // 生产侧那两行都有 eventId，于是「这行怎么少了一个 id」看起来像 bug。
        // 它是唯一一行**结构上**拿不到 id 的：id 就在解析失败的那段字节里，
        // 而那段字节不许进日志（见 invalidLineRecordsTheTypeOnly）。
        String line = RiskEventConsumeLog.formatInvalid(new IllegalArgumentException("x"),
                RiskEventInvalidReason.MISSING_FIELD, 1);

        assertFalse(line.contains("eventId"), line);
    }

    @Test
    @DisplayName("五个原因值一个不多一个不少，且声明顺序固定")
    void reasonValuesAreFrozen() {
        // 与 RiskEventPublishResult 同一个约定：枚举名原样进日志、是脚本要匹配的字面量，
        // 顺序管的是排查时「先怀疑哪一类」的排列。长度断言防「顺手加第六类」——
        // 加值必须同时改设计 §6.2 与脚本。
        assertEquals(List.of(
                        RiskEventInvalidReason.MISSING_FIELD,
                        RiskEventInvalidReason.BAD_NUMBER,
                        RiskEventInvalidReason.MALFORMED_JSON,
                        RiskEventInvalidReason.UNKNOWN_ENUM,
                        RiskEventInvalidReason.INCONSISTENT_EVENT),
                List.of(RiskEventInvalidReason.values()));
    }

    @Test
    @DisplayName("五个原因渲染出的行两两不同——否则短码就只是个装饰")
    void everyReasonRendersDifferently() {
        // 这条防的是「枚举加了但日志上分不出来」：只要有一个值渲染成另一值的样子
        // （复制粘贴时最常见的失误），归因能力就退化回「只有一个 error= 字段」的处境。
        long distinct = List.of(RiskEventInvalidReason.values()).stream()
                .map(reason -> RiskEventConsumeLog.formatInvalid(new IllegalArgumentException("x"),
                        reason, 1))
                .distinct()
                .count();

        assertEquals(RiskEventInvalidReason.values().length, distinct);
    }
}
