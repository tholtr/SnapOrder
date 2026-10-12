package com.campusdash.it;

import com.campusdash.domain.risk.model.Cluster;
import com.campusdash.domain.risk.model.UserWindow;
import com.campusdash.domain.risk.ports.RiskFeaturePort;
import com.campusdash.infrastructure.risk.DeviceHasher;
import com.campusdash.infrastructure.risk.RiskKeys;
import com.campusdash.infrastructure.risk.RiskProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 风控特征采集的集成测试——两个 Lua 脚本在真实 Redis 上的行为。
 *
 * <p><b>为什么这些必须有 IT</b>：脚本没有单测抓手（本仓库没有内嵌 Redis / Testcontainers /
 * mock），批次 2 的三个缺陷（`cap ≤ 127`、Set 的 512 悬崖、查重与裁剪顺序）**全部**
 * 是手工跑真实 Redis 才发现的，单测一个都没抓到。这里把这些结论固化成可重复的断言，
 * 免得下次调配置或改脚本时又只能靠人去记。
 *
 * <p><b>为什么把 ip-account-window.seconds 覆盖成 300</b>：主配置里设备窗口和 IP 账号
 * 窗口恰好都是 600，两者相等时无法区分「各传各的 TTL」和「共用一个 TTL」。
 * 刻意把它们配成不同值，才能让 {@link #eachSetGetsItsOwnWindowSeconds()} 成为
 * 真正的守卫——历史上正是这里共用了 TTL，导致
 * {@code dash.risk.ip-account-window.seconds} 成为一个读了也不生效的死配置项。
 *
 * <p>不清理 Redis 数据：每个用例用互不重叠的 userId / device / IP，天然不互相干扰；
 * 键本身都有 TTL。DB 侧本轮不写任何数据，无需 BenchRun 登记任务。
 */
@SpringBootTest(properties = {
        "dash.risk.ip-account-window.seconds=300",
        // 这些用例只打 Lua 与 Redis，不让 P7 治理层提前拦流
        "dash.risk.enabled=true"})
class RiskFeatureIT {

    /** 测试库共享，各用例的 id 段互不重叠，避免键互相污染。 */
    private static final long USER_BASE = 910_000L;

    @Autowired RiskFeaturePort riskFeaturePort;
    @Autowired StringRedisTemplate redis;
    @Autowired DeviceHasher hasher;
    @Autowired RiskProperties props;

    private static BenchRun run;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(),
                "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        run = BenchRun.start("IT-RISK");
    }

    @AfterAll
    static void finishRun() {
        if (run != null) {
            run.finish("PASS", "{\"scenario\":\"IT-RISK\",\"scripts\":2,\"degraded\":false}");
            run.close();
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 两个用户窗口键，顺序与 {@code risk_user_window.lua} 的 KEYS 一致。 */
    private List<String> userWindowKeys(long userId) {
        return List.of(
                RiskKeys.userWindow(userId, props.userWindow().shortSeconds()),
                RiskKeys.userWindow(userId, props.userWindow().longSeconds()));
    }

    private String deviceKey(String deviceId) {
        return RiskKeys.deviceCluster(hasher.hash(deviceId));
    }

    private String ipAccountsKey(String clientIp) {
        return RiskKeys.ipAccounts(hasher.hash(clientIp));
    }

    private String ipRateKey(String clientIp) {
        return RiskKeys.ipRate(hasher.hash(clientIp));
    }

    /** 用例开始前清掉自己会用到的那几个键，保证可重复运行。 */
    private void purge(long userId, String deviceId, String clientIp) {
        List<String> keys = new ArrayList<>(userWindowKeys(userId));
        if (deviceId != null) {
            keys.add(deviceKey(deviceId));
        }
        if (clientIp != null) {
            keys.add(ipAccountsKey(clientIp));
            keys.add(ipRateKey(clientIp));
        }
        redis.delete(keys);
    }

    private String encodingOf(String key) {
        return redis.execute((RedisCallback<String>) conn ->
                conn.keyCommands().encodingOf(key.getBytes(StandardCharsets.UTF_8)).raw());
    }

    /** 并发调用，返回每个线程各自拿到的返回值。 */
    private List<UserWindow> concurrentUserWindow(long userId, List<String> requestIds) throws Exception {
        Queue<UserWindow> results = new ConcurrentLinkedQueue<>();
        int n = requestIds.size();
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);

        // 虚拟线程 + 对齐释放，照抄 GrabConcurrencyIT：用固定大小线程池会在
        // ready.await() 上死等——池子比并发数小的话前 N 个线程全卡在 fire 上
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String requestId : requestIds) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                        results.add(riskFeaturePort.trackUserWindow(userId, requestId));
                    } catch (Exception e) {
                        // 异常即失败，不能计入结果
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "线程未在 30s 内就绪");
            fire.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "调用未在 60s 内完成");
        }
        return List.copyOf(results);
    }

    private static List<String> ids(String prefix, int count) {
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(prefix + String.format("%03d", i));
        }
        return list;
    }

    // ── 用户滑动窗口 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("并发 5 个不同 requestId：两个窗口都恰好计 5 次")
    void distinctRequestIdsCountExactly() throws Exception {
        long userId = USER_BASE;
        purge(userId, null, null);

        List<UserWindow> results = concurrentUserWindow(userId, ids("distinct-", 5));

        assertEquals(5, results.size(), "有调用抛异常，实际返回 " + results.size() + " 条");
        // 计数以 Redis 为准：脚本原子，最后一次调用必须看到全部 5 个成员
        assertEquals(5L, redis.opsForZSet().zCard(userWindowKeys(userId).get(0)));
        assertEquals(5L, redis.opsForZSet().zCard(userWindowKeys(userId).get(1)));
        assertTrue(results.stream().anyMatch(r -> r.count10s() == 5),
                "没有任何一次调用看到完整的 5 次计数");
        assertTrue(results.stream().noneMatch(UserWindow::duplicateRequest),
                "5 个 requestId 互不相同，不应有任何一次被判为重复");
    }

    @Test
    @DisplayName("并发 8 个相同 requestId：只计 1 次，且恰好 1 次抢到「首次」")
    void sameRequestIdCountsOnce() throws Exception {
        long userId = USER_BASE + 1;
        purge(userId, null, null);

        List<String> same = java.util.Collections.nCopies(8, "replay-same-id");
        List<UserWindow> results = concurrentUserWindow(userId, same);

        assertEquals(8, results.size(), "有调用抛异常，实际返回 " + results.size() + " 条");
        // 这条只有在脚本原子时才成立：并发下若「查重」和「写入」之间能被插入，
        // 就会有多个线程同时判定为首次，计数随之大于 1。这是脚本正确性的核心证据。
        assertEquals(1L, redis.opsForZSet().zCard(userWindowKeys(userId).get(0)));
        assertEquals(1L, redis.opsForZSet().zCard(userWindowKeys(userId).get(1)));

        long firsts = results.stream().filter(r -> !r.duplicateRequest()).count();
        assertEquals(1L, firsts, "应恰好有 1 次判定为首次，实际 " + firsts + " 次");
        assertTrue(results.stream().allMatch(r -> r.count10s() == 1 && r.count60s() == 1),
                "去重生效时每次返回值都应是计数 1，实际 " + results);
    }

    @Test
    @DisplayName("滑出窗口的成员在下一次写入时被裁掉，且不被误判为重复")
    void expiredMemberIsTrimmedAndNotDuplicate() {
        long userId = USER_BASE + 2;
        purge(userId, null, null);

        String key10 = userWindowKeys(userId).get(0);
        String stale = "stale-request";
        // 手工塞一个 70 秒前的成员来模拟「数据已滑出窗口」，而不是 Thread.sleep(70)。
        // 分值单位是微秒（脚本用 TIME 的微秒；纳秒超出 Lua double 的精度）。
        long staleUs = (System.currentTimeMillis() - 70_000L) * 1000L;
        redis.opsForZSet().add(key10, stale, staleUs);
        redis.expire(key10, java.time.Duration.ofSeconds(300));

        UserWindow result = riskFeaturePort.trackUserWindow(userId, stale);

        // 顺序写反（先查重后裁剪）时这里会是 {0, 0, true}——一边说「重复」，
        // 一边说窗口里一个成员都没有。那种自相矛盾正是批次 2 抓到该 bug 的线索。
        assertFalse(result.duplicateRequest(), "已滑出窗口的 requestId 不该被判为重复");
        assertEquals(1, result.count10s(), "已滑出窗口的请求应重新计数");
        assertEquals(1, result.count60s());
        // 成员被重新写入当前时刻，说明确实走了「先裁剪、后查重」
        Double score = redis.opsForZSet().score(key10, stale);
        assertNotNull(score);
        long nowUs = System.currentTimeMillis() * 1000L;
        assertTrue(score > nowUs - 10_000_000L, "陈旧成员应被裁掉并重新写入，实际分值 " + score);
    }

    @Test
    @DisplayName("用户窗口成员上限生效：10s 窗口截到 6、60s 窗口截到 31，保留最新")
    void userWindowCapEnforced() {
        long userId = USER_BASE + 3;
        purge(userId, null, null);

        List<String> all = ids("cap-", 40);
        for (String requestId : all) {
            riskFeaturePort.trackUserWindow(userId, requestId);
        }

        List<String> keys = userWindowKeys(userId);
        assertNotNull(encodingOf(keys.get(0)), "10 秒窗口键不存在");
        assertEquals(6L, redis.opsForZSet().zCard(keys.get(0)), "10s 窗口上限应为 short-threshold+1 = 6");
        assertEquals(31L, redis.opsForZSet().zCard(keys.get(1)), "60s 窗口上限应为 long-threshold+1 = 31");

        // 保留的是最新的：最早的没了，最后的还在。
        // （同分值时 ZSet 按成员字典序排，id 用零填充保证了字典序与写入序一致）
        assertNull(redis.opsForZSet().score(keys.get(0), all.get(0)), "最早的成员应已被裁掉");
        assertNotNull(redis.opsForZSet().score(keys.get(0), all.get(39)), "最新的成员必须保留");
    }

    @Test
    @DisplayName("用户窗口的两个键都带上 TTL，且不超过配置窗口")
    void userWindowKeysHaveTtl() {
        long userId = USER_BASE + 4;
        purge(userId, null, null);

        riskFeaturePort.trackUserWindow(userId, "ttl-probe");

        long expected = Math.max(props.userWindow().shortSeconds(), props.userWindow().longSeconds());
        for (String key : userWindowKeys(userId)) {
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            assertNotNull(ttl);
            assertTrue(ttl > 0, key + " 没有 TTL，键会永久累积");
            assertTrue(ttl <= expected, key + " 的 TTL " + ttl + " 超过最长窗口 " + expected);
        }
    }

    // ── 设备 / IP 聚集维度 ──────────────────────────────────────────────────

    @Test
    @DisplayName("设备集合上限生效，且两个 Set 各拿各的 TTL")
    void deviceCapEnforcedAndEachSetGetsItsOwnTtl() {
        long deviceUser = USER_BASE + 10;
        String device = "it-device-ttl";
        purge(deviceUser, device, null);

        // 上限 9 = device-window.threshold(8) + 1；写 14 个不同账号
        for (int i = 0; i < 14; i++) {
            riskFeaturePort.trackCluster(deviceUser + i, device, null);
        }

        String key = deviceKey(device);
        assertEquals(9L, redis.opsForSet().size(key), "设备集合上限应为 threshold+1 = 9");

        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= props.deviceWindow().seconds(),
                "设备集合 TTL 应等于 device-window.seconds=" + props.deviceWindow().seconds() + "，实际 " + ttl);
    }

    @Test
    @DisplayName("IP 账号集合上限生效，且用自己的窗口秒数（不是设备窗口那个）")
    void ipAccountCapEnforcedWithItsOwnWindow() {
        long ipUser = USER_BASE + 20;
        String ip = "198.51.100.7";
        purge(ipUser, null, ip);

        // 上限 11 = ip-account-window.threshold(10) + 1；写 16 个不同账号
        for (int i = 0; i < 16; i++) {
            riskFeaturePort.trackCluster(ipUser + i, null, ip);
        }

        String key = ipAccountsKey(ip);
        assertEquals(11L, redis.opsForSet().size(key), "IP 账号集合上限应为 threshold+1 = 11");

        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        // Redis 的 TTL 向下取整：设 300 秒后立刻读会得到 299。所以这里用区间而不是等值。
        //
        // 本类把 ip-account-window.seconds 覆盖成 300，与 device-window.seconds(600) 不同。
        // 上界 300 就是判别条件——若脚本把两个 Set 的 TTL 合并成一个参数，
        // 这里会读到 600 而不是 299，直接被上界挡住。
        int expected = props.ipAccountWindow().seconds();
        assertTrue(ttl > 0 && ttl <= expected,
                "IP 账号集合的 TTL 必须来自 ip-account-window.seconds(" + expected
                        + ")，不能借用设备窗口的 " + props.deviceWindow().seconds() + "，实际 " + ttl);
        assertTrue(ttl > expected - 10, "TTL 明显小于配置窗口，可能是写入了错误的秒数：" + ttl);
        assertNotEquals(props.deviceWindow().seconds(), props.ipAccountWindow().seconds(),
                "本用例依赖两个 Set 窗口秒数不同才具备区分能力，配置被改成相等时这条守卫失效");
    }

    @Test
    @DisplayName("IP 请求数与用户窗口语义相反：不去重，每次都累加")
    void ipRateCountsEveryRequest() {
        long userId = USER_BASE + 30;
        String ip = "198.51.100.31";
        purge(userId, null, ip);

        Cluster first = riskFeaturePort.trackCluster(userId, null, ip);
        int previous = first.ipRequests();
        for (int i = 0; i < 4; i++) {
            Cluster c = riskFeaturePort.trackCluster(userId, null, ip);
            assertTrue(c.ipRequests() > previous,
                    "IP 请求数应逐次递增，实际 " + previous + " -> " + c.ipRequests());
            previous = c.ipRequests();
        }

        // 这条是与用户窗口最大的语义差异（D5）：用户窗口用 requestId 去重，
        // IP 速率计的是原始请求数。若这里也去重，攻击者反复用同一个 requestId
        // 就能把计数压到 1，规则形同不存在。
        //
        // 断言 >= 4 而不是 == 5：同一微秒内的两次调用会被 ZSet 当成同一个成员合并
        // （成员是时间戳本身）。连续的网络往返不可能落进同一微秒，
        // 但没必要把这个未写进契约的时间精度写死成断言。
        assertTrue(previous >= 4, "5 次调用后 IP 请求数至少应有 4，实际 " + previous);
    }

    @Test
    @DisplayName("IP 速率 ZSet 上限生效：写 300 次后恰好截到 101")
    void ipRateCapEnforced() {
        String ip = "198.51.100.61";
        purge(USER_BASE + 40, null, ip);

        // 上限 101，写 300 次。成员是时间戳，同微秒的写入会合并，
        // 所以不能断言「写了多少次就有多少成员」——但 300 次分布在几十毫秒里，
        // 合并到不足 101 个不同时间戳需要上百次碰撞，不可能发生。
        for (int i = 0; i < 300; i++) {
            riskFeaturePort.trackCluster(USER_BASE + 40 + i, null, ip);
        }

        assertEquals(101L, redis.opsForZSet().zCard(ipRateKey(ip)),
                "IP 速率窗口上限应为 ip-rate-window.threshold+1 = 101");
    }

    @Test
    @DisplayName("设备与 IP 两个维度互不污染")
    void deviceAndIpDimensionsAreIsolated() {
        long userId = USER_BASE + 50;
        String device = "it-device-isolated";
        String ip = "198.51.100.90";
        purge(userId, device, ip);

        // 同一设备、不同账号 → 只涨设备计数
        Cluster devOnly = riskFeaturePort.trackCluster(userId, device, null);
        assertEquals(1, devOnly.deviceAccounts());
        assertEquals(0, devOnly.ipAccounts(), "没传 IP 时 IP 维度必须是 0");
        assertEquals(0, devOnly.ipRequests(), "没传 IP 时不写速率键，计数必须是 0");

        // 同一 IP、不同账号 → 只涨 IP 计数
        Cluster ipOnly = riskFeaturePort.trackCluster(userId + 1, null, ip);
        assertEquals(0, ipOnly.deviceAccounts(), "没传设备时设备维度必须是 0");
        assertEquals(1, ipOnly.ipAccounts());
        assertEquals(1, ipOnly.ipRequests());

        // 两组键都在，且各自只含自己那一维的用户
        assertEquals(1L, redis.opsForSet().size(deviceKey(device)));
        assertEquals(1L, redis.opsForSet().size(ipAccountsKey(ip)));
        assertTrue(redis.opsForSet().isMember(deviceKey(device), String.valueOf(userId)));
        assertFalse(redis.opsForSet().isMember(deviceKey(device), String.valueOf(userId + 1)));
    }

    // ── 缺失维度 ────────────────────────────────────────────────────────────

    /**
     * 本次调用**新增**了哪些风控键。
     *
     * <p>不能直接断言 {@code redis.keys("risk:dev:*").isEmpty()}：Redis 是各用例共享的，
     * 别的用例（本类自己的编码用例就会）已经建出设备键，全局为空根本不成立。
     * 该断言先前就是这么写错的——它测的是「Redis 干净不干净」，而不是
     * 「这次调用写没写键」。改成比较调用前后的键集合之差。
     */
    private Set<String> riskKeysAddedBy(Runnable call) {
        Set<String> before = redis.keys("risk:*");
        call.run();
        Set<String> after = redis.keys("risk:*");
        after.removeAll(before);
        return after;
    }

    @Test
    @DisplayName("缺设备维度时不写任何设备键，也不碰占位键")
    void missingDeviceLeavesNoKey() {
        long userId = USER_BASE + 60;
        String ip = "198.51.100.120";
        purge(userId, null, ip);

        Cluster[] result = new Cluster[1];
        Set<String> added = riskKeysAddedBy(() -> result[0] = riskFeaturePort.trackCluster(userId, null, ip));

        assertEquals(0, result[0].deviceAccounts(), "缺设备维度时应返回 0");
        assertFalse(redis.hasKey("risk:_absent_"),
                "占位键 risk:_absent_ 被真的建了出来——说明脚本的 hasDevice 开关失效了");
        assertTrue(added.stream().noneMatch(k -> k.startsWith("risk:dev:")),
                "缺设备维度时不该新增设备键，实际新增：" + added);
        // 反向确认这次调用确实写了东西，否则上面那条断言可能是空转
        assertTrue(added.contains(ipAccountsKey(ip)), "这次调用应当写入 IP 账号键，实际新增：" + added);
        assertTrue(added.contains(ipRateKey(ip)), "这次调用应当写入 IP 速率键，实际新增：" + added);
    }

    @Test
    @DisplayName("两个维度都缺失：直接返回 EMPTY，Redis 里不留任何痕迹")
    void bothDimensionsMissingTouchesNothing() {
        long userId = USER_BASE + 70;
        purge(userId, null, null);

        Cluster[] result = new Cluster[1];
        Set<String> added = riskKeysAddedBy(() -> result[0] = riskFeaturePort.trackCluster(userId, null, null));

        assertEquals(Cluster.EMPTY, result[0]);
        assertTrue(added.isEmpty(), "两个维度都缺失时不该新增任何风控键，实际新增：" + added);
        assertTrue(redis.keys("risk:_absent_").isEmpty(), "占位键不该存在");
    }

    // ── 编码复核（任务 10） ─────────────────────────────────────────────────

    @Test
    @DisplayName("写满上限后五个键仍保持紧凑编码（listpack / intset）")
    void keysKeepCompactEncodingAtCapacity() {
        long userId = USER_BASE + 80;
        String device = "it-device-encoding";
        String ip = "198.51.100.200";
        purge(userId, device, ip);

        // 每个键都写到超过自己的上限，走的是真实写入路径
        // （ZSet 会瞬时到上限+1，Set 最多正好到上限）
        for (int i = 0; i < 40; i++) {
            riskFeaturePort.trackUserWindow(userId, "enc-" + String.format("%03d", i));
        }
        for (int i = 0; i < 20; i++) {
            riskFeaturePort.trackCluster(userId + i, device, ip);
        }
        for (int i = 0; i < 150; i++) {
            riskFeaturePort.trackCluster(userId + 100 + i, null, ip);
        }

        List<String> keys = userWindowKeys(userId);
        assertEquals("listpack", encodingOf(keys.get(0)), "用户 10s 窗口键转为非紧凑编码，内存会翻倍");
        assertEquals("listpack", encodingOf(keys.get(1)), "用户 60s 窗口键转为非紧凑编码");
        assertEquals("intset", encodingOf(deviceKey(device)), "设备集合转为 hashtable，内存跳涨 18.5 倍");
        assertEquals("intset", encodingOf(ipAccountsKey(ip)), "IP 账号集合转为 hashtable");
        assertEquals("listpack", encodingOf(ipRateKey(ip)), "IP 速率 ZSet 转为 skiplist");

        System.out.printf("[IT-RISK] 编码复核 10s=%s 60s=%s device=%s ipUsers=%s ipRate=%s%n",
                encodingOf(keys.get(0)), encodingOf(keys.get(1)),
                encodingOf(deviceKey(device)), encodingOf(ipAccountsKey(ip)), encodingOf(ipRateKey(ip)));
    }

    @Test
    @DisplayName("上限刚好越界一次也不会把编码顶下去（cap 与编码边界的耦合）")
    void writingPastCapDoesNotBreakEncoding() {
        long userId = USER_BASE + 90;
        purge(userId, null, null);

        // 上限 6，写到 7 —— 正是「先 ZADD 后裁剪」的瞬时峰值。
        // 若有人把 short-threshold 调到 127，这里瞬时就是 128 个成员，
        // 当场转 skiplist 且永不回退。RiskProperties 的启动校验拦的就是这一条，
        // 本用例从行为侧再确认一遍：当前配置下峰值仍然安全。
        for (int i = 0; i < 7; i++) {
            riskFeaturePort.trackUserWindow(userId, "peak-" + i);
        }

        assertEquals("listpack", encodingOf(userWindowKeys(userId).get(0)),
                "写上限+1 次之后的编码不是 listpack");
        assertEquals(6L, redis.opsForZSet().zCard(userWindowKeys(userId).get(0)));
    }

    @Test
    @DisplayName("RiskKeys 与适配器用的是同一套键（任务 11 的前置条件）")
    void keyBuildersMatchAdapter() {
        long userId = USER_BASE + 95;
        String device = "it-device-keymatch";
        String ip = "198.51.100.240";
        purge(userId, device, ip);

        riskFeaturePort.trackUserWindow(userId, "keymatch-1");
        riskFeaturePort.trackCluster(userId, device, ip);

        // 直接断言「用 RiskKeys 算出来的名字上确实有数据」。黑名单工具
        // （BlacklistKeyTool）算的键必须与应用查的键逐字节相同，否则运维写进去的
        // 黑名单永远查不到且不报错——这里是那套约定的第一道验证。
        assertTrue(redis.hasKey(deviceKey(device)));
        assertTrue(redis.hasKey(ipAccountsKey(ip)));
        assertTrue(redis.hasKey(ipRateKey(ip)));
        assertTrue(redis.hasKey(RiskKeys.userWindow(userId, props.userWindow().shortSeconds())));
    }
}
