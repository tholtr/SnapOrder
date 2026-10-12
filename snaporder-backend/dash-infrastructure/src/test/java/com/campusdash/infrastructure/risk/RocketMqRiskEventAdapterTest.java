package com.campusdash.infrastructure.risk;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskDecision;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskEventGateType;
import com.campusdash.domain.risk.model.RiskMode;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.model.RiskOutcome;
import com.campusdash.domain.risk.ports.RiskEventPort;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.RecallReceipt;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 适配器的五条性质（P9.4 设计 §8.1）：聚合关闭时每条都发 / 同用户第二条被抑制且
 * **不碰 provider** / 闸门抛异常时照发 / 异步失败时记失败行 / 队列满时丢弃且不入队。
 *
 * <h2>这个类是异步的，所以「等待」本身是测试设计的一部分</h2>
 *
 * <p>所有断言都发生在**另一个线程**上（适配器的执行器），所以「断言时那件事已经发生了吗」
 * 必须由某个同步点回答，不能靠 {@code sleep} 猜。这里用两种：
 *
 * <ul>
 *   <li>假 {@code Producer} 上的 {@link CountDownLatch}——「已经发出去 N 条」。
 *       {@code CountDownLatch} 自带 happens-before，等到它归零时，执行器线程上
 *       在此之前写下的东西（包括投递行）都已经可见。</li>
 *   <li>需要「换个用户当哨兵」的那条用例见
 *       {@link #secondEventForSameUserIsSuppressed()}：它靠**随后一条一定会发出去的
 *       事件**来证明前一条已经处理完。执行器是单线程 FIFO 的，所以这是确定性推理，
 *       不是时间推理。</li>
 * </ul>
 *
 * <h2>为什么这里可以捕获日志，而别处不行</h2>
 *
 * <p>{@code RiskEventPublishLog} 的注释里写着「本仓库没有日志捕获依赖，把格式做成纯函数
 * 是唯一不引依赖的测法」。那句话在**引依赖**这个意义上是对的，但它给了一个错误的暗示：
 * 以为这里捕获不了日志。实际上 <b>logback-classic 本来就在类路径上</b>
 * （{@code spring-boot-starter-logging} 传递进来的），{@link ListAppender} 不新增任何依赖。
 *
 * <p>所以本类用它，理由不是「方便」：{@code result=SENT} 与 {@code result=SENT_GATE_FAILED}
 * **都会真的发出去**，两条路径在 provider 上留下的痕迹**完全一样**，靠假 Producer
 * 区分不了。而它们的区别正是 D8 的全部内容——不测这一条，「闸门坏了所以每条都发」
 * 与「闸门正常放行」就只靠读代码来保证。
 *
 * <p>同理 {@code risk_event_publish_failed} 只在 {@code CompletableFuture} 的回调里产生，
 * 没有任何其他可观察的副作用。
 *
 * <p>代价是要自己处理线程可见性，见 {@link #capturePublishLines()}。
 */
@DisplayName("风险事件 MQ 适配器")
class RocketMqRiskEventAdapterTest {

    private static final long OCCURRED_AT = 1_757_600_000_000L;
    private static final long USER = 2001L;
    private static final long ERRAND = 3003L;

    /**
     * **真的** `ClientServiceProvider`，只有 {@code Producer} 是假的。
     *
     * <p>这样断言的就是生产真正会构造出来的那个 {@link Message} 对象——topic、keys、
     * body 的字节都是 RocketMQ 自己拼的，而不是我照着记忆假造一份。假造一份的话，
     * 「消息体是不是 UTF-8」「keys 是不是 Collection」这类问题会由我的假实现替我回答，
     * 而它答错了没人知道。
     */
    private static final ClientServiceProvider PROVIDER = loadProvider();

    private final FakeProducer producer = new FakeProducer();
    private final FakeGate gate = new FakeGate();
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final List<RocketMqRiskEventAdapter> adapters = new ArrayList<>();

    /**
     * {@code ListAppender.list} 默认是普通 {@code ArrayList}——它在执行器线程上被写、
     * 在测试线程上被读，两个线程之间没有 happens-before 就只能靠运气。
     *
     * <p>换成 {@link CopyOnWriteArrayList} 而不是「等 latch 之后再读」：等 latch 那条路
     * 只对「发送之前写下的一切」有效，而失败行的产生点**在** latch 之后（回调里），
     * 恰恰等不到。用并发容器之后，「读到的行」与「已经发生的调用」顺序一致，
     * 与断言用哪种同步点无关——一个不许出现的失败模式，比一条需要每次都想一遍的规则好。
     */
    @BeforeEach
    void capturePublishLines() {
        assertTrue(publishLogger().isInfoEnabled(),
                "前提不成立：RiskEventPublishLog 的 INFO 被关掉了（多半是加了 logback 配置文件），"
                        + "这个类会安静地捕获到空列表，然后每条断言都以「行数不对」的形式失败。");
        lines.list = new CopyOnWriteArrayList<>();
        lines.start();
        publishLogger().addAppender(lines);
    }

    @AfterEach
    void detachAppender() {
        publishLogger().detachAppender(lines);
        lines.stop();
        adapters.forEach(RocketMqRiskEventAdapter::shutdown);
    }

    @Test
    @DisplayName("聚合关闭时每条都发，且一次都不问闸门")
    void aggregationOffSendsEveryEvent() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(false);
        producer.expectSends(3);

        adapter.publish(limitEvent(USER));
        adapter.publish(limitEvent(USER));
        adapter.publish(limitEvent(USER));

        assertTrue(producer.awaitSends(), "三条事件都该发出去");
        assertEquals(3, producer.sent.size());
        assertTrue(gate.calls.isEmpty(),
                "聚合关闭时闸门不该被问到——它只影响「有几条上了 MQ」，"
                        + "连问一次都白白多一次 Redis 往返，而这是在请求链路的执行器线程上："
                        + gate.calls);
        assertAllResults("SENT", 3);
    }

    @Test
    @DisplayName("聚合开启时同用户的第二条被抑制，且从未调用 sendAsync")
    void secondEventForSameUserIsSuppressed() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(true);
        gate.script(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE);
        producer.expectSends(2);

        adapter.publish(limitEvent(USER));
        adapter.publish(limitEvent(USER));
        // 第三条换个用户，它一定会发出去。等到「已发送 2 条」就等于第二条**已经处理完**：
        // 执行器是单线程 FIFO 的，第三条能发出去说明它前面那条早走完了。
        // 没有这个哨兵，「第二条没发」就只能靠 sleep 之后看一眼来判——那是在赌。
        adapter.publish(limitEvent(9999L));

        assertTrue(producer.awaitSends());

        List<String> published = publishLines();
        assertEquals(3, published.size(), published.toString());
        assertTrue(published.get(0).endsWith(" result=SENT"), published.get(0));
        assertTrue(published.get(1).endsWith(" result=SUPPRESSED"), published.get(1));
        assertTrue(published.get(2).endsWith(" result=SENT"), published.get(2));
        assertEquals(2, producer.sent.size(), "被抑制的那条不该碰 provider：抑制的意义就是省掉这条消息");

        // 动作类闸门的主体是**用户 id**。取错主体的后果不是报错，而是算出一个谁都查不到的
        // 键——聚合于是恒不生效，而日志上看起来一切正常。所以逐个断言主体。
        assertEquals(List.of("LIMIT/2001", "LIMIT/2001", "LIMIT/9999"), gate.calls);
    }

    @Test
    @DisplayName("闸门抛异常时照常发送，但记 SENT_GATE_FAILED 而不是 SENT（D8）")
    void gateFailureStillSends() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(true);
        gate.script(new IllegalStateException("Redis 连不上"));
        producer.expectSends(1);

        adapter.publish(limitEvent(USER));

        assertTrue(producer.awaitSends(),
                "D8：聚合判断失败**不是**「要不要记录」的判断——Redis 出问题往往伴随流量异常，"
                        + "那正是最需要证据的时刻");
        assertEquals(1, producer.sent.size());
        // 这一条是本类唯一必须捕获日志的地方：上面那次发送与「闸门正常放行」在 provider 上
        // 留下的痕迹一模一样。记成 SENT 的话，「闸门坏了所以每条都发」与「闸门正常放行」
        // 在离线算抑制率时分不出来，抑制率会凭空变好看。
        assertAllResults("SENT_GATE_FAILED", 1);
    }

    @Test
    @DisplayName("降级事件用实例标识抢闸门——主体取错不会报错，只会恒不生效")
    void degradedGateIsKeyedByInstance() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(true);
        gate.script(Boolean.TRUE);
        producer.expectSends(1);

        adapter.publish(passButDegradedEvent(USER));

        assertTrue(producer.awaitSends());
        assertEquals(List.of("DEGRADED/test-instance"), gate.calls,
                "降级类闸门的主体是实例，不是用户：D5 的窗口语义是「每实例 10 秒一条」，"
                        + "传成用户 id 会算出 risk:event:gate:degraded:{2001} 这种谁都查不到的键");
        assertTrue(publishLines().get(0).contains(" decisionAction=PASS degraded=true gateType=DEGRADED"),
                publishLines().get(0));
    }

    @Test
    @DisplayName("动作与降级同时成立时按动作定档，降级键不得抢走 LIMIT 事件（D7）")
    void theActionWinsOverDegraded() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(true);
        gate.script(Boolean.TRUE);
        producer.expectSends(1);

        adapter.publish(new RiskEvent("evt-1", "req-1", RiskOperationType.GRAB, ERRAND, USER,
                RiskOutcome.DENIED, RiskAction.LIMIT, true,
                List.of(DegradeReason.BLACKLIST_FAILED), OCCURRED_AT));

        assertTrue(producer.awaitSends());
        assertEquals(List.of("LIMIT/2001"), gate.calls,
                "两个闸门都成立时必须是动作那个赢：若降级键赢，被抑制的恰好是攻击信号本身");
    }

    @Test
    @DisplayName("异步发送失败时记一行 risk_event_publish_failed，投递行不受影响")
    void asyncFailureEmitsFailedLine() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(false);
        producer.failAsync = true;
        producer.expectSends(1);

        adapter.publish(limitEvent(USER));
        assertTrue(producer.awaitSends());

        // 失败行产生在 whenComplete 的回调里，没有任何可以等的闩——所以轮询。
        awaitFailedLines(1);

        List<String> published = publishLines();
        assertEquals(1, published.size());
        assertTrue(published.get(0).endsWith(" result=SENT"),
                "投递行说的是「已交给 sendAsync」，它不知道 broker 收没收——"
                        + "「已投递」是个这个类证明不了的结论：" + published.get(0));
        assertTrue(failedLines().get(0).contains(" error=IllegalStateException"),
                "只记异常类型，不记异常消息——消息里可能嵌着服务端返回的原文，"
                        + "那是外部输入：" + failedLines().get(0));
    }

    @Test
    @DisplayName("sendAsync 同步抛出时也记失败行——不接住它会杀死执行器线程，事件静默消失")
    void synchronousThrowIsRecordedToo() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(false);
        producer.thrownOnAsync = new IllegalArgumentException("topic 非法");
        producer.expectSends(1);

        adapter.publish(limitEvent(USER));

        awaitFailedLines(1);
        assertTrue(failedLines().get(0).contains(" error=IllegalArgumentException"),
                failedLines().get(0));
    }

    @Test
    @DisplayName("执行器队列满时记 DROPPED 并且不入队——丢弃是明确的，不是静默的（D9）")
    void fullQueueDropsInsteadOfBlocking() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(false);
        // 占住唯一那个执行器线程：它在 sendAsync 里卡着不返回。
        producer.blockNextSends();

        // 头一条被工作线程直接拿去当 firstTask——**不经过队列**，这一条与线程调度无关。
        // 接着 QUEUE_CAPACITY 条把队列填满，再往后的每一条都落在 AbortPolicy 上。
        int accepted = 1 + RocketMqRiskEventAdapter.QUEUE_CAPACITY;
        int overflow = 2;
        for (int i = 0; i < accepted + overflow; i++) {
            adapter.publish(limitEvent(USER + i));
        }
        assertTrue(producer.awaitThreadStuck(),
                "工作线程没起来？起来之前「只调了一次 sendAsync」是白说的");

        // 用「第二条被拒」而不是「恰好一条被拒」来判：第一条只证明队列满过一次，
        // 两条才说明**队列一直是满的**，也就是拒绝确实来自容量而不是别的原因。
        assertEquals(overflow, droppedLines().size(),
                "队列满时该有 " + overflow + " 条 DROPPED。注意这条用例**故意不解开阻塞**："
                        + "解开之后那 " + accepted + " 条排队任务会真的发出去、刷出几千行日志，"
                        + "而它们对这里的断言没有任何贡献。代价是本测试 JVM 里留下一个卡在 "
                        + "sendAsync 上的守护线程，它随 JVM 退出消失。现有行：" + messages());
        assertEquals(1, producer.sendAsyncCalls.get(),
                "被丢弃的那些从未入队，也就从未到达 provider——队列里现在应该还压着 "
                        + accepted + " 条没跑。");
        assertEquals(0, producer.sent.size(), "第一条还卡在 sendAsync 里，什么都没记下");
    }

    @Test
    @DisplayName("载荷逐字匹配 §3.3 的十个字段，顺序即声明序")
    void payloadMatchesSpecExactly() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(false);
        producer.expectSends(1);

        adapter.publish(new RiskEvent("evt-1", "req-abc", RiskOperationType.GRAB, ERRAND, USER,
                RiskOutcome.DENIED, RiskAction.LIMIT, false, List.of(), OCCURRED_AT));

        assertTrue(producer.awaitSends());
        Message message = producer.sent.get(0);

        assertEquals(RiskEventPort.TOPIC_RISK_EVENT, message.getTopic());
        // 消息键就是 eventId：控制台上能直接按它查到一条事件，而 eventId 同时出现在
        // risk_decision 行、投递行与消费行上，排障时不必二次换算。
        assertEquals(List.of("evt-1"), List.copyOf(message.getKeys()));
        assertEquals("{"
                + "\"eventId\":\"evt-1\""
                + ",\"requestId\":\"req-abc\""
                + ",\"operationType\":\"GRAB\""
                + ",\"errandId\":3003"
                + ",\"runnerId\":2001"
                + ",\"outcome\":\"DENIED\""
                + ",\"decisionAction\":\"LIMIT\""
                + ",\"degraded\":false"
                + ",\"degradeReasons\":\"\""
                + ",\"occurredAt\":1757600000000"
                + "}", bodyOf(message));
    }

    @Test
    @DisplayName("降级原因按给定顺序用 | 连接，空列表是空串而不是 -")
    void degradeReasonsArePipeJoined() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(false);
        producer.expectSends(1);

        // 刻意给一个**不是**声明序的顺序：这条要钉的是「原样连接」，不是「这里排序」。
        // 排序是产出这个列表的地方的约定，在这里再排一遍只会让将来改约定时漏掉一处。
        adapter.publish(new RiskEvent("evt-1", "req-1", RiskOperationType.GRAB, ERRAND, USER,
                RiskOutcome.PASSED, RiskAction.PASS, true,
                List.of(DegradeReason.CLUSTER_FAILED, DegradeReason.BLACKLIST_FAILED), OCCURRED_AT));

        assertTrue(producer.awaitSends());
        assertTrue(bodyOf(producer.sent.get(0))
                        .contains("\"degradeReasons\":\"CLUSTER_FAILED|BLACKLIST_FAILED\""),
                bodyOf(producer.sent.get(0)));
    }

    @Test
    @DisplayName("requestId 里塞引号造不出第二个字段——它是载荷里唯一由客户端控制的输入")
    void requestIdCannotForgeAField() throws Exception {
        RocketMqRiskEventAdapter adapter = adapter(false);
        producer.expectSends(1);

        adapter.publish(new RiskEvent("evt-1", "x\",\"decisionAction\":\"BLOCK",
                RiskOperationType.GRAB, ERRAND, USER, RiskOutcome.DENIED, RiskAction.LIMIT,
                false, List.of(), OCCURRED_AT));

        assertTrue(producer.awaitSends());
        String payload = bodyOf(producer.sent.get(0));

        assertEquals(1, countOf(payload, "\"decisionAction\":\""),
                "客户端塞进来的那个 decisionAction 必须是**文本的一部分**，不是第二个字段。"
                        + "消费端与离线脚本都按名字取值，一次伪造的字段会直接进指标： " + payload);
        assertTrue(payload.contains("\"requestId\":\"x\\\",\\\"decisionAction\\\":\\\"BLOCK\""),
                "转义后的原文该原样留着——换成丢弃或改写会让 requestId 在两条日志之间对不上：" + payload);
    }

    @Test
    @DisplayName("控制字符转义成 JSON 规范的形式，U+2028/U+2029 不动")
    void controlCharactersAreEscaped() {
        // 直接在拼装函数上断言，不起适配器：这几条是纯字符替换，走一遍线程与 latch
        // 只会让失败信息更难读。
        assertEquals("a\\nb", RocketMqRiskEventAdapter.escapeJson("a\nb"));
        assertEquals("a\\tb", RocketMqRiskEventAdapter.escapeJson("a\tb"));
        assertEquals("a\\\"b", RocketMqRiskEventAdapter.escapeJson("a\"b"));
        assertEquals("a\\\\b", RocketMqRiskEventAdapter.escapeJson("a\\b"));
        // 有短转义的都走短转义：
        assertEquals("a\\bb", RocketMqRiskEventAdapter.escapeJson("a\bb"));
        assertEquals("a\\fb", RocketMqRiskEventAdapter.escapeJson("a\fb"));
        // U+0000 没有短转义，只能走四位的十六进制形式。
        //
        // **这里用 (char) 0 现造，而不是把那个字符直接写进源码里的字符串字面量**：
        // 不可见码点落进源文件时，编辑器、diff 和测试结果里看到的都是「什么都没写」，
        // 旁边那句「这里有个 NUL」的注释于是成了唯一的证据——而它没有任何东西能验证。
        // 换成 (char) <码点> 之后源码里不存在不可见字符，读的人不必相信谁。
        assertEquals("a\\u0000b",
                RocketMqRiskEventAdapter.escapeJson("a" + (char) 0 + "b"),
                "U+0000 没有短转义，只能走四位的十六进制形式");
        // 反面样本：U+2028 / U+2029 在 JSON 里是合法字符，**不该**被转义。
        // 日志行那边要挡它们（Python 的 splitlines 认它们是换行，能凭空造出一整行），
        // 载荷这边不挡——两处的失效方式不同，别把一边的处理顺手抄到另一边。
        // 同样用 (char) 现造，理由与上面那条一样。
        String lineSeparator = String.valueOf((char) 0x2028);
        assertEquals("a" + lineSeparator + "b",
                RocketMqRiskEventAdapter.escapeJson("a" + lineSeparator + "b"));
    }

    @Test
    @DisplayName("适配器的复查判据与 RiskDecision#producesEvent 逐组合一致")
    void reportableAgreesWithProducesEvent() {
        // 这是「两份判据不许漂移」的守卫。适配器里那份是**防御性**复查，判据的唯一
        // 定义在 RiskDecision 上；两份漂移的表现是「某一类决策静默地不再产生事件」
        // ——不报错、不告警，只有 P9.5 报告里少了一类数据。
        //
        // 四个动作 × degraded 两个取值 = 八种组合，全都过一遍。不写成「PASS 不发、
        // 其余发」是因为那样等于把适配器那份判据抄进测试，三份一起漂。
        for (RiskAction action : RiskAction.values()) {
            for (boolean degraded : new boolean[]{false, true}) {
                RiskDecision decision = decision(action, degraded);
                RiskEvent event = eventFor(action, degraded);

                assertEquals(decision.producesEvent(), RocketMqRiskEventAdapter.isReportable(event),
                        "两个判据在 action=" + action + " degraded=" + degraded + " 上不一致");
            }
        }
    }

    @Test
    @DisplayName("两个判据都不发风控关闭时的 PASS——「关闭」不是「降级」")
    void disabledRiskProducesNoEvent() {
        // RiskDecision.pass() 是风控关闭时的结果：action=PASS 且 degraded=false。
        // 它在**两个**判据上都必须为假。这一条单独写出来，是因为「关闭」与「降级」
        // 在代码里长得很像（都是 action=PASS），只有 degraded 一位之差。
        RiskDecision off = RiskDecision.pass("1");
        RiskEvent event = new RiskEvent("evt-1", "req-1", RiskOperationType.GRAB, ERRAND, USER,
                RiskOutcome.PASSED, RiskAction.PASS, false, List.of(), OCCURRED_AT);

        assertFalse(off.producesEvent());
        assertFalse(RocketMqRiskEventAdapter.isReportable(event));
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private RocketMqRiskEventAdapter adapter(boolean aggregationEnabled) {
        RocketMqRiskEventAdapter adapter = new RocketMqRiskEventAdapter(
                PROVIDER, producer, gate, properties(aggregationEnabled));
        adapters.add(adapter);
        return adapter;
    }

    private static RiskProperties properties(boolean aggregationEnabled) {
        return new RiskProperties(true, "test-only-not-a-secret", RiskMode.SHADOW, true,
                10, 10_000, "1",
                new RiskProperties.UserWindow(10, 5, 60, 30),
                new RiskProperties.DeviceWindow(600, 8, 3),
                new RiskProperties.IpAccountWindow(600, 10),
                new RiskProperties.IpRateWindow(60, 100),
                aggregationEnabled, "test-instance");
    }

    private static RiskEvent limitEvent(long runnerId) {
        return new RiskEvent("evt-" + runnerId, "req-" + runnerId, RiskOperationType.GRAB,
                ERRAND, runnerId, RiskOutcome.DENIED, RiskAction.LIMIT, false, List.of(), OCCURRED_AT);
    }

    private static RiskEvent passButDegradedEvent(long runnerId) {
        return new RiskEvent("evt-" + runnerId, "req-" + runnerId, RiskOperationType.GRAB,
                ERRAND, runnerId, RiskOutcome.PASSED, RiskAction.PASS, true,
                List.of(DegradeReason.BLACKLIST_FAILED), OCCURRED_AT);
    }

    private static RiskEvent eventFor(RiskAction action, boolean degraded) {
        List<DegradeReason> reasons = degraded ? List.of(DegradeReason.BLACKLIST_FAILED) : List.of();
        RiskOutcome outcome = action == RiskAction.PASS ? RiskOutcome.PASSED : RiskOutcome.DENIED;
        return new RiskEvent("evt-1", "req-1", RiskOperationType.GRAB, ERRAND, USER,
                outcome, action, degraded, reasons, OCCURRED_AT);
    }

    /** 走完整构造器而不是绕开分数校验的旁路：那样才会连 {@code action == ofScore(score)} 一起验。 */
    private static RiskDecision decision(RiskAction action, boolean degraded) {
        int score = switch (action) {
            case PASS -> 0;
            case LIMIT -> RiskAction.LIMIT_MIN;
            case REVIEW -> RiskAction.REVIEW_MIN;
            case BLOCK -> RiskAction.BLOCK_MIN;
        };
        List<DegradeReason> reasons = degraded ? List.of(DegradeReason.BLACKLIST_FAILED) : List.of();
        return new RiskDecision(score, action, List.of(), "1", reasons);
    }

    private static ClientServiceProvider loadProvider() {
        try {
            return ClientServiceProvider.loadService();
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "取不到 RocketMQ 的 ClientServiceProvider 实现（SPI）。本类刻意用真的 provider："
                            + "只有 Producer 是假的，这样断言的就是生产真正会构造出来的那个 Message。"
                            + "取不到说明 rocketmq-client-java 不在测试类路径上。", t);
        }
    }

    private static Logger publishLogger() {
        return (Logger) LoggerFactory.getLogger(RiskEventPublishLog.class);
    }

    /** {@code Message.getBody()} 返回的 ByteBuffer 是只读视图，{@code duplicate} 免得读掉它的位置。 */
    private static String bodyOf(Message message) {
        return StandardCharsets.UTF_8.decode(message.getBody().duplicate()).toString();
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private List<String> messages() {
        return lines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> publishLines() {
        return messages().stream().filter(m -> m.startsWith("risk_event_publish ")).toList();
    }

    private List<String> droppedLines() {
        return publishLines().stream().filter(m -> m.endsWith(" result=DROPPED")).toList();
    }

    private List<String> failedLines() {
        return messages().stream().filter(m -> m.startsWith("risk_event_publish_failed ")).toList();
    }

    private void assertAllResults(String result, int expected) {
        List<String> published = publishLines();
        assertEquals(expected, published.size(), published.toString());
        for (String line : published) {
            assertTrue(line.endsWith(" result=" + result), line);
        }
    }

    /**
     * 轮询到失败行出现为止。**只在没有可等的闩时才用**——它不建立 happens-before，
     * 靠的是 {@link #capturePublishLines()} 把列表换成了并发容器，读到的内容才与
     * 已经发生的调用一致。
     *
     * <p>失败行是唯一需要这样等的：它产生在 {@code whenComplete} 的回调里，
     * 而回调没有一个可以挂闩的入口。其余几类结果都能用「已经发出去 N 条」来同步。
     */
    private void awaitFailedLines(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (failedLines().size() >= expected) {
                return;
            }
            sleepBriefly();
        }
        fail("等不到 " + expected + " 行 risk_event_publish_failed；当前的日志行：" + messages());
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 用例里被中断不是一种预期结果，是一个坏了的环境。吞掉它只会让上面那个
            // 循环空转到超时，然后以「等不到某一行」的形式报出来——那是假线索。
            throw new IllegalStateException("等待日志行时被中断", e);
        }
    }

    /**
     * 手写假 {@code Producer}。
     *
     * <p>{@code Producer} 有 7 个方法（按 jar 里的字节码核对过），适配器只该用
     * {@code sendAsync} 一个。其余 6 个一律抛，理由同 {@code FakeStringRedisTemplate}：
     * 它们不是「还没轮到实现」，是这个假实现**刻意不支持**的——适配器哪天开始调
     * {@code send(...)} 或 {@code beginTransaction()}，这里应该炸，而不是安静地走过
     * 一个返回 null 的桩。
     *
     * <p>{@code close()} 也在抛之列：生产者的生命周期归 {@code RocketMqConfig} 的
     * {@code destroyMethod} 管。适配器若在 {@code @PreDestroy} 里顺手关掉它，抢单进程
     * 一次重启就会把注入进来的单例 bean 关死，而重启是运维最常做的事。
     */
    private static final class FakeProducer implements Producer {

        /** 只记调用流水，不解释对错——那是测试的事。 */
        final List<Message> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger sendAsyncCalls = new AtomicInteger();

        /** true 时用异常完成 future——broker 收下了请求但拒了消息。 */
        volatile boolean failAsync;

        /** 非 null 时同步抛出——组包阶段就出错的那一路。 */
        volatile RuntimeException thrownOnAsync;

        private volatile CountDownLatch blockGate;
        private volatile CountDownLatch enteredSend;
        private volatile CountDownLatch sendLatch = new CountDownLatch(1);

        void expectSends(int n) {
            sendLatch = new CountDownLatch(n);
        }

        boolean awaitSends() throws InterruptedException {
            return sendLatch.await(10, TimeUnit.SECONDS);
        }

        /** 让接下来的 {@code sendAsync} 全部卡住（**不再解开**，见队列满那条用例）。 */
        void blockNextSends() {
            blockGate = new CountDownLatch(1);
            enteredSend = new CountDownLatch(1);
        }

        /**
         * 等工作线程真的进到 {@code sendAsync} 里。
         *
         * <p>不这样等的话，「只调了一次 sendAsync」这类断言是在跟线程启动赛跑：
         * 断言时工作线程可能还没被调度起来，那个计数就是 0，而这与会话被拒绝毫无关系。
         */
        boolean awaitThreadStuck() throws InterruptedException {
            return enteredSend.await(10, TimeUnit.SECONDS);
        }

        @Override
        public CompletableFuture<SendReceipt> sendAsync(Message message) {
            sendAsyncCalls.incrementAndGet();
            CountDownLatch entered = enteredSend;
            if (entered != null) {
                entered.countDown();
            }

            CountDownLatch block = blockGate;
            if (block != null) {
                awaitUninterruptibly(block);
            }
            if (thrownOnAsync != null) {
                throw thrownOnAsync;
            }

            sent.add(message);
            sendLatch.countDown();

            if (failAsync) {
                CompletableFuture<SendReceipt> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("broker 拒收"));
                return failed;
            }
            // 收据不必造：适配器只判 error 是否为 null，从不读 receipt 的字段。
            return CompletableFuture.completedFuture(null);
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            boolean interrupted = false;
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        // ── 以下 6 个刻意不支持，理由见类注释 ──────────────────────────────

        private static final String NOT_SUPPORTED =
                "这个替身只实现 sendAsync——适配器也只该用它。走到这里说明被测代码开始"
                        + "同步发送或使用事务了，那是适配器形状变了，不是替身缺功能。";

        @Override
        public SendReceipt send(Message message) throws ClientException {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public SendReceipt send(Message message, Transaction transaction) throws ClientException {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Transaction beginTransaction() throws ClientException {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public RecallReceipt recallMessage(String topic, String receiptHandle) throws ClientException {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public CompletableFuture<RecallReceipt> recallMessageAsync(String topic, String receiptHandle) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public void close() throws IOException {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }
    }

    /**
     * 手写假闸门。只记录「问了什么」并回放一个脚本，不判断对错。
     *
     * <p>{@code super(null)}：本类覆盖了父类唯一的公开方法，{@code redis} 字段永远不会被
     * 读到。传 null 而不是造一个假的 {@code StringRedisTemplate}，是为了让「假实现漏覆盖了
     * 父类新加的方法」表现为 NPE，而不是一个安静的返回值。键名本身的正确性由
     * {@code RedisRiskEventGateTest} 与 {@code RiskKeysTest} 负责，不在这里重复。
     *
     * <p>脚本里的取值：{@code Boolean} 是 {@code tryAcquire} 的返回值，
     * {@code RuntimeException} 是「抛出去」。
     */
    private static final class FakeGate extends RedisRiskEventGate {

        final List<String> calls = new CopyOnWriteArrayList<>();
        private final Deque<Object> script = new ArrayDeque<>();

        FakeGate() {
            super(null);
        }

        FakeGate script(Object... results) {
            script.addAll(List.of(results));
            return this;
        }

        @Override
        public boolean tryAcquire(RiskEventGateType type, String subject) {
            calls.add(type + "/" + subject);
            Object next = script.poll();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            // 脚本用完之后默认放行：这样「闸门只被问了一次」之类的用例不必写满脚本。
            return next == null || (Boolean) next;
        }
    }
}
