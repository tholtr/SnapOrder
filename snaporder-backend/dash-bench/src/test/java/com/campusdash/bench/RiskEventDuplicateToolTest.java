package com.campusdash.bench;

import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskOutcome;
import com.campusdash.domain.risk.ports.RiskEventPort;
import com.campusdash.infrastructure.risk.RocketMqRiskEventAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@link RiskEventDuplicateTool} 的**纯逻辑**部分：参数解析与载荷拼装（P9.5 设计 §8）。
 * 网络部分不测——与 {@code SpikeLoadClientTest} / {@code BlacklistKeyToolTest} 的既有尺度一致：
 * 那一段要真 broker，测它就得把整个中间件拖进单测，而它出问题时的表现是「连不上」，
 * 不是「算错了」——后者才是这里要守的。
 *
 * <p>这个工具最危险的失效方式不是崩，而是**送出一条和报告里说的不一样的事件**：
 * 消息确实发出去了、消费行也真的出现了、判据全绿，只有被验的那件事不对。
 * 所以下面盯得最紧的是三处：参数写错必须报错、载荷必须来自生产端的序列化器、
 * 两次发送用的必须是同一份 body。
 */
@DisplayName("风险事件重复投递工具")
class RiskEventDuplicateToolTest {

    @Test
    @DisplayName("默认样例自洽：GRAB / LIMIT / DENIED / 不降级，端点与 topic 取默认值")
    void defaultSampleIsSelfConsistent() {
        RiskEventDuplicateTool.Sample sample = RiskEventDuplicateTool.parse(new String[0]);

        assertEquals("GRAB", sample.event().operationType().name());
        assertEquals(RiskAction.LIMIT, sample.event().decisionAction());
        assertEquals(RiskOutcome.DENIED, sample.event().outcome());
        assertFalse(sample.event().degraded(), "工具只造「不降级」这一种形态，见 parse 的注释");
        assertTrue(sample.event().degradeReasons().isEmpty());
        assertEquals(RiskEventDuplicateTool.DEFAULT_ENDPOINT, sample.endpoint());
        assertEquals(RiskEventPort.TOPIC_RISK_EVENT, sample.topic());
        assertFalse(sample.event().eventId().isBlank());
        assertFalse(sample.event().requestId().isBlank());
    }

    @Test
    @DisplayName("outcome 由 action 推出，与 RiskPreCheckResult.toEvent 同一条规则")
    void outcomeFollowsAction() {
        for (RiskAction action : RiskAction.values()) {
            RiskEventDuplicateTool.Sample sample =
                    RiskEventDuplicateTool.parse(new String[]{"--action", action.name()});

            RiskOutcome expected = action == RiskAction.PASS ? RiskOutcome.PASSED : RiskOutcome.DENIED;
            assertEquals(expected, sample.event().outcome(), "action=" + action);
        }
    }

    @Test
    @DisplayName("两次取载荷逐字节相同——这是「重复投递」这件事的全部前提")
    void payloadIsStableAcrossCalls() {
        // 若有人把 occurredAt 改成在发送时取当前时间（或把 payload 改成每次现拼），
        // 两次送出的就是**同一个 eventId、不同 body** 的两条消息：消费行照样是两行、
        // 离线按 requestId + operationType 去重后 duplicate 照样是 1——
        // 所有判据全绿，而「逐字节相同」这个前提已经不成立了。这条盯的就是它。
        RiskEventDuplicateTool.Sample sample = RiskEventDuplicateTool.parse(new String[0]);

        assertEquals(sample.payload(), sample.payload());
    }

    @Test
    @DisplayName("载荷来自生产端的序列化器，不是工具手拼的一份")
    void payloadComesFromTheProductionSerializer() {
        RiskEventDuplicateTool.Sample sample = RiskEventDuplicateTool.parse(new String[]{
                "--event-id", "dup-evt-1", "--request-id", "dup-req-1",
                "--errand-id", "42", "--runner-id", "7", "--action", "BLOCK"});

        assertEquals(RocketMqRiskEventAdapter.payloadOf(sample.event()), sample.payload());
    }

    @Test
    @DisplayName("requestId 里塞引号也拼不出第二个字段（生产端的转义在这里照样生效）")
    void requestIdCannotInjectExtraFields() {
        // requestId 是载荷里唯一的外部输入。工具能灌任意字符串，所以这条边界要从工具这一侧
        // 也确认一遍：不转义的话，灌一个 `x","decisionAction":"BLOCK` 就能往事件里多加一个字段，
        // 而消费端与离线脚本都按名字取值——一次伪造的字段会直接进指标（D13）。
        String injection = "x\",\"decisionAction\":\"BLOCK";
        RiskEventDuplicateTool.Sample sample =
                RiskEventDuplicateTool.parse(new String[]{"--request-id", injection, "--action", "LIMIT"});

        String payload = sample.payload();
        assertFalse(payload.contains(injection), "载荷里出现了未转义的原始 requestId：" + payload);
        assertTrue(payload.contains("x\\\",\\\"decisionAction\\\":\\\"BLOCK"),
                "引号没有被转义成反斜杠加引号：" + payload);
        assertEquals(1, countOf(payload, ",\"decisionAction\":"),
                "decisionAction 这个字段只该出现一次（多出来的一次就是注入成功）：" + payload);
        assertEquals(RiskAction.LIMIT, sample.event().decisionAction(),
                "注入的 BLOCK 不能真的改掉这次决策的动作");
    }

    @Test
    @DisplayName("不认识的参数报错，绝不会被静默忽略去用默认值")
    void unknownFlagIsRejected() {
        // 敲错一个字母（--actoin）若被忽略，工具会用默认的 LIMIT 造一条事件送出去，
        // 而报告里写着「灌了一条 BLOCK」：消息真发出去了、消费行真出现了、判据全绿。
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RiskEventDuplicateTool.parse(new String[]{"--actoin", "BLOCK"}));

        assertTrue(ex.getMessage().contains("--actoin"), ex.getMessage());
        assertTrue(ex.getMessage().contains("用法"), "报错时要给出用法：" + ex.getMessage());
    }

    @Test
    @DisplayName("--名字 值与 --名字=值 两种写法等价")
    void bothValueFormsAreAccepted() {
        // 两个 id 都显式给：省略时它们的默认值带毫秒时间戳，跨边界就会不等——
        // 那种红与参数写法毫无关系，只会让人怀疑这条用例本身。
        RiskEventDuplicateTool.Sample spaced = RiskEventDuplicateTool.parse(
                new String[]{"--event-id", "e1", "--request-id", "r1", "--action", "REVIEW", "--errand-id", "42"});
        RiskEventDuplicateTool.Sample joined = RiskEventDuplicateTool.parse(
                new String[]{"--event-id=e1", "--request-id=r1", "--action=REVIEW", "--errand-id=42"});

        // 逐个字段比，不整个 record 比：occurredAt 按设计取「当时」的毫秒，两次 parse 跨一个
        // 毫秒边界就会不等——那样的红与参数写法毫无关系。（也正因如此工具不提供 --occurred-at：
        // 手设一个过去的时间会让 lagMs 变成一个假数字。）
        assertEquals(spaced.event().eventId(), joined.event().eventId());
        assertEquals(spaced.event().requestId(), joined.event().requestId());
        assertEquals(spaced.event().errandId(), joined.event().errandId());
        assertEquals(spaced.event().runnerId(), joined.event().runnerId());
        assertEquals(spaced.event().decisionAction(), joined.event().decisionAction());
        assertEquals(spaced.endpoint(), joined.endpoint());
    }

    @Test
    @DisplayName("值看起来像另一个参数时报错，而不是把它当成值吃掉")
    void valueLookingLikeAFlagIsRejected() {
        // `--action --endpoint 10.0.0.5:8081`（少给了 action 的值）若被吃掉，
        // 报出来的是「动作不合法：--endpoint」——指向一个没错的地方。
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RiskEventDuplicateTool.parse(new String[]{"--action", "--endpoint", "x:1"}));

        assertTrue(ex.getMessage().contains("--action"), ex.getMessage());
    }

    @Test
    @DisplayName("参数后面缺值时给出用法，而不是数组越界")
    void missingValueIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RiskEventDuplicateTool.parse(new String[]{"--event-id"}));
    }

    @Test
    @DisplayName("同一个参数给两次报错，而不是悄悄用后一个")
    void duplicateFlagIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RiskEventDuplicateTool.parse(new String[]{
                        "--action", "LIMIT", "--action", "BLOCK"}));

        assertTrue(ex.getMessage().contains("--action"), ex.getMessage());
    }

    @Test
    @DisplayName("位置参数报错——本工具的参数全部可省，混进来只会吃掉后面的值")
    void positionalArgumentIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RiskEventDuplicateTool.parse(new String[]{"BLOCK"}));
    }

    @Test
    @DisplayName("枚举写错时列出可选值，且可选值由枚举现算不会过期")
    void enumErrorsListTheValidValues() {
        IllegalArgumentException action = assertThrows(IllegalArgumentException.class,
                () -> RiskEventDuplicateTool.parse(new String[]{"--action", "allow"}));
        IllegalArgumentException type = assertThrows(IllegalArgumentException.class,
                () -> RiskEventDuplicateTool.parse(new String[]{"--operation-type", "PAY"}));

        for (RiskAction value : RiskAction.values()) {
            assertTrue(action.getMessage().contains(value.name()), action.getMessage());
        }
        assertTrue(type.getMessage().contains("GRAB"), type.getMessage());
    }

    @Test
    @DisplayName("动作名大小写不敏感，避免手敲时踩坑")
    void actionIsCaseInsensitive() {
        assertEquals(RiskAction.BLOCK,
                RiskEventDuplicateTool.parse(new String[]{"--action", "block"}).event().decisionAction());
    }

    @Test
    @DisplayName("id 不是整数时报错")
    void nonNumericIdIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RiskEventDuplicateTool.parse(new String[]{"--errand-id", "abc"}));

        assertTrue(ex.getMessage().contains("--errand-id"), ex.getMessage());
    }

    // ── 报告 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("报告逐字给出载荷与两个 msgId——照着它能复现这一次灌了什么")
    void reportQuotesThePayloadVerbatim() {
        RiskEventDuplicateTool.Sample sample = RiskEventDuplicateTool.parse(
                new String[]{"--event-id", "dup-evt-9", "--action", "BLOCK"});

        String report = RiskEventDuplicateTool.buildReport(sample, List.of("msg-1", "msg-2"));

        assertTrue(report.contains(sample.payload()), "报告里没有逐字的载荷：" + report);
        assertTrue(report.contains("msg-1"), report);
        assertTrue(report.contains("msg-2"), report);
        assertTrue(report.contains("dup-evt-9"), "报告里要能拿到 eventId 去日志里 grep：" + report);
        assertTrue(report.contains("analyze_risk_events.py"),
                "第二条判据靠离线脚本，报告要指出是哪一个：" + report);
    }

    @Test
    @DisplayName("报告里出现的是实际连的端点，不写死默认端口")
    void reportShowsTheEndpointItUsed() {
        // 与 BlacklistKeyTool 同一条规矩：报告里的东西必须说得出它来自哪个库。
        // 写死默认端点的话，一份照着复制的命令在生产上会灌进本机 broker——
        // 不报错，只是消息没了，而报告里那两条判据仍然会是绿的。
        RiskEventDuplicateTool.Sample sample = RiskEventDuplicateTool.parse(
                new String[]{"--endpoint", "10.0.0.5:8081"});

        String report = RiskEventDuplicateTool.buildReport(sample, List.of("msg-1", "msg-2"));

        assertTrue(report.contains("10.0.0.5:8081"), report);
        assertFalse(report.contains(RiskEventDuplicateTool.DEFAULT_ENDPOINT),
                "不该同时出现默认端点，否则分不清灌到了哪个 broker：" + report);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
