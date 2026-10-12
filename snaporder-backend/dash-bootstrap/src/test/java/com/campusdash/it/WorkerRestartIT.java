package com.campusdash.it;

import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.infrastructure.risk.DeviceHasher;
import com.campusdash.infrastructure.risk.RiskEventPublishLog;
import com.campusdash.infrastructure.risk.RiskProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「Worker 停止后恢复」的自动化落点（P9.5 决策 D8）。
 *
 * <p>P9.4 §8.4 把这条留给手工实测，理由是「现有证据链分不出这两种情况」——重启**前**发的事件
 * 与重启**后**发的事件在日志里长得一样。**那条理由现在不成立**：消费行上有
 * {@code lagMs}（消费时刻 − 事件时刻）与 {@code deliveryAttempt}，而 {@code lagMs} 恰好就是缺的那一段。
 * 于是判据可以写成一个不等式：
 *
 * <pre>
 *   消费行.lagMs  ≥  第二个子进程的启动时刻 − 事件发生时刻
 * </pre>
 *
 * <p>三种写法的区别值得说清，免得后来人被简化掉：
 *
 * <ul>
 *   <li>「消费行出现了」——**不够**。事件也可能是重启之后才发的，那种情况下这条同样绿。</li>
 *   <li>「{@code lagMs} 很大」——不够。它大也可能只是因为消费慢，而不是因为停机窗口。</li>
 *   <li>「{@code lagMs} ≥ 重启时刻 − 事件时刻」——**够**。它要求事件时刻早于新进程的启动时刻，
 *       也就是「消息在停机窗口里一直躺在 broker 上」。这是本用例唯一能被证伪的地方。</li>
 * </ul>
 *
 * <p>这是对 P9.4 §8.4 那句话的设计内修正，不是推翻它的结论。
 *
 * <h2>「这一行是新进程写的」靠什么证明</h2>
 *
 * <p>{@code WorkerSubprocess.start()} 每次都把日志文件**截断**（那个动作原本是为了让
 * 「本轮收到几条」算得对）。这条副作用在这里正好是判据本身：重启之后从文件里读到的
 * {@code risk_event_processed} 行**只可能**由第二个进程写出——第一个进程的日志已经没了。
 * 于是「同一个 {@code eventId} 出现在重启后的文件里」就等价于「新进程消费了这条消息」，
 * 不必再比对时间戳或 PID。
 *
 * <h2>为什么先打一条预热事件</h2>
 *
 * <p>两个理由，缺了它这条用例会变成一条「时灵时不灵」的用例：
 *
 * <ol>
 *   <li><b>起点</b>：没有预热，「重启后还能消费」就不知道是从哪儿恢复的——
 *       第一个进程到底消费过没有？预热行就在同一个日志文件里，直接给出答案。</li>
 *   <li><b>消费组位点</b>：本用例发布事件时消费组**没有在线成员**。gRPC 客户端在一个
 *       从未提交过位点的消费组上重新上线时是从最早还是从最新位置开始，本阶段没有单独验证过——
 *       先消费一条把这个前提坐实，就不必去赌它。（{@code RiskEventIT} 发布事件时 worker 已经在线，
 *       所以它碰不到这一条。）</li>
 * </ol>
 *
 * <h2>停下那一步不是「优雅停」</h2>
 *
 * <p>{@code WorkerSubprocess.stop()} 是 {@code destroy()}（SIGTERM）→ 等 8 秒 →
 * {@code destroyForcibly()}（SIGKILL）。按 D-6，worker 收到 SIGTERM 后**不会退出**
 * （关停钩子卡在 RocketMQ {@code PushConsumer.close()} 的 {@code awaitTerminated}），
 * 所以那 8 秒每次都走满、最后一定落在强杀上——它看起来像防御性编程，实际每一步都会发生。
 *
 * <p>因此本用例的验收目标是**「重启后能继续消费」，不是「优雅关停」**：后者当前不可达，
 * 属于 D-6，等排期。这条限制会原样写进交付物（设计 §8）。D-6 修掉之后，
 * 把 {@code stop()} 换成 SIGTERM 而不强杀即可升级这条用例。
 *
 * <h2>停机之后，消费行要等 60 秒量级才出现</h2>
 *
 * <p>这是实测读数（F-34 记了原始日志与时间戳）：事件 {@code occurredAt} 到消费行落盘之间是
 * **67959 毫秒**，而且那一条消费行的 {@code deliveryAttempt=2}——消息**先被投给了那个已经死掉的成员**
 * （那算一次投递），要等 broker 侧一个 60 秒量级的期限过去、队列重新分给新成员，才回到新进程手上。
 *
 * <p>两个后果都写进了这条用例：等待时长按整段加余量取（{@link #AWAIT_CONSUME}），
 * 而 {@code deliveryAttempt} 只打印不断言（断言 1 会永远红，断言 2 是把一次实测读数当不变式）。
 *
 * <p>这也是 D-6（优雅停不可达）的**运行后果**，不只是代码缺陷：重启 worker 之后的一分钟量级内，
 * 停机期间产生的事件可能还没有消费行。这条要原样进交付物的限制小节。
 *
 * <h2>守卫与隔离库</h2>
 *
 * <p>与 {@code RiskEventIT} 同源：先过「中间件 → broker → worker jar 新鲜度」三关，
 * 再验隔离库存在（不存在就整类跳过），子进程的数据源由
 * {@code WorkerSubprocess.requireIsolatedDbAndWorker} 强制指到 {@code campus_dash_worker_it}。
 * 本类**不用**事件观察者探针：要证明的是「另一个进程消费了它」，消费行已经足够。
 */
class WorkerRestartIT {

    /** 本类私有的 id 段（其它 IT 用 910_xxx / 920_xxx / 930_xxx / 940_xxx / 950_xxx / 960_xxx）。 */
    private static final long USER_BASE = 970_000L;

    /**
     * 停机窗口：发布事件之后、重启之前**故意空等**的一段。
     *
     * <p>没有它，{@code t重启 ≈ t事件}，那个不等式的下界就成了 0 毫秒——
     * 「{@code lagMs} ≥ 下界」变成一句恒真的话，用例照样绿而什么也没证明。
     * 3 秒是量级选择：远大于 {@code lagMs} 的毫秒级（区分得开「重启后才发」），
     * 又远小于启用新进程要的十几秒冷启动（不拉长用例）。
     *
     * <p><b>它与「消息多久才被消费」无关</b>：真正决定后者的是 broker 侧那个
     * 60 秒量级的期限（见 {@link #AWAIT_CONSUME}），停机窗口只影响判据的下界。
     * 把两件事混起来会得出「加长停机窗口能更快看到消费行」这种反过来的结论。
     */
    private static final Duration DOWN_WINDOW = Duration.ofSeconds(3);

    /**
     * 等消费行的时长。**150 秒，不是 60 秒**——这个数字是实测出来的，不是拍的。
     *
     * <p>第一次跑这条用例时用的是 60 秒，失败信息里写「60 秒内没消费到」，
     * 而子进程日志里那一行是在**超时的 0.9 秒之后**落下的：`lagMs=67959`、
     * `deliveryAttempt=2`。也就是说消费真的发生了、判据其实成立，只是等得不够久。
     *
     * <p>那 68 秒的来源是「先投给已死成员」：`stop()` 走的是 SIGKILL（D-6 让优雅停不可达），
     * 消费组里那个成员在 broker 侧还活着，消息先被投给它（算一次投递，`deliveryAttempt` 记到 2），
     * 要等一个 60 秒量级的期限过去、队列重新分给新成员，消息才回到新进程手上
     * （F-34 有原始读数与机制推断）。60 秒因此正好卡在边界上——**失败与成功的差别只有 1 秒**，
     * 那种用例是「时灵时不灵」的典型，必须留出整段余量。
     *
     * <p>不能靠「停得干净些」来缩短它：D-6 就是「停不干净」。这是本阶段必须如实记录的一条运维事实
     * ——重启 worker 之后的一分钟量级内，停机期间产生的事件可能还没有消费行。
     */
    private static final Duration AWAIT_CONSUME = Duration.ofSeconds(150);

    /** 一次成功投递里的两个 id：有它们才能把消费行接回**这一条**投递行。 */
    private record Published(String eventId, String requestId) {
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "dash.risk.mode=ENFORCE",
                    // 测试 yaml 默认关着 MQ（其它 IT 不需要 broker）。本类与 RiskEventIT 一样要开它。
                    "dash.mq.enabled=true",
                    "dash.risk.event-aggregation-enabled=true",
                    // 必须覆盖：yaml 里的 10ms 走真 HTTP 时会被连接池冷启动吃光，采集整段被跳过，
                    // 于是这一次请求**不计进用户窗口**（RiskPreCheckUseCase 的四个检查点跳过第 2~4 步）。
                    // 少一次计数 → 连抢 shortThreshold+1 次也越不了线 → 压根不产生事件，
                    // 而失败信息是「最后一次不是 RISK_LIMITED，而是 ALREADY_GRABBED」——指向完全无关的地方。
                    // RiskGrabLinkIT.Shadow 与 RiskTestHeaderBoundaryIT 都覆盖了这一项，理由相同（F-19）。
                    "dash.risk.deadline-ms=1000",
                    "dash.credit.max-ongoing=10000"})
    class RestartDuringAnEvent {

        @Autowired PublishErrandUseCase publishUseCase;
        @Autowired TestRestTemplate rest;
        @Autowired StringRedisTemplate redis;
        @Autowired RiskProperties props;
        @Autowired DeviceHasher hasher;
        @Autowired JdbcTemplate jdbc;

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(),
                    "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
            Assumptions.assumeTrue(MiddlewareAvailable.mqReachable(),
                    "RocketMQ gRPC proxy(8081) 未启动，跳过需要 broker 的用例。请先执行 docker compose -f docker/docker-compose.yaml up -d");
            Assumptions.assumeTrue(WorkerSubprocess.jarReady(), WorkerSubprocess.jarHint());
        }

        @BeforeEach
        void setUp() {
            WorkerSubprocess.requireIsolatedDbAndWorker(jdbc);
            ItFixtures.resetPublisherBalance(jdbc);
        }

        /**
         * 走真 HTTP 打到越线，返回那唯一一条 {@code SENT} 投递行的两个 id（设计 §5.7 第 ③ 步）。
         *
         * <p>次数由配置推导（{@code shortThreshold + 1}）而不是写死：阈值是可配的，写死会让用例在
         * 改配置之后变成「跑完了但什么都没发」而看起来仍然是绿的
         * （与 {@code RiskGrabLinkIT.burstIsRejectedBeforeGrabRedis} 同一条规矩）。
         *
         * <p>越线之前的几次抢单会失败（同一个任务只有一个名额），但**风控预检在名额裁决之前**，
         * 所以窗口照样计数——{@code RiskGrabLinkIT.Enforce#burstIsRejectedBeforeGrabRedis}
         * 用同一个形态验过这条。这里因此只断言 HTTP 层没有 500（业务失败是 200 + 错误码，
         * 见 {@code GlobalExceptionHandler} 的注释），把「哪一次越线」交给投递行去说。
         */
        private Published publishOneEvent(long userId, long errandId) {
            ItFixtures.purgeUser(redis, props, hasher, userId, null);
            ItFixtures.purgeEventGates(redis, userId);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(ItFixtures.login(rest, userId));
            int attempts = props.userWindow().shortThreshold() + 1;

            try (LogCapture publishLog = LogCapture.of(RiskEventPublishLog.class)) {
                String lastCode = null;
                for (int i = 0; i < attempts; i++) {
                    var resp = rest.exchange("/api/errands/" + errandId + "/grab", HttpMethod.POST,
                            new HttpEntity<>(headers), Map.class);
                    assertEquals(200, resp.getStatusCode().value(),
                            "第 " + (i + 1) + " 次抢单在服务端炸了：" + resp.getBody());
                    lastCode = String.valueOf(resp.getBody().get("code"));
                }
                assertEquals("RISK_LIMITED", lastCode,
                        "最后一次必须在风控这一层被拦下（否则这次运行压根没产生事件）");

                // 等第 2 条来表达「不会有第 2 条」：投递行在 risk-event-pub 线程上异步写，
                // 数一次就下结论会输给那几毫秒（F-31）。按 errandId 认领而不是按 requestId：
                // 这条用例不发 X-Request-Id，requestId 由服务端生成，测试侧没有那个字符串（D4）。
                List<String> sent = publishLog.awaitLines("risk_event_publish",
                        l -> String.valueOf(errandId).equals(LogCapture.field(l, "errandId")),
                        2, Duration.ofSeconds(2));
                assertEquals(1, sent.size(),
                        "本次应恰好投出一条事件（越线之前都是 PASS、不发事件）；实际全部投递行：" + publishLog.raw());
                assertEquals("SENT", LogCapture.field(sent.get(0), "result"),
                        "这一条必须真发出去，否则后面等的是一个不存在的事件：" + sent.get(0));

                String eventId = LogCapture.field(sent.get(0), "eventId");
                String requestId = LogCapture.field(sent.get(0), "requestId");
                assertNotNull(eventId, "投递行必须带 eventId：" + sent.get(0));
                assertNotNull(requestId, "投递行必须带 requestId：" + sent.get(0));
                return new Published(eventId, requestId);
            }
        }

        @Test
        @DisplayName("停机期间发布的事件，重启后的新进程照常消费，且 lagMs 覆盖整段停机窗口")
        void eventPublishedWhileWorkerIsDownIsConsumedAfterRestart() throws Exception {
            // ── 0. 预热：证明重启之前这个 worker 就在消费（同时坐实消费组位点） ──
            Published warm = publishOneEvent(USER_BASE, ItFixtures.publishSlots(publishUseCase, 1));
            assertNotNull(WorkerSubprocess.awaitLine(
                            l -> l.contains("risk_event_processed") && l.contains("eventId=" + warm.eventId()),
                            AWAIT_CONSUME),
                    "预热事件 " + AWAIT_CONSUME.toSeconds() + " 秒内都没被消费——那这个 worker 在重启之前就不工作，"
                            + "后面的结论没有起点。子进程日志尾部：\n" + WorkerSubprocess.tail(40));

            // ── 1. 停。SIGTERM 停不住（D-6），8 秒后强杀 ────────────────────
            long stopStartedAt = System.currentTimeMillis();
            WorkerSubprocess.stop();
            long stopMillis = System.currentTimeMillis() - stopStartedAt;

            // ── 2. 停机期间发布事件 ──────────────────────────────────────────
            // 此刻消费组没有在线成员，这条消息只能躺在 broker 上等。
            long userId = USER_BASE + 1;
            long errandId = ItFixtures.publishSlots(publishUseCase, 1);
            Published published = publishOneEvent(userId, errandId);
            long publishedAt = System.currentTimeMillis();

            // ── 3. 故意留出停机窗口，让不等式的下界有量级（见 DOWN_WINDOW 的注释） ──
            Thread.sleep(DOWN_WINDOW.toMillis());
            long restartAt = System.currentTimeMillis();

            // ── 4. 起。日志文件在这一刻被截断，之后读到的行只可能来自新进程 ──
            WorkerSubprocess.ensureStarted();

            // ── 5. 判据 ──────────────────────────────────────────────────────
            String consumeLine = WorkerSubprocess.awaitLine(
                    l -> l.contains("risk_event_processed") && l.contains("eventId=" + published.eventId()),
                    AWAIT_CONSUME);
            assertNotNull(consumeLine, "重启后的 worker " + AWAIT_CONSUME.toSeconds() + " 秒内没消费到 eventId="
                    + published.eventId() + "（停机窗口 " + DOWN_WINDOW.toSeconds() + " 秒）。子进程日志尾部：\n"
                    + WorkerSubprocess.tail(40));

            // 字段级自洽：消费的是**那一条**事件，不是某个 key 碰巧相同的消息。
            assertEquals(published.eventId(), LogCapture.field(consumeLine, "eventId"));
            assertEquals(published.requestId(), LogCapture.field(consumeLine, "requestId"),
                    "消费行与投递行必须是同一个 requestId");
            assertEquals("GRAB", LogCapture.field(consumeLine, "operationType"));
            assertEquals(String.valueOf(errandId), LogCapture.field(consumeLine, "errandId"));
            assertEquals("LIMIT", LogCapture.field(consumeLine, "decisionAction"));

            // deliveryAttempt 只打印、不断言具体值。设计 §5.7 ⑤ 原本写的是「= 1」，
            // 实测是 2：这条消息先被投给了那个已经死掉的成员（见 AWAIT_CONSUME 与 F-34）。
            // 断言 1 会让这条用例永远红；断言「= 2」则是把一次实测读数当成不变式——
            // 它会随着 broker 侧那个期限与新进程启动快慢的关系在 1/2/3 之间跳。
            long lagMs = Long.parseLong(LogCapture.field(consumeLine, "lagMs"));
            String deliveryAttempt = LogCapture.field(consumeLine, "deliveryAttempt");

            // 核心判据：lagMs 必须覆盖「事件发生 → 新进程启动」这一段。
            // 事件是停机期间发的，最早也只能被新进程消费，所以它不可能小于这个下界；
            // 反过来说，若某天有人把「先发事件再停」的顺序写反了，这一条会立刻红。
            long lowerBound = restartAt - publishedAt;
            assertTrue(lagMs >= lowerBound,
                    "lagMs=" + lagMs + "ms 小于「重启时刻 − 事件时刻」=" + lowerBound
                            + "ms——这条事件不可能是停机期间发的那一条：" + consumeLine);

            System.out.printf("[IT-RESTART] 停机耗时=%dms 停机窗口=%dms 下界=%dms 实际lagMs=%dms deliveryAttempt=%s%n",
                    stopMillis, DOWN_WINDOW.toMillis(), lowerBound, lagMs, deliveryAttempt);
        }
    }
}
