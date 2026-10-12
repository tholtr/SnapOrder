package com.campusdash.worker;

import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.model.RiskOutcome;
import com.campusdash.infrastructure.risk.RiskEventInvalidReason;
import com.campusdash.infrastructure.risk.RocketMqRiskEventAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 载荷解析的契约（P9.4 设计 §6.1、§8.1）。
 *
 * <p><b>往返测试用的是生产端真正的序列化器</b>（{@code RocketMqRiskEventAdapter.payloadOf}，
 * 为此改成了 {@code public}）而不是一份手抄的载荷字面量。判别标准很简单：
 * 手抄的字面量能证明「解析器读得懂我抄的这一份」，而往返能证明「转义与反转义互为逆运算」
 * ——后者才是这两个模块之间真正的那条约束，且它坏掉时**两侧各自的单测都是绿的**。
 *
 * <p>其余用例分两类：一类把模板载荷逐处改坏（每种坏法对应一个
 * {@link RiskEventInvalidReason}，那是日志行 {@code reason=} 的全部取值来源），
 * 另一类锁住「不该被当成坏的」的形态（多余字段、空白、空原因集）。
 */
@DisplayName("风险事件载荷解析")
class RiskEventPayloadTest {

    /** 与生产端 {@code payloadOf} 逐字同形的一份模板，用来逐处改坏。 */
    private static final String VALID = "{\"eventId\":\"evt-1\",\"requestId\":\"req-abc\","
            + "\"operationType\":\"GRAB\",\"errandId\":3003,\"runnerId\":2001,"
            + "\"outcome\":\"DENIED\",\"decisionAction\":\"LIMIT\",\"degraded\":false,"
            + "\"degradeReasons\":\"\",\"occurredAt\":1757600000000}";

    /**
     * 两个「该出现但按字符写会出事」的码点，用 {@code (char)} 常量而不是转义写法。
     *
     * <p>U+2028 行分隔符：它在 JSON 里合法、在生产端**不转义**（见
     * {@code RocketMqRiskEventAdapter.escapeJson} 的说明），但很多工具会把它当换行
     * ——它是「这一行到底会不会被切断」的唯一证物。U+0001 控制字符：生产端必须把它
     * 写成四位十六进制转义，正好压一压解析器那半边转义表里短转义之外的分支。
     *
     * <p>写成十六进制的转义序列也是对的，但这个仓库出过一次「打算写转义、实际写进去的
     * 是那个字符本身」的事故（见 {@code 实现期发现.md}）：那种写法编译器和用例都看不出来，
     * 只在按字节看源码时才现形。{@code (char) 0x2028} 是一串纯 ASCII 源码，
     * 读得出来，也验得动。
     */
    private static final char LINE_SEPARATOR = (char) 0x2028;

    private static final char START_OF_HEADING = (char) 0x0001;

    private static RiskEvent expected() {
        return new RiskEvent("evt-1", "req-abc", RiskOperationType.GRAB, 3003L, 2001L,
                RiskOutcome.DENIED, RiskAction.LIMIT, false, List.of(), 1_757_600_000_000L);
    }

    /**
     * 把模板里的一段换成别的东西。
     *
     * <p>先断言那一段**确实在**模板里：否则模板哪天被改动，这里会静默地什么也没换，
     * 于是「非法输入被拒」的用例变成在解析一份合法载荷，而它照样是绿的。
     */
    private static String tampered(String fragment, String replacement) {
        assertTrue(VALID.contains(fragment), "前提：模板里应当有这段——" + fragment);
        return VALID.replace(fragment, replacement);
    }

    private static RiskEventInvalidReason reasonOf(String json) {
        RiskEventPayload.Invalid ex = assertThrows(RiskEventPayload.Invalid.class,
                () -> RiskEventPayload.parse(json));
        return ex.reason();
    }

    // ------------------------------------------------------------------
    // 往返
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模板载荷解析成预期的十个字段")
    void parsesTheTemplate() {
        assertEquals(expected(), RiskEventPayload.parse(VALID));
    }

    @Test
    @DisplayName("往返：经生产端序列化再解析回来，逐字段相同——含需要转义的 requestId")
    void roundTripKeepsEveryField() {
        // requestId 里塞满会让两侧任一处措手不及的东西：引号与冒号能伪造出一个字段、
        // 换行与行分隔符能造出一行、反斜杠是转义符本身、控制字符必须走四位转义。
        String requestId = "x\",\"decisionAction\":\"BLOCK\n \\ " + LINE_SEPARATOR + " "
                + START_OF_HEADING + " tail";
        RiskEvent original = new RiskEvent("evt-1", requestId, RiskOperationType.GRAB, 3003L, 2001L,
                RiskOutcome.DENIED, RiskAction.LIMIT, false, List.of(), 1_757_600_000_000L);

        RiskEvent parsed = RiskEventPayload.parse(RocketMqRiskEventAdapter.payloadOf(original));

        assertEquals(original, parsed);
        // 逐字段再钉一次：record 的 equals 比较的是这十个值，但若将来加了字段而
        // 解析器没跟着改，equals 会先把两个都在默认值上的字段判等——那时这条会红。
        assertEquals(requestId, parsed.requestId());
        assertEquals(RiskAction.LIMIT, parsed.decisionAction());
    }

    @Test
    @DisplayName("往返：降级原因的顺序也照原样回来")
    void roundTripKeepsDegradeReasonOrder() {
        RiskEvent original = new RiskEvent("evt-2", "req-def", RiskOperationType.GRAB, 3003L, 2001L,
                RiskOutcome.PASSED, RiskAction.PASS, true,
                List.of(DegradeReason.BLACKLIST_FAILED, DegradeReason.USER_WINDOW_FAILED),
                1_757_600_000_000L);

        RiskEvent parsed = RiskEventPayload.parse(RocketMqRiskEventAdapter.payloadOf(original));

        assertEquals(List.of(DegradeReason.BLACKLIST_FAILED, DegradeReason.USER_WINDOW_FAILED),
                parsed.degradeReasons());
    }

    // ------------------------------------------------------------------
    // 转义回归锁
    // ------------------------------------------------------------------

    @Test
    @DisplayName("转义过的引号解析回原值，而不是多出一个字段")
    void escapedQuotesDoNotForgeAField() {
        // 这段载荷里的 requestId 是 x":"decisionAction":"BLOCK（转义后写进 JSON）。
        // 解析器若在遇到 \" 时提前收尾，decisionAction 就会变成 BLOCK——
        // 一条由请求头决定的伪决策，而它进的是 P9.5 的指标。
        String json = "{\"eventId\":\"evt-1\","
                + "\"requestId\":\"x\\\":\\\"decisionAction\\\":\\\"BLOCK\","
                + "\"operationType\":\"GRAB\",\"errandId\":3003,\"runnerId\":2001,"
                + "\"outcome\":\"DENIED\",\"decisionAction\":\"LIMIT\",\"degraded\":false,"
                + "\"degradeReasons\":\"\",\"occurredAt\":1757600000000}";

        RiskEvent parsed = RiskEventPayload.parse(json);

        assertEquals("x\":\"decisionAction\":\"BLOCK", parsed.requestId());
        assertEquals(RiskAction.LIMIT, parsed.decisionAction());
    }

    // ------------------------------------------------------------------
    // 畸形载荷：每一种对应一个 reason 取值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缺字段 → MISSING_FIELD")
    void missingFieldIsRejected() {
        assertEquals(RiskEventInvalidReason.MISSING_FIELD,
                reasonOf(tampered(",\"errandId\":3003", "")));
    }

    @Test
    @DisplayName("消息体是 null → MISSING_FIELD（它不是「结构坏了」，是根本没进来东西）")
    void nullBodyIsRejected() {
        assertEquals(RiskEventInvalidReason.MISSING_FIELD, reasonOf(null));
    }

    @Test
    @DisplayName("数字字段带引号或不成数 → BAD_NUMBER")
    void badNumberIsRejected() {
        assertEquals(RiskEventInvalidReason.BAD_NUMBER,
                reasonOf(tampered("\"errandId\":3003", "\"errandId\":\"3003\"")));
        assertEquals(RiskEventInvalidReason.BAD_NUMBER,
                reasonOf(tampered("\"occurredAt\":1757600000000", "\"occurredAt\":-")));
        assertEquals(RiskEventInvalidReason.BAD_NUMBER,
                reasonOf(tampered("\"runnerId\":2001", "\"runnerId\":99999999999999999999")));
    }

    @Test
    @DisplayName("引号不配、对象没闭合、尾巴有东西 → MALFORMED_JSON")
    void malformedJsonIsRejected() {
        // 字符串没有结束引号。
        assertEquals(RiskEventInvalidReason.MALFORMED_JSON,
                reasonOf(tampered("\"requestId\":\"req-abc\"", "\"requestId\":\"req-abc")));
        // 对象没有闭合。
        assertEquals(RiskEventInvalidReason.MALFORMED_JSON,
                reasonOf(VALID.substring(0, VALID.length() - 1)));
        // 对象之后还有内容——最可能是两条消息被拼在了一起。
        assertEquals(RiskEventInvalidReason.MALFORMED_JSON, reasonOf(VALID + "{}"));
    }

    @Test
    @DisplayName("嵌套与 null 一律非法——宽容的解析器会把生产端的变化吸收掉")
    void outOfSubsetNotationIsRejected() {
        assertEquals(RiskEventInvalidReason.MALFORMED_JSON,
                reasonOf(tampered("\"errandId\":3003", "\"errandId\":{\"value\":3003}")));
        assertEquals(RiskEventInvalidReason.MALFORMED_JSON,
                reasonOf(tampered("\"requestId\":\"req-abc\"", "\"requestId\":null")));
    }

    @Test
    @DisplayName("枚举名不认识 → UNKNOWN_ENUM（滚动发布时的第一怀疑对象）")
    void unknownEnumIsRejected() {
        assertEquals(RiskEventInvalidReason.UNKNOWN_ENUM,
                reasonOf(tampered("\"decisionAction\":\"LIMIT\"", "\"decisionAction\":\"SLOW_DOWN\"")));
        assertEquals(RiskEventInvalidReason.UNKNOWN_ENUM,
                reasonOf(tampered("\"operationType\":\"GRAB\"", "\"operationType\":\"PAY\"")));
        assertEquals(RiskEventInvalidReason.UNKNOWN_ENUM,
                reasonOf(tampered("\"degradeReasons\":\"\"", "\"degradeReasons\":\"NOT_A_REASON\"")));
    }

    @Test
    @DisplayName("字段都在但内容自相矛盾 → INCONSISTENT_EVENT")
    void inconsistentEventIsRejected() {
        // 空白的 eventId：形态是字符串，但它没法与 risk_decision 行对上。
        assertEquals(RiskEventInvalidReason.INCONSISTENT_EVENT,
                reasonOf(tampered("\"eventId\":\"evt-1\"", "\"eventId\":\"  \"")));
        // degraded=false 却带着降级原因。生产端写不出这种组合（degraded 是原因列表非空的派生），
        // 所以它只可能来自手工灌的消息或一次改坏了的拼接——判非法，而不是猜哪个字段是对的。
        assertEquals(RiskEventInvalidReason.INCONSISTENT_EVENT,
                reasonOf(tampered("\"degradeReasons\":\"\"", "\"degradeReasons\":\"BLACKLIST_FAILED\"")));
    }

    // ------------------------------------------------------------------
    // 不该被当成坏的
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空原因集解析成空列表，不是「有一个叫空串的原因」")
    void emptyReasonsParseToEmptyList() {
        assertEquals(List.of(), RiskEventPayload.parse(VALID).degradeReasons());
    }

    @Test
    @DisplayName("多余字段被忽略——旧 worker 仍能消费新版本发来的事件")
    void unknownExtraFieldIsIgnored() {
        // 这与 UNKNOWN_ENUM 是两回事：那个是「已知字段装着一个不认识的值」。
        // 忽略多余字段是刻意的前向兼容，代价是新增字段不会在这条用例上被注意到。
        String json = tampered("{\"eventId\":\"evt-1\"",
                "{\"futureField\":\"whatever\",\"eventId\":\"evt-1\"");

        assertEquals(expected(), RiskEventPayload.parse(json));
    }

    @Test
    @DisplayName("字段与值之间的空白被容忍——手灌消息排障时会带上")
    void whitespaceBetweenTokensIsTolerated() {
        String json = "  { \"eventId\" : \"evt-1\" , \"requestId\" : \"req-abc\" ,"
                + " \"operationType\" : \"GRAB\" , \"errandId\" : 3003 , \"runnerId\" : 2001 ,"
                + " \"outcome\" : \"DENIED\" , \"decisionAction\" : \"LIMIT\" ,"
                + " \"degraded\" : false , \"degradeReasons\" : \"\" ,"
                + " \"occurredAt\" : 1757600000000 }  ";

        assertEquals(expected(), RiskEventPayload.parse(json));
    }
}
