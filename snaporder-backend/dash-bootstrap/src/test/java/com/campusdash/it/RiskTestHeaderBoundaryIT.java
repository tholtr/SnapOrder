package com.campusdash.it;

import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.infrastructure.risk.DeviceHasher;
import com.campusdash.infrastructure.risk.RiskKeys;
import com.campusdash.infrastructure.risk.RiskProperties;
import com.campusdash.presentation.risk.RiskContextResolver;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试头安全边界的端到端证据（P9.5 决策 D2）。
 *
 * <p>{@code X-Test-Device-Id} / {@code X-Test-Client-Ip} 是本阶段唯一的后门：压测要用设备与 IP
 * 维度造聚集，而本机的真实 {@code remoteAddr} 恒为 {@code 127.0.0.1}、真实设备号又只有前端会发，
 * 不注入就造不出「同一设备 N 个账号」这类场景。**后门能开，就必须同时证明它关得上。**
 *
 * <h2>为什么必须有这条 IT</h2>
 *
 * <p>{@code RiskContextInterceptorTest} 是单测：它手工 new 一个拦截器、手工喂一个
 * {@code Environment}，证明的是**那段逻辑**。它证明不了「装配起来之后默认配置真的不认这个头」——
 * 而这正是安全边界的形状。把 {@code test-context-enabled} 写错一个字母（历史笔记里就出现过
 * {@code test-context-enable} 这个笔误）、或者有人把 yaml 里的
 * {@code ${DASH_RISK_TEST_CONTEXT_ENABLED:false}} 的默认值写成 {@code true}，
 * 单测全绿而线上开始认头。本类走真 HTTP、真上下文、真拦截器，那两种改法都会当场红。
 *
 * <h2>为什么是三个上下文，不是两个</h2>
 *
 * <p>闸门是「配置开关 <b>与</b> {@code test} profile 同时成立」（{@code RiskContextInterceptor:47}），
 * 所以「开关打开」单独一个上下文说明不了什么。更要紧的是：**IT 里 {@code test} profile 从来不激活**——
 * 本仓库没有任何一处设置 {@code spring.profiles.active}（测试 yaml、根 pom、各模块 pom 都没有，
 * 本类是全仓第一个用 {@code @ActiveProfiles} 的测试）。于是「开关打开」那个上下文同时也是
 * 「profile 这一半在挡着」的证据，而反证（开关打开 <b>且</b> profile 激活时头真的生效）需要第三个上下文。
 * 三个合起来才把「两个条件相乘」这件事钉住：
 *
 * <table>
 *   <tr><th>上下文</th><th>配置开关</th><th>{@code test} profile</th><th>预期</th></tr>
 *   <tr><td>一、{@code DefaultOff}</td><td>false（yaml 默认）</td><td>不激活</td><td>忽略</td></tr>
 *   <tr><td>二、{@code SwitchOnWithoutTestProfile}</td><td>true</td><td>不激活</td><td>忽略</td></tr>
 *   <tr><td>三、{@code SwitchOnWithTestProfile}</td><td>true</td><td>激活</td><td><b>生效</b></td></tr>
 * </table>
 *
 * <p>只做一、三两个上下文也能验「相乘」，但那样二这个上下文消失之后，
 * 「开关打开、profile 没开」这条真实存在的组合（本地调试时最常见的形态）就没有覆盖了——
 * 而它恰恰是最容易被人顺手改成「只判开关」的那一处。
 *
 * <h2>判据为什么落在 Redis 键上，不在日志行上</h2>
 *
 * <p>设计 §4.3 原本写的是「断 {@code risk_feature} 行的 {@code deviceAccounts} / {@code ipAccounts}」，
 * 外加「断 Redis 里没有这一类键」。**前半条现在不可用**：{@code risk_feature} 这一行整个没有生产者
 * （{@code RiskFeatureLog.emit} 无调用点，见 F-33 / D-7），本轮又不改生产代码。剩下来的 Redis 键
 * 判据本来就是更强的那一半——原设计里两条并列的理由正是「没有第二条，{@code deviceAccounts=1}
 * 可能是因为压根没采集」，而「键集合里有没有」直接就是那个「有没有采集」，不经过任何日志格式的转述。
 *
 * <p>四个键的分工：设备键与测试 IP 的两个键都是**本类专用**的（{@code TEST_DEVICE} / {@code TEST_IP}
 * 只在这个文件里出现），所以「在不在」是干净的判据；用户窗口键是**反证**——本次抢单必须真的走过采集，
 * 否则上面那几条「没有键」可能只是整条链路没跑而绿，那种绿最难发现（{@code RiskGrabLinkIT} 的同一条规矩）。
 *
 * <p>真实 IP {@code 127.0.0.1} 的键**不作为判据**：它与别的 IT 共用，在不在取决于刚才有没有人跑过，
 * 断言它等于断言「Redis 干净不干净」而不是「这次调用写了什么」——{@code RiskFeatureIT} 在
 * {@code riskKeysAddedBy} 的注释里记过这个坑。
 *
 * <h2>为什么不覆盖 {@code mode}，也不登记 {@code BenchRun}</h2>
 *
 * <p>{@code mode} 保持测试 yaml 的 {@code SHADOW}：本类要断的是「采不采」，与拦不拦无关，
 * 而 {@code ENFORCE} 会把本机 {@code 127.0.0.1} 上所有 IT 共用的 IP 速率窗口拉进判据——
 * 别的测试跑得多一点就可能把这几条抢单判成 {@code LIMIT}，变成一条与本事无关的偶发红。
 * 不登记 {@code BenchRun} 与 {@code RiskPreCheckIT} 同型：不跑并发、不校验超卖。
 *
 * <p>{@code deadline-ms} 覆盖成 1000ms 是必须的（照 {@code RiskGrabLinkIT.Shadow} 的先例）：
 * 默认 10ms 下新上下文的第一个请求光是连接池冷启动就要 15ms，采集整段被跳过，
 * 于是「测试头生效」那条会因为采集压根没跑而失败，而那与测试头的语义毫无关系。
 */
class RiskTestHeaderBoundaryIT {

    /** 本类私有的 id 段（其它 IT 用 910_xxx / 920_xxx / 930_xxx / 940_xxx / 950_xxx）。 */
    private static final long USER_BASE = 960_000L;

    /** 本类专用的测试设备号。只在这个文件里出现，所以「设备键在不在」是干净的判据。 */
    private static final String TEST_DEVICE = "it-p95-test-header-device";

    /** {@code TEST-NET-3}（RFC 5737 的文档用网段），永远不可能是真实客户端。 */
    private static final String TEST_IP = "203.0.113.77";

    // ── 共用工具 ────────────────────────────────────────────────────────────
    //
    // 全部写成外层类的 static 方法、把 bean 当参数传：Java 的 @Nested 非静态内部类
    // 不能声明静态成员（与 RiskGrabLinkIT 同型）。三个上下文各写一份会把
    // 「清哪些键」这件事抄三遍，而那正是最容易改一处漏一处的地方。

    /**
     * 清掉本类会碰到的五个键：用户的两个窗口 + 本类专用设备键 + 专用测试 IP 的两个键。
     *
     * <p>必须在**每次抢单之前**清，而不是收尾清：三个上下文故意共用同一个设备号与测试 IP，
     * 谁的用例先跑都行（JUnit 不保证 {@code @Nested} 之间的顺序），
     * 「先清再断」让每个用例的判据只取决于**它自己那一次**抢单。
     */
    private static void purge(StringRedisTemplate redis, RiskProperties props,
                              DeviceHasher hasher, long userId) {
        redis.delete(List.of(
                RiskKeys.userWindow(userId, props.userWindow().shortSeconds()),
                RiskKeys.userWindow(userId, props.userWindow().longSeconds()),
                RiskKeys.deviceCluster(hasher.hash(TEST_DEVICE)),
                RiskKeys.ipAccounts(hasher.hash(TEST_IP)),
                RiskKeys.ipRate(hasher.hash(TEST_IP))));
    }

    /** 带两个测试头的真 HTTP 抢单。请求头必须构造，不能用 {@code ItFixtures.grabRequest}（它只认 X-Request-Id）。 */
    private static Map<?, ?> grabWithTestHeaders(TestRestTemplate rest, long errandId, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ItFixtures.login(rest, userId));
        headers.set(RiskContextResolver.TEST_DEVICE_HEADER, TEST_DEVICE);
        headers.set(RiskContextResolver.TEST_IP_HEADER, TEST_IP);

        var resp = rest.exchange("/api/errands/" + errandId + "/grab", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class);
        assertEquals(200, resp.getStatusCode().value(), "抢单在 HTTP 层就失败了：" + resp.getBody());
        return resp.getBody();
    }

    /**
     * 抢单一次，断三维度。{@code honoured} 是**预期**：测试头该不该被认。
     *
     * <p>真 IP（{@code 127.0.0.1}）那一维不在此列，理由见类注释。
     */
    private static void runScenario(TestRestTemplate rest, StringRedisTemplate redis, RiskProperties props,
                                    DeviceHasher hasher, JdbcTemplate jdbc,
                                    PublishErrandUseCase publishUseCase, long userId, boolean honoured) {
        ItFixtures.resetPublisherBalance(jdbc);
        purge(redis, props, hasher, userId);
        long errandId = ItFixtures.publishSlots(publishUseCase, 1);

        Map<?, ?> body = grabWithTestHeaders(rest, errandId, userId);
        assertEquals("OK", body.get("code"), "这个用户是干净的，风控不该拦他：" + body);

        String deviceKey = RiskKeys.deviceCluster(hasher.hash(TEST_DEVICE));
        String ipAccountsKey = RiskKeys.ipAccounts(hasher.hash(TEST_IP));
        String ipRateKey = RiskKeys.ipRate(hasher.hash(TEST_IP));
        String windowKey = RiskKeys.userWindow(userId, props.userWindow().shortSeconds());

        // 反证：本次抢单真的走过采集。少了它，下面那几条「没有键」在
        // 「整条链路压根没跑」的世界里同样全绿——而那种绿最难发现。
        assertTrue(redis.hasKey(windowKey),
                "本次抢单没写用户窗口键 " + windowKey + "，采集整段没跑，"
                        + "后面「测试头没有生效」的结论是空的");

        if (honoured) {
            assertTrue(redis.hasKey(deviceKey), "测试头生效时必须建出设备键 " + deviceKey);
            assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(deviceKey, String.valueOf(userId))),
                    "设备键里必须有这个 userId " + userId + "；只有键存在证明不了它认的是哪个账号");
            assertTrue(redis.hasKey(ipAccountsKey), "测试头生效时必须建出 IP 账号键 " + ipAccountsKey);
            assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(ipAccountsKey, String.valueOf(userId))),
                    "IP 账号键里必须有这个 userId " + userId);
            assertTrue(redis.hasKey(ipRateKey), "测试头生效时必须建出 IP 速率键 " + ipRateKey);
        } else {
            assertFalse(redis.hasKey(deviceKey),
                    "测试头没生效时不该建出设备键 " + deviceKey
                            + "——它存在说明这道闸在装配之后是开着的，而单测看不见这一点");
            assertFalse(redis.hasKey(ipAccountsKey),
                    "测试头没生效时不该建出 IP 账号键 " + ipAccountsKey);
            assertFalse(redis.hasKey(ipRateKey),
                    "测试头没生效时不该建出 IP 速率键 " + ipRateKey);
        }

        System.out.printf("[IT-HEADER] 测试头%s device=%s ipUsers=%s ipRate=%s window=%s%n",
                honoured ? "生效" : "被忽略",
                redis.hasKey(deviceKey), redis.hasKey(ipAccountsKey),
                redis.hasKey(ipRateKey), redis.hasKey(windowKey));
    }

    // ── 上下文一：默认配置（yaml 的 test-context-enabled: false） ────────────

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "dash.risk.deadline-ms=1000",
                    "dash.mq.enabled=false",
                    "dash.credit.max-ongoing=10000"})
    class DefaultOff {

        @Autowired TestRestTemplate rest;
        @Autowired StringRedisTemplate redis;
        @Autowired RiskProperties props;
        @Autowired DeviceHasher hasher;
        @Autowired JdbcTemplate jdbc;
        @Autowired PublishErrandUseCase publishUseCase;

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(),
                    "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        }

        @Test
        @DisplayName("默认配置（开关 false）：带着测试头抢单，设备与 IP 维度都不认这两个头")
        void defaultConfigIgnoresTestHeaders() {
            runScenario(rest, redis, props, hasher, jdbc, publishUseCase, USER_BASE, false);
        }
    }

    // ── 上下文二：开关打开，但 test profile 没激活 ──────────────────────────

    /**
     * 这是最容易被改坏的那一处：把 `test-context-enabled: true` 当成「打开测试头」的完整条件。
     *
     * <p>本地调试时打开这个开关是常规操作（压测环境也要打开），所以「开关是 true」在真实世界里
     * 出现的频率很高。若闸门只剩开关这一半，这个上下文就会建出设备键、本用例当场红。
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "dash.risk.test-context-enabled=true",
                    "dash.risk.deadline-ms=1000",
                    "dash.mq.enabled=false",
                    "dash.credit.max-ongoing=10000"})
    class SwitchOnWithoutTestProfile {

        @Autowired TestRestTemplate rest;
        @Autowired StringRedisTemplate redis;
        @Autowired RiskProperties props;
        @Autowired DeviceHasher hasher;
        @Autowired JdbcTemplate jdbc;
        @Autowired PublishErrandUseCase publishUseCase;

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(),
                    "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        }

        @Test
        @DisplayName("开关打开但 test profile 未激活：测试头仍然被忽略（闸门是两者相与）")
        void switchAloneIsNotEnough() {
            runScenario(rest, redis, props, hasher, jdbc, publishUseCase, USER_BASE + 1, false);
        }
    }

    // ── 上下文三：开关打开 + test profile 激活（反证） ──────────────────────

    /**
     * 没有这个上下文，上面两条在「测试头功能整个没实现」的世界里同样全绿——
     * 而那正是「默认配置下头被忽略」最廉价的假绿：把功能删掉，边界用例反而更绿了。
     *
     * <p>{@code @ActiveProfiles("test")} 在这里只做一件事：让
     * {@code environment.acceptsProfiles(Profiles.of("test"))} 为真。全仓唯一读它的地方就是
     * {@code RiskContextInterceptor}，测试 yaml 里也没有 {@code on-profile} 块，
     * 所以激活它不会顺带改变别的配置——这一点是本类敢用它的前提。
     */
    @Nested
    @ActiveProfiles("test")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "dash.risk.test-context-enabled=true",
                    "dash.risk.deadline-ms=1000",
                    "dash.mq.enabled=false",
                    "dash.credit.max-ongoing=10000"})
    class SwitchOnWithTestProfile {

        @Autowired TestRestTemplate rest;
        @Autowired StringRedisTemplate redis;
        @Autowired RiskProperties props;
        @Autowired DeviceHasher hasher;
        @Autowired JdbcTemplate jdbc;
        @Autowired PublishErrandUseCase publishUseCase;

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(),
                    "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        }

        @Test
        @DisplayName("开关打开且 test profile 激活：同一个测试头确实落到了设备与 IP 维度上")
        void testHeadersTakeEffectOnlyWhenBothHalvesHold() {
            runScenario(rest, redis, props, hasher, jdbc, publishUseCase, USER_BASE + 2, true);
        }
    }
}
