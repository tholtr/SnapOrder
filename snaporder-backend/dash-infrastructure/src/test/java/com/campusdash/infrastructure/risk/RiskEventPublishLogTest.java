package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskEventGateType;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.model.RiskOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产侧两行的**逐字**契约（P9.4 §4.3）。
 *
 * <p>本类的作用是把协议锁死——改格式必须同时改这里，改不动就说明那是破坏性变更。
 * §8.3 的 {@code analyze_risk_events.py} 按行首标记定位、按字段名取值、按 {@code result}
 * 分组，所以下面每一条断言的都是**整行文本**，而不是「A 等于 B」那种同义反复。
 *
 * <p>行本身是给离线脚本读的，字段名和顺序等于协议。这两行也是**最容易漂移的一类**：
 * 它们的产生点在一次网络往返的两侧（投递前与回调里），而两边都不报错、只是少一个字段
 * 或少一条行的时候，没有人会发现。
 */
@DisplayName("风控事件生产侧日志行")
class RiskEventPublishLogTest {

    private static final long OCCURRED_AT = 1_757_600_000_000L;

    private static RiskEvent event() {
        // degraded=false，所以 degradeReasons 必须为空——RiskEvent 的紧凑构造器会挡
        // 不自洽的组合。这里不去构造降级事件：本条日志行**没有** degradeReasons 字段，
        // 降级只体现为 degraded=true 与 gateType=DEGRADED 两处。
        return new RiskEvent("evt-1", "req-abc", RiskOperationType.GRAB, 3003L, 2001L,
                RiskOutcome.DENIED, RiskAction.LIMIT, false, List.of(), OCCURRED_AT);
    }

    @Test
    @DisplayName("投递行逐字匹配设计 §4.3 的格式")
    void publishLineMatchesSpecExactly() {
        String line = RiskEventPublishLog.format(event(), RiskEventGateType.LIMIT,
                RiskEventPublishResult.SENT);

        assertEquals("risk_event_publish eventId=evt-1 requestId=req-abc"
                + " operationType=GRAB errandId=3003 runnerId=2001"
                + " decisionAction=LIMIT degraded=false gateType=LIMIT result=SENT", line);
    }

    @Test
    @DisplayName("四个 result 各产生一条行，取值就是离线脚本要匹配的那四个字符串")
    void everyResultRendersVerbatim() {
        // 逐字断言而不是「包含枚举名」：这四个字符串是 §8.3 的脚本直接匹配的字面量，
        // 值对不上不会有任何报错，只会让 risk_event_suppressed_total 恒为 0。
        assertEquals("risk_event_publish eventId=evt-1 requestId=req-abc"
                        + " operationType=GRAB errandId=3003 runnerId=2001"
                        + " decisionAction=LIMIT degraded=false gateType=LIMIT result=SENT",
                RiskEventPublishLog.format(event(), RiskEventGateType.LIMIT,
                        RiskEventPublishResult.SENT));

        String suppressed = RiskEventPublishLog.format(event(), RiskEventGateType.LIMIT,
                RiskEventPublishResult.SUPPRESSED);
        assertTrue(suppressed.endsWith(" result=SUPPRESSED"), suppressed);

        String gateFailed = RiskEventPublishLog.format(event(), RiskEventGateType.REVIEW,
                RiskEventPublishResult.SENT_GATE_FAILED);
        assertTrue(gateFailed.endsWith(" gateType=REVIEW result=SENT_GATE_FAILED"), gateFailed);

        String dropped = RiskEventPublishLog.format(event(), RiskEventGateType.BLOCK,
                RiskEventPublishResult.DROPPED);
        assertTrue(dropped.endsWith(" gateType=BLOCK result=DROPPED"), dropped);
    }

    @Test
    @DisplayName("四个取值一个不多一个不少，且声明顺序固定")
    void resultValuesAreFrozen() {
        // 顺序本身在单行日志上看不出来，它管的是离线脚本按 result 分组时的排列，
        // 以及「谁排在谁前面」不要被顺手调换。长度断言是防「顺手加第五类」——
        // 比如给发送失败补一个 FAILED，而 §4.3 明确把那条做成了独立的一行。
        assertEquals(List.of(
                        RiskEventPublishResult.SENT,
                        RiskEventPublishResult.SUPPRESSED,
                        RiskEventPublishResult.SENT_GATE_FAILED,
                        RiskEventPublishResult.DROPPED),
                List.of(RiskEventPublishResult.values()));
    }

    @Test
    @DisplayName("投递行的九个字段都在，且顺序固定")
    void publishFieldOrderIsStable() {
        // 顺序不是审美问题：分析脚本若按位置切分，换序会静默产出错指标。
        String[] expected = {"eventId=", "requestId=", "operationType=", "errandId=",
                "runnerId=", "decisionAction=", "degraded=", "gateType=", "result="};

        String line = RiskEventPublishLog.format(event(), RiskEventGateType.LIMIT,
                RiskEventPublishResult.SENT);

        int cursor = -1;
        for (String field : expected) {
            int at = line.indexOf(field);
            assertTrue(at > cursor, "字段 " + field + " 缺失或顺序不对：" + line);
            cursor = at;
        }
    }

    @Test
    @DisplayName("投递行只有一行——客户端塞不进换行")
    void publishLineIsAlwaysSingleLine() {
        String line = RiskEventPublishLog.format(event(), RiskEventGateType.LIMIT,
                RiskEventPublishResult.SENT);

        assertFalse(line.contains("\n"));
    }

    @Test
    @DisplayName("requestId 带空格时被中性化——客户端伪造不出一个 result 字段")
    void requestIdWithSpacesIsNeutralized() {
        // 这是 D13 在**这条行**上的调用路径锁：requestId 是本行里唯一来自客户端的字段，
        // X-Request-Id: x result=DROPPED 若原样渲染，离线脚本按空格切分取 result 时
        // 读到的是客户端写进去的值——不是排版问题，是指标可被伪造。
        RiskEvent forged = new RiskEvent("evt-1", "x result=DROPPED", RiskOperationType.GRAB,
                3003L, 2001L, RiskOutcome.DENIED, RiskAction.LIMIT, false, List.of(), OCCURRED_AT);

        String line = RiskEventPublishLog.format(forged, RiskEventGateType.LIMIT,
                RiskEventPublishResult.SENT);

        assertTrue(line.contains("requestId=x_result=DROPPED"), line);
        // 逐 token 断言：伪字段不能是一个独立的 token。用 contains 会漏掉
        // “……requestId=x result=DROPPED ……”这种**只多一个空格**的形态。
        assertFalse(List.of(line.split(" ")).contains("result=DROPPED"), line);
        assertEquals(1, List.of(line.split(" ")).stream()
                .filter(t -> t.startsWith("result=")).count(), line);
    }

    @Test
    @DisplayName("失败行逐字匹配设计 §4.3 的格式")
    void failedLineMatchesSpecExactly() {
        String line = RiskEventPublishLog.formatFailed("evt-1", "errand-risk-event",
                new IllegalStateException("broker unreachable"));

        assertEquals("risk_event_publish_failed eventId=evt-1"
                + " topic=errand-risk-event error=IllegalStateException", line);
    }

    @Test
    @DisplayName("失败行只记异常**类型**，消息一个字都不进日志")
    void failedLineRecordsTheTypeOnly() {
        // 异常消息里可能嵌着服务端返回的原文——那是外部输入。入参收窄成 Throwable
        // 就是为了让「顺手把 getMessage() 写进日志」不再是一种可能的写法，
        // 而这条断言是那个收窄的可见证据。
        Throwable error = new IllegalStateException("x action=BLOCK");

        String line = RiskEventPublishLog.formatFailed("evt-1", "errand-risk-event", error);

        assertTrue(line.endsWith("error=IllegalStateException"), line);
        assertFalse(line.contains("x action=BLOCK"), line);
        assertFalse(line.contains("BLOCK"), line);
    }

    @Test
    @DisplayName("异常类型取不到时渲染成 -，而不是留一个空的 error=")
    void blankErrorTypeRendersAsDash() {
        // 匿名类的 getSimpleName() 是空串。不处理的话会渲染出 error=（后面直接换行），
        // 那看起来像字段被截断了，而不是「没记下类型」。
        Throwable anonymous = new IllegalStateException() {
        };

        String line = RiskEventPublishLog.formatFailed("evt-1", "errand-risk-event", anonymous);

        assertTrue(line.endsWith(" error=-"), line);
    }

    @Test
    @DisplayName("失败行的三个字段都在，且顺序固定")
    void failedFieldOrderIsStable() {
        String[] expected = {"eventId=", "topic=", "error="};

        String line = RiskEventPublishLog.formatFailed("evt-1", "errand-risk-event",
                new IllegalStateException("x"));

        int cursor = -1;
        for (String field : expected) {
            int at = line.indexOf(field);
            assertTrue(at > cursor, "字段 " + field + " 缺失或顺序不对：" + line);
            cursor = at;
        }
    }

    @Test
    @DisplayName("失败行不重复 requestId——它只在投递行上出现一次")
    void failedLineCarriesNoRequestId() {
        // §4.3 的格式里没有它。要回查 requestId 就拿 eventId 去匹配投递行——
        // 两行都有 eventId 正是为了这个（D3 那条「两条日志对上的唯一凭据」）。
        // 谁「顺手」把 requestId 加进失败行，就必须同时想清楚它要不要过净化。
        String line = RiskEventPublishLog.formatFailed("evt-1", "errand-risk-event",
                new IllegalStateException("x"));

        assertFalse(line.contains("requestId="), line);
    }
}
