package com.campusdash.it;

import com.campusdash.application.usecase.GrabErrandUseCase;
import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.domain.risk.model.RiskEventGateType;
import com.campusdash.infrastructure.risk.DeviceHasher;
import com.campusdash.infrastructure.risk.RiskEventPublishLog;
import com.campusdash.infrastructure.risk.RiskKeys;
import com.campusdash.infrastructure.risk.RiskProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P9.4 的端到端验收：一条风险事件从 **app 进程**发出去，被**另一个真的 worker 进程**收下。
 *
 * <p>本类是「异步」这个词唯一能被证伪的地方（设计 §8.2）。前面八个阶段的用例全在一个 JVM 里，
 * 「事件发出去了」与「有人收到了」在同一个进程内做断言时，两者之间其实没有跨过任何边界——
 * 消息可能压根没上 broker，而两边照样绿。这里要的形态是：
 *
 * <pre>
 *   app 进程（本测试的 Spring 上下文，真 producer）
 *        │  risk_event_publish … eventId=E   ← 发布行（LogCapture，同 JVM）
 *        ▼
 *   RocketMQ topic errand-risk-event
 *        │
 *        ├──► 观察者消费组 dash-risk-event-it ──► 载荷逐字段（RiskEventProbe）
 *        └──► worker 子进程 dash-risk-event-consumer
 *                    │  risk_event_processed … eventId=E   ← 消费行（读子进程日志文件）
 * </pre>
 *
 * <p>同一个 {@code eventId} 出现在发布行与消费行里，就是「跨进程往返」的判据（D3）；
 * 观察者收到的载荷是**第二份独立证据**——日志是进程自己说它做了什么，载荷证明消息真的过了 broker。
 *
 * <h2>三条用例</h2>
 *
 * <ol>
 *   <li>往返：{@code LIMIT} 事件从 app 到 worker，两行 {@code eventId} 相同</li>
 *   <li>聚合抑制：同一用户同一窗口内多次 {@code LIMIT}，只有一条 {@code SENT}、
 *       其余记 {@code SUPPRESSED}，且 broker 上**真的只有一条消息**</li>
 *   <li>降级路径：{@code deadline-ms=0} 但 {@code fail-open=true} 的真实 HTTP 抢单，
 *       服务端生成的 {@code requestId} 与事件载荷里的**是同一个字符串**（D4 的跨阶段落点）</li>
 * </ol>
 *
 * <h2>子 worker 必须用隔离库</h2>
 *
 * <p>worker 一启动就跑五个没有 {@code initialDelay} 的定时作业，其中超时扫描会把库里
 * 过期的存量任务就地改状态。实测在共享测试库上：2 秒内 140 个任务回退、78 个流转、
 * 真发出 8 条 MQ 消息——那些任务属于别的 IT，事后无法还原（见
 * {@code 实现期发现.md} F-30）。所以子进程的数据源被强制指到 {@code campus_dash_worker_it}，
 * 并且本类在启动它之前**先验证那个库存在**，不存在就整类跳过：
 *
 * <pre>
 *   docker exec dash-mysql mysql -uroot -pdash123456 \
 *     -e "CREATE DATABASE IF NOT EXISTS campus_dash_worker_it CHARACTER SET utf8mb4"
 *   docker exec -i dash-mysql mysql -uroot -pdash123456 campus_dash_worker_it < docker/init.sql
 * </pre>
 *
 * <p>用「库不存在就当没做隔离」当硬闸门，而不是自动建库：建库要灌整个 {@code init.sql}，
 * 那是 docker 的职责，测试悄悄替环境做掉只会让「干净机器上跑一遍」变得不可复现。
 *
 * <p><b>已知边界：Redis 隔离不了。</b>{@code RedissonConfig} 只读 host/port，没有
 * {@code database} 项，所以子 worker 与本测试共用 6380 的 db0。本轮的风险事件链路里
 * 子 worker 不读写任何 Redis 键（消费只解载荷 + 打一行日志），所以共用不影响本类的结论；
 * 但这条边界必须写下来，因为它意味着「两个进程的 Redis 视图可以不一样」这件事没有被验证过。
 *
 * <h2>为什么守卫要分三段</h2>
 *
 * <p>{@code MySQL/Redis} → {@code RocketMQ proxy} → {@code worker jar 存在且不比源码旧}。
 * 三段分开而不是合成一个 {@code check()}：本类是唯一需要 broker 的用例，也是唯一需要
 * 先 {@code package} 的用例，合成一个会让跳过信息说不清缺的是哪一样。
 */
class RiskEventIT {

    /**
     * 本类私有的 id 段。910_xxx {@code RiskFeatureIT} / 920_xxx {@code RiskPreCheckIT} /
     * 930_xxx {@code RiskGrabLinkIT} / 940_001–942_000 {@code RiskDegradeConcurrencyIT}，
     * 都不碰。
     */
    private static final long USER_BASE = 950_000L;

    /** 类级别的探针：消费组只有一个，多个上下文共用它（见 {@link #ensureProbe()}）。 */
    private static RiskEventProbe probe;

    @AfterAll
    static void stopProbe() throws Exception {
        if (probe != null) {
            probe.close();
            probe = null;
        }
    }

    /**
     * 三段前置条件，每个 {@code @Nested} 各调一次。
     *
     * <p>写在 {@code @Nested} 的 {@code @BeforeAll} 里而不是外层的：这是本包 28 个 IT
     * 一致的写法，而且跳过的粒度就是「这个上下文」（某个上下文缺 broker 不该把另一个
     * 也拖下水）。
     */
    private static void requireRiskEventStack() throws Exception {
        Assumptions.assumeTrue(MiddlewareAvailable.check(),
                "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        Assumptions.assumeTrue(MiddlewareAvailable.mqReachable(),
                "RocketMQ gRPC proxy(8081) 未启动，跳过需要 broker 的用例。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        Assumptions.assumeTrue(WorkerSubprocess.jarReady(), WorkerSubprocess.jarHint());
        ensureProbe();
    }

    /** 探针懒起 + 幂等：第二个上下文进来时复用同一个消费者。 */
    private static synchronized void ensureProbe() throws Exception {
        if (probe == null) {
            probe = RiskEventProbe.start("127.0.0.1:8081");
        }
    }

    // ── 上下文一：ENFORCE（走到越线，产生真事件） ────────────────────────────
    //
    // 隔离库与子进程的守卫（requireIsolatedDbAndWorker）已搬到 WorkerSubprocess 里：
    // P9.5 的 WorkerRestartIT 要用同一段逻辑，复制第二份的代价在于那条跳过提示里的
    // docker 命令会漂移。搬动时**只改了类名**，断言与提示文案一个字都没动。

    @Nested
    @SpringBootTest(properties = {
            "dash.risk.mode=ENFORCE",
            // 测试 yaml 默认关着 MQ（另外 27 个 IT 不需要 broker）。本类是唯一开它的。
            "dash.mq.enabled=true",
            "dash.risk.event-aggregation-enabled=true",
            "dash.credit.max-ongoing=10000"})
    class ProcessToProcess {

        @Autowired GrabErrandUseCase grabUseCase;
        @Autowired PublishErrandUseCase publishUseCase;
        @Autowired StringRedisTemplate redis;
        @Autowired RiskProperties props;
        @Autowired DeviceHasher hasher;
        @Autowired JdbcTemplate jdbc;

        @BeforeAll
        static void requireMiddleware() throws Exception {
            requireRiskEventStack();
        }

        @BeforeEach
        void setUp() {
            WorkerSubprocess.requireIsolatedDbAndWorker(jdbc);
            ItFixtures.resetPublisherBalance(jdbc);
        }

        @Test
        @DisplayName("端到端往返：app 进程发一条、真 worker 进程收一条，两行的 eventId 相同（D3）")
        void oneEventTravelsFromOneProcessToTheOther() {
            long userId = USER_BASE + 1;
            String tag = "it-roundtrip-" + System.currentTimeMillis();
            ItFixtures.purgeUser(redis, props, hasher, userId, null);
            ItFixtures.purgeEventGates(redis, userId);
            long errandId = ItFixtures.publishSlots(publishUseCase, 1);

            try (LogCapture publishLog = LogCapture.of(RiskEventPublishLog.class)) {
                // 打到越线为止：最后一次必是 LIMIT（窗口计数越线），而只有非 PASS 才发事件。
                // 前几次是 PASS、不发事件——这正好让下面「恰好一条」这个断言成立，
                // 而不是靠过滤把多余的挑掉。
                int attempts = props.userWindow().shortThreshold() + 1;
                for (int i = 0; i < attempts; i++) {
                    grabUseCase.grab(new GrabErrandUseCase.Command(errandId, userId, tag + "-" + i));
                }

                // 等「第 2 条」而不是「等着看有几条」：投递行在 risk-event-pub 线程上异步写，
                // 数一次就下结论会输给那几毫秒（实测过）。2 秒内等不到第 2 条 = 真的只有 1 条。
                //
                // 认 requestId 用 startsWith 而不是 equals：每次 grab 的 requestId 是
                // `tag + "-" + i`，写 equals(tag) 一条也匹配不上——而症状（「0 条投递」）
                // 看起来像「事件没发出去」，其实它发了，是断言认错了名字。
                List<String> sent = publishLog.awaitLines("risk_event_publish",
                        l -> {
                            String requestId = LogCapture.field(l, "requestId");
                            return requestId != null && requestId.startsWith(tag);
                        }, 2, Duration.ofSeconds(2));
                assertEquals(1, sent.size(),
                        "本次运行应当恰好投出一条事件（前几次 PASS 不发）；实际全部投递行：" + publishLog.raw());
                String publishLine = sent.get(0);
                assertEquals("SENT", LogCapture.field(publishLine, "result"));
                assertEquals("GRAB", LogCapture.field(publishLine, "operationType"));
                assertEquals(String.valueOf(errandId), LogCapture.field(publishLine, "errandId"));
                assertEquals(String.valueOf(userId), LogCapture.field(publishLine, "runnerId"));
                assertEquals("LIMIT", LogCapture.field(publishLine, "decisionAction"));
                assertEquals("false", LogCapture.field(publishLine, "degraded"));

                String eventId = LogCapture.field(publishLine, "eventId");
                assertNotNull(eventId, "投递行必须带 eventId（没有它这行接不回任何东西）：" + publishLine);

                // 一、worker 进程的日志里有同一个 eventId。这是「跨进程」的全部证据：
                //     发布行在测试 JVM 的内存 appender 里，消费行在子进程的日志文件里。
                String consumeLine = WorkerSubprocess.awaitLine(
                        l -> l.contains("risk_event_processed") && l.contains("eventId=" + eventId),
                        Duration.ofSeconds(60));
                assertNotNull(consumeLine, "真 worker 进程 60 秒内没消费到 eventId=" + eventId
                        + "；子进程日志尾部：\n" + WorkerSubprocess.tail(40));
                // D3：两行靠 eventId 串起来，其余字段必须自洽（同一个事件、同一次决策）
                assertEquals(eventId, LogCapture.field(consumeLine, "eventId"));
                assertEquals("GRAB", LogCapture.field(consumeLine, "operationType"));
                assertEquals(String.valueOf(errandId), LogCapture.field(consumeLine, "errandId"));
                assertEquals("DENIED", LogCapture.field(consumeLine, "outcome"));
                assertEquals("LIMIT", LogCapture.field(consumeLine, "decisionAction"));
                assertEquals("-", LogCapture.field(consumeLine, "degradeReasons"),
                        "没有降级原因时空集渲染成 -（不是空串）");
                assertTrue(Long.parseLong(LogCapture.field(consumeLine, "lagMs")) >= 0,
                        "lagMs 是消费时刻减事件时刻，被夹到 >= 0：" + consumeLine);

                // 二、broker 上真有一份载荷。日志证明不了「消息过了 broker」——
                //     生产者完全可能打完日志而没发出去，所以这份载荷是独立的一份证据。
                RiskEventProbe.Received message = probe.await(
                        m -> eventId.equals(m.keys()), Duration.ofSeconds(30));
                assertNotNull(message, "观察者消费组 " + RiskEventProbe.GROUP
                        + " 没在 topic 上收到 key=" + eventId + " 的消息");
                assertEquals(eventId, RiskEventProbe.payloadString(message.body(), "eventId"));
                assertEquals(tag + "-" + (attempts - 1), RiskEventProbe.payloadString(message.body(), "requestId"),
                        "载荷里的 requestId 必须是触发这次决策的那一个");
                assertEquals("GRAB", RiskEventProbe.payloadString(message.body(), "operationType"));
                assertEquals(String.valueOf(errandId), RiskEventProbe.payloadRaw(message.body(), "errandId"));
                assertEquals(String.valueOf(userId), RiskEventProbe.payloadRaw(message.body(), "runnerId"));
                assertEquals("DENIED", RiskEventProbe.payloadString(message.body(), "outcome"));
                assertEquals("LIMIT", RiskEventProbe.payloadString(message.body(), "decisionAction"));
                assertEquals("false", RiskEventProbe.payloadRaw(message.body(), "degraded"));
                // 消费行与载荷里的 occurredAt 相同 → 消费端解出来的确实是这一条事件，
                // 而不只是「某个 key 相同的消息」。这条把「同一个事件」钉到了字段级。
                assertEquals(RiskEventProbe.payloadRaw(message.body(), "occurredAt"),
                        LogCapture.field(consumeLine, "occurredAt"),
                        "消费行与载荷里的 occurredAt 必须一致");
            }
        }

        @Test
        @DisplayName("聚合抑制：同一窗口内多次 LIMIT 只发一条，broker 上也只有一条消息")
        void aggregationSendsOnlyOneWithinTheWindow() {
            long userId = USER_BASE + 2;
            String tag = "it-agg-" + System.currentTimeMillis();
            ItFixtures.purgeUser(redis, props, hasher, userId, null);
            ItFixtures.purgeEventGates(redis, userId);
            long errandId = ItFixtures.publishSlots(publishUseCase, 1);

            // 越线那一次 SENT，之后再打两次：那两次都必须在闸门处被吞掉。
            // 多打的这两次是本用例的全部意义——只打到越线的话，测不出聚合。
            int attempts = props.userWindow().shortThreshold() + 3;
            try (LogCapture publishLog = LogCapture.of(RiskEventPublishLog.class)) {
                for (int i = 0; i < attempts; i++) {
                    grabUseCase.grab(new GrabErrandUseCase.Command(errandId, userId, tag + "-" + i));
                }

                // 越线之后恰好 3 次 LIMIT 决策（第 threshold+1 次起），所以恰好 3 行投递。
                // 等「第 4 行」来表达「第 4 行不会来」——异步写的行不能靠数一次定论。
                List<String> mine = publishLog.awaitLines("risk_event_publish", l -> {
                    String requestId = LogCapture.field(l, "requestId");
                    return requestId != null && requestId.startsWith(tag);
                }, 4, Duration.ofSeconds(2));
                List<String> sent = mine.stream()
                        .filter(l -> "SENT".equals(LogCapture.field(l, "result"))).toList();
                List<String> suppressed = mine.stream()
                        .filter(l -> "SUPPRESSED".equals(LogCapture.field(l, "result"))).toList();

                assertEquals(1, sent.size(), "同一 10 秒窗内只该有一条 LIMIT 事件真发出去；实际：" + publishLog.raw());
                assertEquals(2, suppressed.size(),
                        "越线之后的另外两次都必须被闸门吞掉并留下 SUPPRESSED 行；实际：" + mine);
                assertEquals(mine.size(), sent.size() + suppressed.size(),
                        "除 SENT 外的每一条都该是 SUPPRESSED（既不能消失、也不能发第二遍）：" + mine);
                assertEquals("LIMIT", LogCapture.field(sent.get(0), "decisionAction"));
                // gateType 在抑制行上也有值：定档发生在「要不要跳过闸门」之前，
                // 这正是 SENT_UNGATED 不需要存在的原因（设计 §4.3）。
                assertTrue(suppressed.stream()
                                .allMatch(l -> "LIMIT".equals(LogCapture.field(l, "gateType"))),
                        "被抑制的行也必须说得出自己属于哪一类闸门：" + suppressed);

                String eventId = LogCapture.field(sent.get(0), "eventId");

                // broker 侧：等到那一条，再静默 3 秒确认没有第二条。
                // 只数一次不够——第二条可能晚到，那样「恰好一条」是假绿。
                assertTrue(probe.awaitQuiet(m -> eventId.equals(m.keys()),
                                Duration.ofSeconds(3), Duration.ofSeconds(30)),
                        "本该只有一条 SENT，但 3 秒静默期内 topic 上又来了同 key 的消息");

                // 反向：抑制的是「雷同的第二条」，不是整条链路。
                assertNotNull(WorkerSubprocess.awaitLine(
                                l -> l.contains("risk_event_processed") && l.contains("eventId=" + eventId),
                                Duration.ofSeconds(60)),
                        "被抑制之外的那一条必须照常到达 worker；子进程日志尾部：\n"
                                + WorkerSubprocess.tail(40));
            }
        }
    }

    // ── 上下文二：ENFORCE + fail-open=true + deadline-ms=0 + 真实 HTTP ──────

    /**
     * 降级路径 + 真实 HTTP。两条判据都只能在这里拿到：
     *
     * <ul>
     *   <li>{@code deadline-ms=0} 且 {@code fail-open=true}：决策是「PASS + 降级」，
     *       按蓝图 §5.3 它**必须**产生事件（{@code degraded} 也是事件，D2）；</li>
     *   <li>不带 {@code X-Request-Id} 头：{@code requestId} 由服务端生成，
     *       而它出现在两个地方——抢单幂等键里（业务侧）与事件载荷里（风控侧）。
     *       两者同值就是 D4 的落点证据。</li>
     * </ul>
     *
     * <p>为什么不在直接调用例里做这条：{@code requestId} 的解析活在 controller
     * 与拦截器之间，直接构造 {@code Command} 会把那个缺陷整个绕开
     * （{@code RiskGrabLinkIT.Enforce#bothLinksShareTheSameRequestId} 是它的同型用例）。
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "dash.risk.mode=ENFORCE",
                    "dash.risk.fail-open=true",
                    "dash.risk.deadline-ms=0",
                    "dash.mq.enabled=true",
                    "dash.risk.event-aggregation-enabled=true",
                    "dash.credit.max-ongoing=10000"})
    class DegradedOverHttp {

        @Autowired PublishErrandUseCase publishUseCase;
        @Autowired StringRedisTemplate redis;
        @Autowired RiskProperties props;
        @Autowired DeviceHasher hasher;
        @Autowired JdbcTemplate jdbc;
        @Autowired TestRestTemplate rest;

        @BeforeAll
        static void requireMiddleware() throws Exception {
            requireRiskEventStack();
        }

        @BeforeEach
        void setUp() {
            WorkerSubprocess.requireIsolatedDbAndWorker(jdbc);
            ItFixtures.resetPublisherBalance(jdbc);
        }

        @Test
        @DisplayName("降级但放行：HTTP 里服务端生成的 requestId 就是事件载荷里的那一个（D4）")
        void degradedPassKeepsTheServerGeneratedRequestId() {
            long userId = USER_BASE + 3;
            ItFixtures.purgeUser(redis, props, hasher, userId, null);
            ItFixtures.purgeEventGates(redis, userId);
            // DEGRADED 闸门的主体是**实例**不是用户（RiskKeys 两个重载的分工），
            // 所以按用户清不掉它，得按 instanceId 清。
            redis.delete(RiskKeys.eventGate(RiskEventGateType.DEGRADED, props.instanceId()));
            long errandId = ItFixtures.publishSlots(publishUseCase, 1);

            try (LogCapture publishLog = LogCapture.of(RiskEventPublishLog.class)) {
                String token = ItFixtures.login(rest, userId);
                var resp = rest.exchange("/api/errands/" + errandId + "/grab", HttpMethod.POST,
                        ItFixtures.grabRequest(token, null), Map.class);
                assertEquals(200, resp.getStatusCode().value());
                assertEquals("OK", resp.getBody().get("code"),
                        "fail-open=true 时预算是 0 只降级不拦，抢单必须照常成功：" + resp.getBody());

                // 业务侧那个 requestId：本次抢单唯一一条幂等键的后半截
                Set<String> idemKeys = redis.keys("errand:idem:{" + errandId + "}:*");
                assertNotNull(idemKeys);
                assertEquals(1, idemKeys.size(), "本次抢单应当留下且只留下一个幂等键：" + idemKeys);
                String generated = idemKeys.iterator().next()
                        .substring(ItFixtures.idemKey(errandId, "").length());

                // 载荷里那个：同一条事件该带着同一个字符串走完整条异步链路
                RiskEventProbe.Received message = probe.await(
                        m -> generated.equals(RiskEventProbe.payloadString(m.body(), "requestId")),
                        Duration.ofSeconds(30));
                assertNotNull(message, "降级但放行也必须发一条事件（degraded 也是事件，D2）；"
                        + "载荷里没有 requestId=" + generated);
                assertEquals("GRAB", RiskEventProbe.payloadString(message.body(), "operationType"));
                assertEquals("PASS", RiskEventProbe.payloadString(message.body(), "decisionAction"));
                assertEquals("true", RiskEventProbe.payloadRaw(message.body(), "degraded"));
                assertEquals(String.valueOf(userId), RiskEventProbe.payloadRaw(message.body(), "runnerId"));
                // 预算为 0 时四个采集步骤一步都不跑，所以原因恰好是 DEADLINE_EXCEEDED 一条
                // （设计 §8.1：「这一次预检证据不全」只发生一次）
                assertEquals("DEADLINE_EXCEEDED",
                        RiskEventProbe.payloadString(message.body(), "degradeReasons"));

                // 投递行上的定档：降级事件走 DEGRADED 闸门（主体是实例）
                List<String> sent = publishLog.lines("risk_event_publish").stream()
                        .filter(l -> generated.equals(LogCapture.field(l, "requestId")))
                        .toList();
                assertEquals(1, sent.size(), "这条请求该恰好投出一条事件；实际：" + publishLog.raw());
                assertEquals("SENT", LogCapture.field(sent.get(0), "result"));
                assertEquals("DEGRADED", LogCapture.field(sent.get(0), "gateType"));
                assertEquals("true", LogCapture.field(sent.get(0), "degraded"));

                // 同一 eventId 也到了 worker 进程——「降级事件照样走完整条链路」的收口
                String eventId = RiskEventProbe.payloadString(message.body(), "eventId");
                assertNotNull(eventId);
                assertNotNull(WorkerSubprocess.awaitLine(
                                l -> l.contains("risk_event_processed") && l.contains("eventId=" + eventId),
                                Duration.ofSeconds(60)),
                        "降级事件也必须被 worker 消费到；子进程日志尾部：\n" + WorkerSubprocess.tail(40));
            }
        }
    }
}
