package com.campusdash.it;

import com.campusdash.application.risk.RiskDecisionLog;
import com.campusdash.application.usecase.GrabErrandUseCase;
import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.infrastructure.risk.DeviceHasher;
import com.campusdash.infrastructure.risk.RiskKeys;
import com.campusdash.infrastructure.risk.RiskProperties;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P9.3 的端到端验收证据：**风控真的接在抢单链路上**，且三种运行模式各自行为正确。
 *
 * <p>本类是「同步接入」这个词唯一能被证伪的地方。P9.1/P9.2 的用例全部直接调
 * {@code RiskPreCheckUseCase}——它们能证明风控自己算得对，<b>证明不了它被接上了</b>。
 *
 * <h2>「没进入后续链路」的判据为什么是幂等键</h2>
 *
 * <p>{@code errand:idem:{errandId}:{requestId}} 由 {@code grab.lua} 写下。
 * 它不存在，就等于「这次请求从未进过抢单 Redis」——比对着名额数或 {@code grab_record}
 * 断言更直接，因为那两个字段在别的路径上也可能被动过，而幂等键只有 Lua 会写。
 * 它与 §8.1 的三个计数器断言互补：单测证明调用面，IT 证明落点。
 *
 * <p>反过来说，**每条「没留下键」的断言旁边都配了一条「该留下的键留下了」的断言**
 * （比如突发场景里第一次抢中的幂等键必须在）。没有它，上面那条可能只是因为整条链路
 * 压根没跑而变绿——而那种绿是最难发现的。
 *
 * <h2>三个 {@code @Nested} 上下文</h2>
 *
 * <p>与 {@code AuthModeIT} 同型：外层不挂 {@code @SpringBootTest}，每个 {@code @Nested}
 * 自带 properties，Spring 建三个独立上下文。{@code ENFORCE} 那个额外用
 * {@code RANDOM_PORT}——它有一条用例必须走真实 HTTP（D4 的回归锁），
 * 因为 {@code requestId} 的解析缺陷整个活在 controller 与拦截器之间，直接调用例会绕过它。
 *
 * <p>Java 的 {@code @Nested} 非静态内部类不能声明静态成员，所以共用工具一律写成外层类的
 * {@code private static} 方法、把 bean 当参数传。重复三份不如多传一个参数。
 */
class RiskGrabLinkIT {

    /** 本类私有的 id 段（其它 IT 用 910_xxx / 920_xxx / 940_xxx / 950_xxx）。 */
    private static final long USER_BASE = 930_000L;

    // ── 共用工具 ────────────────────────────────────────────────────────────
    //
    // idemKey / publishSlots / resetPublisherBalance / purgeUser / login / grabRequest
    // 六个动作已搬到 ItFixtures（RiskEventIT 要用同一批），调用点全部写成 ItFixtures.xxx。
    // P9.4 之前它们是本类私有的；提出来是为了**不被复制第二份**——
    // 幂等键格式这种「镜像实现细节」一旦有两份，改一处漏一处是迟早的事。
    //
    // 本类只此一家用的三个留在下面：它们贴着本类要验的东西（风控窗口键、errand 表），
    // 搬进 ItFixtures 只会把它变成杂物间。

    /** 当前库里全部用户窗口键。「降级时一个都不该新增」这条断言靠它。 */
    private static Set<String> windowKeys(StringRedisTemplate redis) {
        return new HashSet<>(redis.keys("risk:win:*"));
    }

    /** {@code before} 之后新增的窗口键。**只看新增不看消失**：别的键可能刚好到期。 */
    private static Set<String> addedSince(StringRedisTemplate redis, Set<String> before) {
        Set<String> added = windowKeys(redis);
        added.removeAll(before);
        return added;
    }

    private static Long grabberId(JdbcTemplate jdbc, long errandId) {
        return jdbc.queryForObject("SELECT grabber_id FROM errand WHERE id = ?", Long.class, errandId);
    }

    // ── 上下文一：ENFORCE ───────────────────────────────────────────────────

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "dash.risk.mode=ENFORCE",
                    "dash.mq.enabled=false",
                    "dash.credit.max-ongoing=10000"})
    class Enforce {

        @Autowired GrabErrandUseCase grabUseCase;
        @Autowired PublishErrandUseCase publishUseCase;
        @Autowired StringRedisTemplate redis;
        @Autowired RiskProperties props;
        @Autowired DeviceHasher hasher;
        @Autowired JdbcTemplate jdbc;
        @Autowired TestRestTemplate rest;

        /** 本用例写过的黑名单键，收尾删掉——它没有 TTL，留着会污染共享库。 */
        private final List<String> blacklistKeys = new ArrayList<>();

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(),
                    "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        }

        @BeforeEach
        void reset() {
            ItFixtures.resetPublisherBalance(jdbc);
        }

        @AfterEach
        void removeBlacklistKeys() {
            if (!blacklistKeys.isEmpty()) {
                redis.delete(blacklistKeys);
            }
        }

        @Test
        @DisplayName("高频用户在抢单 Redis 之前被拦：最后一次返回 RISK_LIMITED，且它没留下幂等键")
        void burstIsRejectedBeforeGrabRedis() {
            long userId = USER_BASE;
            ItFixtures.purgeUser(redis, props, hasher, userId, null);
            long errandId = ItFixtures.publishSlots(publishUseCase, 1);

            // 次数由配置推导（shortThreshold + 1）而不是写死 6：阈值是可配的，
            // 写死会让用例在改配置之后变成「跑完了但什么都没触发」，而它看起来仍然是绿的。
            int attempts = props.userWindow().shortThreshold() + 1;
            GrabErrandUseCase.Result last = null;
            for (int i = 0; i < attempts; i++) {
                last = grabUseCase.grab(new GrabErrandUseCase.Command(errandId, userId, "burst-" + i));
                if (i < attempts - 1) {
                    assertNotEquals(ErrorCode.RISK_LIMITED, last.code(),
                            "第 " + (i + 1) + " 次不该被风控拦下——窗口还没越线，风控不该提前介入");
                }
            }

            assertFalse(last.grabbed());
            assertEquals(ErrorCode.RISK_LIMITED, last.code(),
                    "最后一次必须在抢单链路之前被风控拦下");

            // 「没进抢单 Redis」的判据（见类注释）。
            assertFalse(redis.hasKey(ItFixtures.idemKey(errandId, "burst-" + (attempts - 1))),
                    "被风控拦下的请求不该在抢单 Redis 上留下幂等键");
            // 反证：第一次抢中的幂等键必须在。少了它，上面那条可能只是因为整条链路
            // 压根没跑而变绿，而那种绿最难发现。
            assertTrue(redis.hasKey(ItFixtures.idemKey(errandId, "burst-0")),
                    "第一次抢中必须写下幂等键，否则「最后一次没有」证明不了任何事");
            // LIMIT 的语义是「这一次拒绝 + 接下来冷却一段」（第 6 步不受预算管辖），
            // 所以限频键必须被写上；只拒一次不留冷却等于用户立刻能再来一次。
            assertTrue(redis.hasKey(RiskKeys.limitKey(userId)), "LIMIT 必须写下限频键");
        }

        @Test
        @DisplayName("黑名单用户不进入后续抢单链路：RISK_BLOCKED，任务仍无人认领")
        void blacklistedUserNeverReachesGrabRedis() {
            long userId = USER_BASE + 1;
            ItFixtures.purgeUser(redis, props, hasher, userId, null);
            long errandId = ItFixtures.publishSlots(publishUseCase, 1);
            String requestId = "bl-" + userId;

            redis.opsForSet().add(RiskKeys.blacklistUser(userId), "1");
            blacklistKeys.add(RiskKeys.blacklistUser(userId));

            var result = grabUseCase.grab(new GrabErrandUseCase.Command(errandId, userId, requestId));

            assertFalse(result.grabbed());
            assertEquals(ErrorCode.RISK_BLOCKED, result.code());
            assertNull(result.candidateRank(), "被风控拒绝不是抢输了，不该进候选队列");
            assertFalse(redis.hasKey(ItFixtures.idemKey(errandId, requestId)));
            assertNull(grabberId(jdbc, errandId), "任务不该被拉黑用户抢走");
        }

        @Test
        @DisplayName("设备键被建成字符串：只降级不拦，抢单照常成功")
        void wrongTypeOnDeviceKeyDegradesButDoesNotBlock() {
            long userId = USER_BASE + 2;
            String device = "it-p93-wrongtype-device";
            ItFixtures.purgeUser(redis, props, hasher, userId, device);
            long errandId = ItFixtures.publishSlots(publishUseCase, 1);
            String requestId = "wrongtype-" + userId;

            // 把这个键手工建成 string：risk_cluster.lua 的 SADD 会报 WRONGTYPE，
            // 适配器抛异常 → 记 CLUSTER_FAILED → 决策照常由剩余证据给出（这里是 PASS）。
            // 这是「键类型错误只适用于特征采集」那句话唯一真实可诱发的证据——
            // 不需要 mock 也不需要断连，SET 一个字符串即可。
            String deviceKey = RiskKeys.deviceCluster(hasher.hash(device));
            redis.opsForValue().set(deviceKey, "not-a-set");

            GrabErrandUseCase.Result result;
            try {
                result = grabUseCase.grab(new GrabErrandUseCase.Command(
                        errandId, userId, requestId, 60, device, null));
            } finally {
                // 收尾在 finally 里：断言失败时后面的语句不执行，而那正是最可能留下垃圾键的一次。
                redis.delete(deviceKey);
            }

            assertTrue(result.grabbed(),
                    "键类型错误只该降级，不该拦住抢单——设备维度没了，别的证据还在");
            assertTrue(redis.hasKey(ItFixtures.idemKey(errandId, requestId)),
                    "降级之后请求必须真的走到了抢单 Redis");
        }

        @Test
        @DisplayName("两条链路的 requestId 同值：风控窗口成员与抢单幂等键指向同一个字符串")
        void bothLinksShareTheSameRequestId() {
            // 这一条**必须不带 X-Request-Id 头**。带上显式头时两条链路本来就同值
            // （P9.3 之前也是），那样测不出任何东西；D4 修的是「头缺失/空白时
            // 两个调用点各生成一个 UUID」。而按实施计划 §9.4，本轮前端根本不发这个头，
            // 也就是**这条分支覆盖的正是全部真实流量**。
            long userId = USER_BASE + 3;
            ItFixtures.purgeUser(redis, props, hasher, userId, null);
            long errandId = ItFixtures.publishSlots(publishUseCase, 1);

            String token = ItFixtures.login(rest, userId);
            var resp = rest.exchange("/api/errands/" + errandId + "/grab", HttpMethod.POST,
                    ItFixtures.grabRequest(token, null), Map.class);

            assertEquals(200, resp.getStatusCode().value());
            assertEquals("OK", resp.getBody().get("code"), "抢单应成功：" + resp.getBody());

            // 幂等键是服务端生成的那个 UUID；把它取出来，再去风控窗口里找它。
            Set<String> idemKeys = redis.keys("errand:idem:{" + errandId + "}:*");
            assertNotNull(idemKeys);
            assertEquals(1, idemKeys.size(), "本次抢单应当留下且只留下一个幂等键：" + idemKeys);
            String generated = idemKeys.iterator().next().substring(ItFixtures.idemKey(errandId, "").length());

            assertNotNull(redis.opsForZSet().score(
                            RiskKeys.userWindow(userId, props.userWindow().shortSeconds()), generated),
                    "风控窗口的去重成员里必须有同一个 requestId=" + generated
                            + "；不在这里就说明两条链路各生成了一个 UUID，"
                            + "「这条风控决策对应哪一次抢单」在数据上无法回答");
        }
    }

    // ── 上下文二：SHADOW ────────────────────────────────────────────────────

    /**
     * SHADOW 的定义是「计算并记录风险结果，但不拦截」（蓝图 §3.10），
     * 所以这个上下文要同时钉住两件事，**少一件这个模式就没被验证**：
     *
     * <ul>
     *   <li>业务结果与没有风控时一模一样（全部抢中）</li>
     *   <li>决策**真的算了**（存在非 PASS 的动作）——否则「SHADOW 全放行」与
     *       「风控关掉」在行为上无法区分，而观察一段真实流量再决定要不要切 ENFORCE
     *       正是 SHADOW 存在的理由</li>
     * </ul>
     */
    @Nested
    @SpringBootTest(properties = {
            "dash.risk.mode=SHADOW",
            // 预算从默认的 10ms 放宽到 1s。**必须放宽**，且原因不是「让它更容易过」：
            // 本上下文要断言的是「设备集合真的攒到了阈值之上」，而集合的写入在
            // 10ms 预算下是尽力而为的。实测新上下文里的第一个请求耗时 15ms
            // （连接池冷启动），整段采集被跳过，集合少一个成员，
            // 断言随之失败——而那次失败与 SHADOW 的语义毫无关系。
            // 预算行为有自己的专门用例（ZeroBudgetFailClosed 与
            // RiskDegradeConcurrencyIT），不该在这里被顺带检验。
            "dash.risk.deadline-ms=1000",
            "dash.mq.enabled=false",
            "dash.credit.max-ongoing=10000"})
    class Shadow {

        @Autowired GrabErrandUseCase grabUseCase;
        @Autowired PublishErrandUseCase publishUseCase;
        @Autowired StringRedisTemplate redis;
        @Autowired RiskProperties props;
        @Autowired DeviceHasher hasher;
        @Autowired JdbcTemplate jdbc;

        /** 抓 {@code risk_decision} 行。挂上/摘掉由下面的两个方法成对负责。 */
        private LogCapture capture;

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(),
                    "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        }

        @BeforeEach
        void reset() {
            ItFixtures.resetPublisherBalance(jdbc);
        }

        /**
         * 挂一个内存 appender 抓 {@code risk_decision} 行。
         *
         * <p>决策日志是 SLF4J 输出，没有别的观测面——而本上下文要断言的正是那一行。
         * {@code RiskDecisionLog} 的 logger 与 Spring 应用是同一个（同 JVM 同 SLF4J 绑定），
         * 所以这样抓到的是真实应用写出来的行，不是测试自己拼的。
         * 格式本身由 {@code RiskDecisionLogTest} 逐字锁住，这里只读 {@code action} 字段。
         *
         * <p>挂/摘的细节搬到了 {@code LogCapture}（P9.4 起 {@code RiskEventIT} 也要抓日志，
         * 而那两处最容易抄错的是「先 stop 再 detach」的顺序）。
         */
        @BeforeEach
        void attachAppender() {
            capture = LogCapture.of(RiskDecisionLog.class);
        }

        @AfterEach
        void detachAppender() {
            capture.close();
        }

        private List<String> decisionLines() {
            return capture.lines("risk_decision");
        }

        @Test
        @DisplayName("SHADOW：同一场景每一次都抢中，但决策里确实算出了非 PASS 的动作")
        void shadowKeepsBusinessResultsButStillComputes() {
            // 场景：一台设备上出现 lowThreshold + 1 个账号，各抢一次。设备聚集规则
            // 会在最后一个账号上越线（DEVICE_ACCOUNTS_LOW → 60 分 → REVIEW）。
            //
            // 为什么是「每人一个任务」而不是「一个多名额任务、每人抢一次」——
            // 后者在本仓库做不到，实测第 2 个人拿到的是 GRAB_CONFLICT：
            // casLockForRunner 的 WHERE 带 status = 'PUBLISHED'，而它自己
            // SET status = 'LOCKED'，所以第一个人抢中之后任务就不再是 PUBLISHED，
            // 剩下的名额只能等超时回退（casRevertToPublished）才重新可抢。
            // 那是本阶段之外的一个既有缺陷（已记入交付记录），这里绕开它。
            //
            // 也不能用「同一用户连发」：第 2 次起就是 ALREADY_GRABBED，
            // 「每一次都成功抢到」根本无法成立。
            int accounts = props.deviceWindow().lowThreshold() + 1;
            String device = "it-p93-shadow-device";

            List<Long> users = new ArrayList<>();
            List<Long> errands = new ArrayList<>();
            for (int i = 0; i < accounts; i++) {
                users.add(USER_BASE + 100 + i);
                errands.add(ItFixtures.publishSlots(publishUseCase, 1));
            }
            // 全部清完再开跑：purgeUser 会连设备键一起清，边跑边清会把已经攒起来的
            // 账号数抹掉，规则再也越不了线，而用例仍然会绿（只是绿得没有意义）。
            for (long userId : users) {
                ItFixtures.purgeUser(redis, props, hasher, userId, device);
            }

            List<GrabErrandUseCase.Result> results = new ArrayList<>();
            for (int i = 0; i < accounts; i++) {
                results.add(grabUseCase.grab(new GrabErrandUseCase.Command(
                        errands.get(i), users.get(i), "shadow-" + i, 60, device, null)));
            }

            try {
                // 一、SHADOW 不改业务结果：每一次都抢中。
                for (int i = 0; i < results.size(); i++) {
                    assertTrue(results.get(i).grabbed(),
                            "SHADOW 下第 " + (i + 1) + " 次抢单必须成功，实际 " + results.get(i).code());
                }

                // 二、规则真的越线了。没有这一条，下面「算出了非 PASS」就成了无源之水——
                // 设备维度压根没采集的话，决策当然全是 PASS，而那说明不了 SHADOW 算过。
                Long deviceAccounts = redis.opsForSet().size(
                        RiskKeys.deviceCluster(hasher.hash(device)));
                assertNotNull(deviceAccounts);
                assertTrue(deviceAccounts > props.deviceWindow().lowThreshold(),
                        "设备聚集必须真的越线，实际账号数=" + deviceAccounts
                                + "，阈值=" + props.deviceWindow().lowThreshold());

                // 三、决策真的算了：存在非 PASS 的动作被记进 risk_decision 行。
                List<String> lines = decisionLines();
                assertFalse(lines.isEmpty(), "SHADOW 下也必须写出 risk_decision 行");
                assertTrue(lines.stream().map(l -> LogCapture.field(l, "action")).anyMatch(a -> a != null && !"PASS".equals(a)),
                        "存在「算了但没拦」的决策行才算 SHADOW 真的在算；实际行："
                                + lines.stream().map(l -> LogCapture.field(l, "action")).toList());
            } finally {
                // 设备键留着会影响后续运行（它是一个有 TTL 的 Set，但 600 秒内都在），
                // 断言失败也一样要清。
                redis.delete(RiskKeys.deviceCluster(hasher.hash(device)));
            }
        }
    }

    // ── 上下文三：ENFORCE + fail-open=false + deadline-ms=0 ─────────────────

    /**
     * 「按配置降级」的**拒绝侧**。放行侧由 {@code RiskDegradeConcurrencyIT} 承担
     * （那边是 {@code fail-open=true}）——设计 §10 明确要求两侧都要有，
     * 只测一侧证明不了「按配置」。
     *
     * <p>{@code deadline-ms=0} 是降级路径的纯配置触发方式（D11 的回归锁）：
     * 不需要 mock、不需要断连、不需要等超时。
     */
    @Nested
    @SpringBootTest(properties = {
            "dash.risk.mode=ENFORCE",
            "dash.risk.fail-open=false",
            "dash.risk.deadline-ms=0",
            "dash.mq.enabled=false",
            "dash.credit.max-ongoing=10000"})
    class ZeroBudgetFailClosed {

        @Autowired GrabErrandUseCase grabUseCase;
        @Autowired PublishErrandUseCase publishUseCase;
        @Autowired StringRedisTemplate redis;
        @Autowired RiskProperties props;
        @Autowired DeviceHasher hasher;
        @Autowired JdbcTemplate jdbc;

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(),
                    "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        }

        @BeforeEach
        void reset() {
            ItFixtures.resetPublisherBalance(jdbc);
        }

        @Test
        @DisplayName("预算为 0 + fail-open=false：每个请求都被 RISK_UNAVAILABLE 拒绝，且风控一步都没跑")
        void zeroBudgetRejectsWhenFailClosed() {
            long errandId = ItFixtures.publishSlots(publishUseCase, 1);
            Set<String> before = windowKeys(redis);

            for (int i = 0; i < 3; i++) {
                long userId = USER_BASE + 200 + i;
                ItFixtures.purgeUser(redis, props, hasher, userId, null);

                var result = grabUseCase.grab(new GrabErrandUseCase.Command(
                        errandId, userId, "zero-budget-" + i));

                assertFalse(result.grabbed());
                assertEquals(ErrorCode.RISK_UNAVAILABLE, result.code(),
                        "fail-open=false 时预算是 0，风控给不出结论，整次请求必须被拒");
            }

            // 第二条断言是「降级真的发生了」的反证。少了它，上面那三条可能只是把请求
            // 拦在了别处（信用、限流），用例照样绿，而它证明的东西完全不同。
            assertEquals(Set.of(), addedSince(redis, before),
                    "预算为 0 时一个采集端口都不该被调用，风控窗口键一个都不该新增");
            assertNull(grabberId(jdbc, errandId), "任务不该被任何人抢走");
        }
    }
}
