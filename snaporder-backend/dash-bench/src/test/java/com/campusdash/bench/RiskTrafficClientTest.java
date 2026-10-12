package com.campusdash.bench;

import com.campusdash.bench.RiskTrafficClient.Lane;
import com.campusdash.bench.RiskTrafficClient.Outcome;
import com.campusdash.bench.RiskTrafficClient.Profile;
import com.campusdash.bench.RiskTrafficClient.Request;
import com.campusdash.bench.RiskTrafficClient.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RiskTrafficClient} 的**纯逻辑**单测：不发一个请求。
 *
 * <p>压测客户端最麻烦的失败形态是**它自己不报错**。设备头拼错一个字母 → 设备维度静默消失；
 * 两个用户撞了同一个假 IP → 场景 4 的分母错了；对照组的设备被 4 个账号共用 →
 * 对照组自己命中规则，而现场看起来只是「命中率有点怪」。这些都不会抛异常，只会让
 * 报告里的数字变成另一件事的读数。所以这里断的全是「一旦坏了就没有任何征兆」的那些性质，
 * 而不是覆盖率。
 *
 * <p>网络与数据库不在这一层：真实链路的证据是压测现场本身（{@code bench/tmp/}）。
 */
class RiskTrafficClientTest {

    private static final long SEED = 2_100_000L;

    // ================================================================ 参数

    @Nested
    class Args {

        @Test
        @DisplayName("省略的并发与用户池取场景自己的默认值")
        void defaultsFollowTheScenario() {
            Profile normal = RiskTrafficClient.parse(new String[]{"normal"});
            assertEquals(Scenario.NORMAL, normal.scenario());
            assertEquals(200, normal.concurrency());
            assertEquals(60_000, normal.userPool());
            assertEquals("http://127.0.0.1:8080", normal.baseUrl());
            assertEquals(60, normal.durationSeconds());
            assertEquals(500, normal.taskPool());
            assertEquals(2_100_000L, normal.seed());

            // 热用户要 400 并发才压得满窗口；用户池只有几个账号才是「热用户」的含义。
            Profile hot = RiskTrafficClient.parse(new String[]{"hot-user"});
            assertEquals(400, hot.concurrency());
            assertEquals(4, hot.userPool());
        }

        @Test
        @DisplayName("显式参数盖过场景默认值")
        void explicitArgsWin() {
            Profile p = RiskTrafficClient.parse(new String[]{
                    "same-ip", "http://10.0.0.1:9090", "64", "30", "20", "2300000", "1234"});

            assertEquals(Scenario.SAME_IP, p.scenario());
            assertEquals("http://10.0.0.1:9090", p.baseUrl());
            assertEquals(64, p.concurrency());
            assertEquals(30, p.durationSeconds());
            assertEquals(20, p.taskPool());
            assertEquals(2_300_000L, p.seed());
            assertEquals(1234, p.userPool());
        }

        @Test
        @DisplayName("seed 必须落在压测号段 2_000_000+，否则当场拒绝")
        void refusesSeedBelowTheBenchRange() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> RiskTrafficClient.parse(new String[]{
                            "normal", "http://127.0.0.1:8080", "200", "60", "500", "1999999"}));

            assertTrue(e.getMessage().contains("2000000"),
                    "报错本身要写出为什么，否则现场只会看到「seed 不合法」：" + e.getMessage());
        }

        @Test
        @DisplayName("未知场景、非正数、空参数都当场拒绝")
        void refusesNonsense() {
            assertThrows(IllegalArgumentException.class,
                    () -> RiskTrafficClient.parse(new String[]{"hot_user"}));
            assertThrows(IllegalArgumentException.class,
                    () -> RiskTrafficClient.parse(new String[]{"normal", "http://x", "0"}));
            assertThrows(IllegalArgumentException.class,
                    () -> RiskTrafficClient.parse(new String[]{"normal", "http://x", "abc"}));
            assertThrows(IllegalArgumentException.class, () -> RiskTrafficClient.parse(new String[0]));
        }

        @Test
        @DisplayName("用法里写清了五个场景与两个默认值不一致的场景")
        void usageNamesEveryScenario() {
            String usage = RiskTrafficClient.usage();
            for (Scenario s : Scenario.values()) {
                assertTrue(usage.contains(s.name().toLowerCase().replace('_', '-')),
                        "usage 里少了场景 " + s);
            }
        }
    }

    // ================================================================ 主体取值

    @Nested
    class Subjects {

        @Test
        @DisplayName("每个池内用户拿到互不相同的假 IP")
        void everyPoolUserGetsItsOwnIp() {
            Set<String> ips = new HashSet<>();
            for (int i = 0; i < 60_000; i++) {
                assertTrue(ips.add(RiskTrafficClient.poolIp(i)),
                        "第 " + i + " 个用户的 IP 与前面的撞了（" + RiskTrafficClient.poolIp(i)
                                + "）——撞了就会有两个用户共用一份 risk:ip:* 计数，"
                                + "而现场只会表现为命中率不对");
            }
            assertTrue(RiskTrafficClient.poolIp(0).startsWith("198.18."),
                    "用户池的假 IP 要走 198.18.0.0/15（benchmark 保留段）");
        }

        @Test
        @DisplayName("四个 IP 号段互不重叠：池、对照、共享、黑名单")
        void ipRangesDoNotCollide() {
            Set<String> pool = new HashSet<>();
            for (int i = 0; i < 60_000; i++) {
                pool.add(RiskTrafficClient.poolIp(i));
            }
            for (int i = 0; i < 60_000; i++) {
                assertFalse(pool.contains(RiskTrafficClient.controlIp(i)),
                        "对照组的 IP 落进了用户池，两边的计数会互相污染");
                assertNotEquals(RiskTrafficClient.SHARED_IP, RiskTrafficClient.controlIp(i));
                assertNotEquals(RiskTrafficClient.BLACKLIST_IP, RiskTrafficClient.controlIp(i));
            }
            assertFalse(pool.contains(RiskTrafficClient.SHARED_IP),
                    "同 IP 场景共用的那个地址不能同时属于用户池——否则场景 4 的对照就没了");
            assertNotEquals(RiskTrafficClient.SHARED_IP, RiskTrafficClient.BLACKLIST_IP,
                    "共享 IP 与被拉黑 IP 不能同值，否则场景 4 的全部流量会被黑名单拦掉");
        }

        @Test
        @DisplayName("对照组设备恰好每 3 个账号换一个 —— 第 4 个就命中 DEVICE_ACCOUNTS_LOW")
        void controlDeviceIsSharedByExactlyThreeUsers() {
            String first = RiskTrafficClient.controlDeviceId(SEED, 0);

            assertEquals(RiskTrafficClient.CONTROL_USERS_PER_DEVICE, 3,
                    "这个常数是 DEVICE_ACCOUNTS_LOW 的阈值推出来的，改它之前先改规则");
            assertEquals(first, RiskTrafficClient.controlDeviceId(SEED, 1));
            assertEquals(first, RiskTrafficClient.controlDeviceId(SEED, 2));
            assertNotEquals(first, RiskTrafficClient.controlDeviceId(SEED, 3),
                    "第 4 个账号必须换设备：DEVICE_ACCOUNTS_LOW 在 deviceAccounts > 3 时命中，"
                            + "对照组自己触发规则就等于没有对照组");
            assertEquals(RiskTrafficClient.controlDeviceId(SEED, 3),
                    RiskTrafficClient.controlDeviceId(SEED, 5));
        }

        @Test
        @DisplayName("黑名单用户与预热主体都在用户池之外")
        void auxiliarySubjectsStayOutOfThePool() {
            int userPool = 8;
            String blacklisted = RiskTrafficClient.blacklistUserId(SEED, userPool);
            for (int i = 0; i < userPool; i++) {
                assertNotEquals(RiskTrafficClient.poolUserId(SEED, i), blacklisted,
                        "被拉黑的用户落在池子里，池里的正常流量会被一起拦掉");
            }

            String warm = RiskTrafficClient.warmUserId(SEED);
            for (int i = 0; i < userPool; i++) {
                assertNotEquals(RiskTrafficClient.poolUserId(SEED, i), warm);
            }
            assertNotEquals(blacklisted, warm);
            assertEquals(String.valueOf(SEED + 900_000L), warm,
                    "预热用户号段是固定的偏移，写进现场记录后要能按 seed 重新算出来");
        }

        @Test
        @DisplayName("三个设备号两两不同：共用 / 黑名单 / 预热")
        void deviceIdsAreDistinct() {
            assertNotEquals(RiskTrafficClient.sharedDeviceId(SEED),
                    RiskTrafficClient.blacklistDeviceId(SEED));
            assertNotEquals(RiskTrafficClient.sharedDeviceId(SEED),
                    RiskTrafficClient.warmDeviceId(SEED));
            // 不同 seed 之间也要分开：场景默认用同一个 seed 连跑时不能互相踩。
            assertNotEquals(RiskTrafficClient.sharedDeviceId(SEED),
                    RiskTrafficClient.sharedDeviceId(SEED + 1));
        }
    }

    // ================================================================ 车道

    @Nested
    class Lanes {

        @Test
        @DisplayName("只有黑名单场景分车道，其余四个场景全落在单车道里")
        void onlyBlacklistSplitsIntoLanes() {
            for (Scenario s : Scenario.values()) {
                if (s == Scenario.BLACKLIST) {
                    continue;
                }
                for (long n = 0; n < 50; n++) {
                    assertEquals(Lane.SINGLE, RiskTrafficClient.laneOf(s, n));
                }
            }
        }

        @Test
        @DisplayName("黑名单场景一半对照、一半攻击，三类攻击均分")
        void blacklistSplitsEvenly() {
            Map<Lane, Integer> counts = new EnumMap<>(Lane.class);
            for (long n = 1; n <= 600; n++) {
                counts.merge(RiskTrafficClient.laneOf(Scenario.BLACKLIST, n), 1, Integer::sum);
            }

            assertEquals(300, counts.get(Lane.CONTROL), "对照组要够大，误伤为 0 才有说服力");
            assertEquals(100, counts.get(Lane.BLACKLIST_USER));
            assertEquals(100, counts.get(Lane.BLACKLIST_DEVICE));
            assertEquals(100, counts.get(Lane.BLACKLIST_IP));
        }
    }

    // ================================================================ 响应分类

    @Nested
    class Responses {

        @Test
        @DisplayName("四个风控码各自归位，不与业务码混在一起")
        void mapsEveryRiskCode() {
            assertEquals(Outcome.RISK_LIMITED, classify("RISK_LIMITED"));
            assertEquals(Outcome.RISK_REVIEW_REQUIRED, classify("RISK_REVIEW_REQUIRED"));
            assertEquals(Outcome.RISK_BLOCKED, classify("RISK_BLOCKED"));
            assertEquals(Outcome.RISK_UNAVAILABLE, classify("RISK_UNAVAILABLE"));
            assertEquals(Outcome.OK, classify("OK"));
            assertEquals(Outcome.SLOT_FULL, classify("SLOT_FULL"));
            assertEquals(Outcome.ALREADY_GRABBED, classify("ALREADY_GRABBED"));
            assertEquals(Outcome.GRAB_RATE_LIMITED, classify("GRAB_RATE_LIMITED"));
        }

        @Test
        @DisplayName("认不出来的码归 OTHER，不归 ERROR")
        void unknownCodeIsNotAnInfrastructureError() {
            // ERROR 会让整轮被判成基础设施失败并以退出码 3 结束。业务码新增一个词
            // 就触发那个分支，等于把「我不知道这是什么」误报成「机器有问题」。
            assertEquals(Outcome.OTHER,
                    RiskTrafficClient.classifyResponse(200, "{\"code\":\"SOMETHING_NEW\"}"));
            assertEquals(Outcome.OTHER, RiskTrafficClient.classifyResponse(200, null));
            assertEquals(Outcome.OTHER, RiskTrafficClient.classifyResponse(500, "<html>502</html>"));
        }

        @Test
        @DisplayName("riskRejected 只认四个风控码：OK 与 ERROR 都不算「被风控拒绝」")
        void riskRejectedCoversExactlyTheFourRiskCodes() {
            assertTrue(Outcome.RISK_LIMITED.riskRejected());
            assertTrue(Outcome.RISK_REVIEW_REQUIRED.riskRejected());
            assertTrue(Outcome.RISK_BLOCKED.riskRejected());
            assertTrue(Outcome.RISK_UNAVAILABLE.riskRejected());
            assertFalse(Outcome.OK.riskRejected());
            assertFalse(Outcome.SLOT_FULL.riskRejected());
            assertFalse(Outcome.ERROR.riskRejected());
        }

        private Outcome classify(String code) {
            return RiskTrafficClient.classifyResponse(200, "{\"code\":\"" + code + "\",\"data\":{}}");
        }
    }

    @Nested
    class Login {

        @Test
        @DisplayName("从登录响应里取出 data.token，取不到返回 null 而不是空串")
        void parsesToken() {
            assertEquals("abc.def-123",
                    RiskTrafficClient.parseToken(
                            "{\"code\":\"OK\",\"data\":{\"token\":\"abc.def-123\",\"expiresIn\":7200}}"));

            assertNull(RiskTrafficClient.parseToken("{\"code\":\"UNAUTHORIZED\"}"),
                    "登录失败要返回 null：返回空串会让压测带着 Bearer 空值跑完，"
                            + "而所有请求都变成 401 的 OTHER");
            assertNull(RiskTrafficClient.parseToken("{\"data\":{\"token\":\""), "截断的响应体不能抛出异常");
        }

        @Test
        @DisplayName("失败原因要能安全地拼进 summary 的 JSON 里")
        void escapesFailureReason() {
            // summary 那一列是 MySQL 的 JSON 类型（BenchRunRecorder 用 CAST(? AS JSON)），
            // 转义漏了引号会让「把这一轮标成 FAIL」这件事自己失败——于是又回到一行永远 RUNNING。
            assertEquals("他说 \\\"连不上\\\" 了",
                    RiskTrafficClient.jsonEscape("他说 \"连不上\" 了"));
            assertEquals("第一行 第二行", RiskTrafficClient.jsonEscape("第一行\n第二行"));
            assertEquals("C:\\\\tmp", RiskTrafficClient.jsonEscape("C:\\tmp"),
                    "反斜杠要先转义，否则会把后面的字符吃掉");
        }
    }

    // ================================================================ 失败路径
    //
    // 这一组守的是一件在终端里看不见的事：**一轮失败之后 bench_run 里留下什么**。
    // 进程直接退出会把那一行永远留在 RUNNING，事后只能靠「应用日志里根本没有这一轮」
    // 反推它是死的（批次 1 的 F-39 就是这么留下的现场）。

    @Nested
    class FailurePaths {

        @Test
        @DisplayName("自检失败的异常消息带上每条明细与补救工具")
        void probeFailureCarriesEveryDetail() {
            IllegalStateException e = RiskTrafficClient.probeFailure(List.of(
                    "黑名单车道 用户 没有被拦（OK，user=1 ip=198.18.0.1 device=-）",
                    "对照车道被风控拒绝（RISK_LIMITED）"));

            assertTrue(e.getMessage().contains("user=1 ip=198.18.0.1"),
                    "明细要跟着异常落进 summary.error，不能只活在终端的几行 stderr 里");
            assertTrue(e.getMessage().contains("对照车道"),
                    "两条失败都要带上：只报第一条会让人以为改完一处就好了");
            assertTrue(e.getMessage().contains("BlacklistKeyTool"),
                    "要告诉下一个人怎么补救，而不是只说「失败了」");
        }

        @Test
        @DisplayName("自检失败是抛异常、不是直接退出 —— 直接退出会留一行永远 RUNNING")
        void probeFailureIsThrownRatherThanExited() {
            String code = methodCode(readMainSource(), "private static void verifyBlacklistSeeds");

            assertFalse(code.contains("System.exit"),
                    "verifyBlacklistSeeds 跑在 startRun 之后，从它里面退出，run() 的兜底就没机会"
                            + "把这一轮标成 FAIL —— 2026-09-12 的烟测就是这么留下 20260912-213853 的");
            assertTrue(code.contains("throw probeFailure(failures)"),
                    "失败要交回 run() 的兜底，由它写 FAIL 再退出");
        }

        @Test
        @DisplayName("测量段的退出码 3 排在落库之后：先把这一轮记下来，再退出")
        void measurementExitComesAfterTheRunIsRecorded() {
            String code = methodCode(readMainSource(), "private static void runTraffic");
            int recorded = code.indexOf("finishRun(runId, pass");
            int exit = code.indexOf("System.exit");

            assertTrue(recorded >= 0, "runTraffic 里找不到落库调用，这条守卫等于没跑");
            assertTrue(exit < 0 || recorded < exit,
                    "先退出再落库 = 这一轮在库里永远是 RUNNING，而它其实带着完整的数字");
        }

        private static String readMainSource() {
            List<Path> candidates = List.of(
                    Path.of("src", "main", "java", "com", "campusdash", "bench",
                            "RiskTrafficClient.java"),
                    Path.of("dash-bench", "src", "main", "java", "com", "campusdash", "bench",
                            "RiskTrafficClient.java"));
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    try {
                        return Files.readString(candidate);
                    } catch (Exception e) {
                        fail("读得到 " + candidate.toAbsolutePath() + " 却读不出来：" + e);
                    }
                }
            }
            fail("两个候选路径下都没有 RiskTrafficClient 的源码，这一组守卫没跑成："
                    + candidates.stream().map(Path::toAbsolutePath).toList());
            return null;
        }

        /**
         * 取出一个方法的**代码文本**：注释与字符串字面量都替成空格，顺便用它来配平大括号。
         *
         * <p>断言必须在代码上做，不能在原文上做——方法体里写一句「这里不许用 System.exit」
         * 的注释是很自然的事，按原文匹配会把它当成真的退出，守卫就变成「谁写注释谁红」。
         */
        private static String methodCode(String source, String signature) {
            int at = source.indexOf(signature);
            if (at < 0) {
                fail("源码里找不到方法签名「" + signature + "」：改名或搬走时这条守卫必须红，"
                        + "静默跳过等于没守");
            }
            StringBuilder code = new StringBuilder();
            int depth = 0;
            for (int i = source.indexOf('{', at); i < source.length(); i++) {
                char c = source.charAt(i);
                char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
                if (c == '/' && next == '/') {
                    while (i < source.length() && source.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && next == '*') {
                    while (i + 1 < source.length()
                            && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i++;
                } else if (c == '"') {
                    while (++i < source.length()) {
                        if (source.charAt(i) == '\\') {
                            i++;
                        } else if (source.charAt(i) == '"') {
                            break;
                        }
                    }
                    code.append(" 空串 ");   // 留个占位，免得两侧的记号粘成一个
                } else if (c == '{') {
                    depth++;
                    code.append(c);
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return code.toString();
                    }
                    code.append(c);
                } else {
                    // 换行统一换成空格：既不丢标记（断言的都是完整的一小段），
                    // 也不至于把两行的记号粘成一个新的词
                    code.append(c == '\n' ? ' ' : c);
                }
            }
            fail("方法体的大括号没配平，签名「" + signature + "」——守卫读错了地方");
            return null;
        }
    }

    // ================================================================ 请求组装
    //
    // 这一组是核心：五个场景的差别**全部**在这一次组装里。组装错了不会报错，
    // 只会让某个场景悄悄变成另一个场景。

    @Nested
    class Requests {

        private static final int POOL = 8;
        private static final List<Long> ERRANDS = List.of(7001L, 7002L, 7003L);

        private Profile profile(Scenario scenario) {
            return new Profile(scenario, "http://127.0.0.1:8080", POOL, 60, ERRANDS.size(),
                    SEED, POOL);
        }

        private RiskTrafficClient.Tokens tokens() {
            String[] pool = new String[POOL];
            for (int i = 0; i < POOL; i++) {
                pool[i] = "tok" + i;
            }
            return new RiskTrafficClient.Tokens(pool, "tok-blacklisted");
        }

        private Request req(Scenario scenario, Lane lane, long n, long laneSeq) {
            return RiskTrafficClient.requestOf(profile(scenario), lane, n, laneSeq, ERRANDS, tokens());
        }

        private Optional<String> header(Request r, String name) {
            return r.request().headers().firstValue(name);
        }

        private void assertNoDeviceHeader(Request r) {
            assertTrue(header(r, RiskTrafficClient.RiskTestHeaders.DEVICE_ID).isEmpty(),
                    "这个场景不该发设备头——真实前端不发它，发了就等于换了一种流量");
        }

        @Test
        @DisplayName("normal：每用户一个固定 IP，不发设备头，身份与主体对齐")
        void normalUsesPoolUserIpAndNoDevice() {
            Request r = req(Scenario.NORMAL, Lane.SINGLE, 3, 3);

            assertEquals(String.valueOf(SEED + 4), r.userId());
            assertEquals(RiskTrafficClient.poolIp(3), r.clientIp());
            assertEquals(RiskTrafficClient.poolIp(3),
                    header(r, RiskTrafficClient.RiskTestHeaders.CLIENT_IP).orElse(null));
            assertNoDeviceHeader(r);

            // userId 与 token 必须指同一个人：token 是从 poolIndex 取的，
            // 两者错位等于「A 抢单记在 B 名下」，而这不会报任何错。
            assertEquals("Bearer tok3", header(r, "Authorization").orElse(null));
            assertTrue(r.request().uri().toString().endsWith("/api/errands/7001/grab"),
                    "任务按全局序号轮转，不该总打同一条：" + r.request().uri());
        }

        /**
         * 对照组设备号的**调用点**守卫。
         *
         * <p>{@code Subjects#controlDeviceIsSharedByExactlyThreeUsers} 只测了
         * {@code controlDeviceId} 这个函数本身——它一直是绿的，而现场仍然是
         * 「388936 个对照请求里 15285 个被设备规则 REVIEW」。差别就在调用点把
         * {@code laneSeq}（= 全局序号）当成了用户号去分组：用户池 60000、IP 池 65024，
         * 回绕处两个下标错位，同一个设备号又挂上 3 个新用户。**函数对、调用错**，
         * 所以这条必须从 {@code requestOf} 走。
         */
        @Test
        @DisplayName("对照组：一个设备号下的用户号恰好 3 个，跨过回绕处也不能多挂")
        void controlDevicesGroupExactlyThreeUsersEvenAcrossWraps() {
            // 用真实参数：用户池 60000 与 IP 池 65024 不整除，错位只在两者的公倍数附近暴露
            int pool = 60000;
            String[] tok = new String[pool];
            java.util.Arrays.fill(tok, "t");
            RiskTrafficClient.Tokens tokens = new RiskTrafficClient.Tokens(tok, "tok-blacklisted");
            Profile p = new Profile(Scenario.BLACKLIST, "http://127.0.0.1:8080", 200, 60, 500, SEED, pool);

            Map<String, Set<String>> usersByDevice = new java.util.HashMap<>();
            for (long n = 0; n < 80000; n++) {
                Request r = RiskTrafficClient.requestOf(p, Lane.CONTROL, n, n, ERRANDS, tokens);
                String device = header(r, RiskTrafficClient.RiskTestHeaders.DEVICE_ID).orElseThrow(
                        () -> new AssertionError("对照组必须发设备头，user=" + r.userId()));
                usersByDevice.computeIfAbsent(device, k -> new HashSet<>()).add(r.userId());
            }

            // 60000 个用户号 ÷ 3 = 20000 个设备号；断言落在「确实铺开了」而不是一个精确值上，
            // 免得换代参数时这条守卫自己变成噪声
            assertTrue(usersByDevice.size() >= 20000,
                    "只数出 " + usersByDevice.size() + " 个设备号，这条守卫没跑到该跑的范围");
            for (Map.Entry<String, Set<String>> e : usersByDevice.entrySet()) {
                assertTrue(e.getValue().size() <= RiskTrafficClient.CONTROL_USERS_PER_DEVICE,
                        "设备 " + e.getKey() + " 名下有 " + e.getValue().size() + " 个账号 "
                                + e.getValue() + "；超过 "
                                + RiskTrafficClient.CONTROL_USERS_PER_DEVICE
                                + " 个就命中 DEVICE_ACCOUNTS_LOW，对照组自己会变成「被误伤」");
            }
        }

        @Test
        @DisplayName("hot-user：同一个池子里的少数账号被反复打，取值方式与 normal 相同")
        void hotUserLooksLikeNormalPerRequest() {
            Request r = req(Scenario.HOT_USER, Lane.SINGLE, 5, 5);

            assertEquals(RiskTrafficClient.poolIp(5), r.clientIp());
            assertNoDeviceHeader(r);
            assertEquals("Bearer tok5", header(r, "Authorization").orElse(null));
        }

        @Test
        @DisplayName("same-device：一个设备号 + 每人各自的 IP（IP 维度不能跟着一起退化）")
        void sameDeviceSharesOnlyTheDevice() {
            Request a = req(Scenario.SAME_DEVICE, Lane.SINGLE, 0, 0);
            Request b = req(Scenario.SAME_DEVICE, Lane.SINGLE, 1, 1);

            assertEquals(RiskTrafficClient.sharedDeviceId(SEED),
                    header(a, RiskTrafficClient.RiskTestHeaders.DEVICE_ID).orElse(null));
            assertEquals(RiskTrafficClient.sharedDeviceId(SEED),
                    header(b, RiskTrafficClient.RiskTestHeaders.DEVICE_ID).orElse(null));
            assertNotEquals(a.clientIp(), b.clientIp(),
                    "同设备场景里 IP 必须各不相同：IP 也撞了的话，量到的是「设备+IP 两个维度」"
                            + "的合成结果，设备规则的贡献就分不出来了");
        }

        @Test
        @DisplayName("same-ip：一个 IP + 不发设备头（只让 IP 维度退化）")
        void sameIpSharesOnlyTheIp() {
            Request a = req(Scenario.SAME_IP, Lane.SINGLE, 0, 0);
            Request b = req(Scenario.SAME_IP, Lane.SINGLE, 1, 1);

            assertEquals(RiskTrafficClient.SHARED_IP, a.clientIp());
            assertEquals(RiskTrafficClient.SHARED_IP, b.clientIp());
            assertNoDeviceHeader(a);
            assertNotEquals(a.userId(), b.userId());
        }

        @Test
        @DisplayName("blacklist 四个车道：三个发被拉黑的标识，对照组三者全干净")
        void blacklistLanesCarryTheRightIdentifier() {
            String deviceHeader = RiskTrafficClient.RiskTestHeaders.DEVICE_ID;
            String ipHeader = RiskTrafficClient.RiskTestHeaders.CLIENT_IP;

            Request blUser = req(Scenario.BLACKLIST, Lane.BLACKLIST_USER, 0, 0);
            assertEquals(String.valueOf(SEED + POOL + 1), blUser.userId());
            assertEquals(RiskTrafficClient.controlIp(0), blUser.clientIp());
            assertNotEquals(RiskTrafficClient.BLACKLIST_IP, blUser.clientIp(),
                    "被拉黑用户那一车道不该同时带上被拉黑 IP：两个维度一起黑，"
                            + "就分不出拦截是用户维度还是 IP 维度给的");
            assertNoDeviceHeader(blUser);

            Request blDevice = req(Scenario.BLACKLIST, Lane.BLACKLIST_DEVICE, 2, 2);
            assertEquals(RiskTrafficClient.blacklistDeviceId(SEED),
                    header(blDevice, deviceHeader).orElse(null));
            assertEquals(RiskTrafficClient.poolUserId(SEED, 2), blDevice.userId());

            Request blIp = req(Scenario.BLACKLIST, Lane.BLACKLIST_IP, 4, 4);
            assertEquals(RiskTrafficClient.BLACKLIST_IP,
                    header(blIp, ipHeader).orElse(null));
            assertNoDeviceHeader(blIp);

            Request control = req(Scenario.BLACKLIST, Lane.CONTROL, 6, 6);
            assertNotEquals(RiskTrafficClient.BLACKLIST_IP, control.clientIp());
            assertNotEquals(RiskTrafficClient.blacklistDeviceId(SEED),
                    header(control, deviceHeader).orElse(null));
            assertNotEquals(String.valueOf(SEED + POOL + 1), control.userId());
            assertEquals(RiskTrafficClient.controlDeviceId(SEED, 6),
                    header(control, deviceHeader).orElse(null));
        }

        @Test
        @DisplayName("每个请求一个新的 X-Request-Id：重复的 id 会被当成幂等重放")
        void everyRequestGetsAFreshRequestId() {
            Request a = req(Scenario.NORMAL, Lane.SINGLE, 1, 1);
            Request b = req(Scenario.NORMAL, Lane.SINGLE, 1, 1);

            String idA = header(a, RiskTrafficClient.REQUEST_ID_HEADER).orElse(null);
            String idB = header(b, RiskTrafficClient.REQUEST_ID_HEADER).orElse(null);
            assertNotNull(idA);
            assertNotNull(idB);
            assertNotEquals(idA, idB, "同一个 requestId 的第二次抢单会被判成重复请求，"
                    + "而且窗口的去重成员也会跟着错");
        }

        @Test
        @DisplayName("token 数组与用户池没对齐时当场拒绝，不许拿着别人的 token 发")
        void refusesMisalignedTokenArray() {
            Profile p = profile(Scenario.NORMAL);
            String[] tooShort = new String[POOL - 1];
            java.util.Arrays.fill(tooShort, "tok");
            RiskTrafficClient.Tokens misaligned = new RiskTrafficClient.Tokens(tooShort, null);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> RiskTrafficClient.requestOf(p, Lane.SINGLE, 1, 1, ERRANDS, misaligned));
            assertTrue(e.getMessage().contains("userPool"), e.getMessage());
        }

        @Test
        @DisplayName("被拉黑用户那一车道必须用**他本人**的 token，不能用池里的干净用户")
        void blacklistedUserLaneCarriesTheBlacklistedUsersOwnToken() {
            // 2026-09-12 的烟测抓到的缺陷：身份是从 token 来的（没开后门），
            // 而这一车道原来沿用池里用户的 token —— 于是应用看到的是一个干净账号，
            // 黑名单永远不会命中，而打印出来的 subject 上写着「被拉黑用户」。
            String blacklisted = String.valueOf(SEED + POOL + 1);
            Request blUser = req(Scenario.BLACKLIST, Lane.BLACKLIST_USER, 0, 0);
            assertEquals(blacklisted, blUser.userId());
            assertEquals("Bearer tok-blacklisted",
                    header(blUser, "Authorization").orElse(null),
                    "这一车道的 token 必须属于 " + blacklisted + " 本人");

            // 反证：其余车道仍然用池里对应用户的 token（否则就是把「被拉黑的 token」
            // 撒到了全部车道上，对照组会整片被拦）。
            assertEquals("Bearer tok6",
                    header(req(Scenario.BLACKLIST, Lane.CONTROL, 6, 6), "Authorization").orElse(null));
            assertEquals("Bearer tok4",
                    header(req(Scenario.BLACKLIST, Lane.BLACKLIST_IP, 4, 4), "Authorization")
                            .orElse(null));
        }

        @Test
        @DisplayName("同一个用户在不同轮次打不同的任务 —— taskPool 整除 userPool 时不能退化成一条")
        void errandsAreNotAliasedToUsers() {
            // 默认参数就是整除的（500 | 60000），所以这不是边角情况，而是默认情况。
            int userPool = 60000;
            int taskPool = 500;
            for (long user : new long[]{0, 1, 12345, 59999}) {
                Set<Integer> seen = new HashSet<>();
                for (int round = 0; round < 8; round++) {
                    seen.add(RiskTrafficClient.errandIndex(round * userPool + user, userPool, taskPool));
                }
                assertEquals(8, seen.size(),
                        "用户 " + user + " 在 8 轮里只落到了 " + seen.size() + " 条任务上；"
                                + "退化之后第二次抢同一条任务必然撞 uk_errand_round_user，"
                                + "落库失败 → GRAB_CONFLICT（烟测里这一条占了 99.3%）");
            }
        }

        @Test
        @DisplayName("任务号落在任务池内，且同时刻在途的请求铺在连续的任务上")
        void errandIndexStaysInRangeAndSpreads() {
            int userPool = 60000;
            int taskPool = 500;
            for (long n = 0; n < 200_000; n += 997) {
                int index = RiskTrafficClient.errandIndex(n, userPool, taskPool);
                assertTrue(index >= 0 && index < taskPool, "任务号越界：" + index);
            }
            // 连续的 n（同一时刻在途的那几十个请求）落到的任务号也必须连续，
            // 否则负载会堆在少数任务上，热点限流先于风控生效。
            for (long n = 1000; n < 1010; n++) {
                assertEquals(RiskTrafficClient.errandIndex(n, userPool, taskPool) + 1,
                        RiskTrafficClient.errandIndex(n + 1, userPool, taskPool));
            }
        }
    }

    // ================================================================ 跨模块字面量
    //
    // 三个头的名字在本仓库有两个来源：presentation 侧的常量（服务端读哪个头）与
    // 本客户端的常量（发哪个头）。它们分属两个模块，bench 不依赖 presentation，
    // 所以**没有任何编译期或装配期的一致性保证**——而写错的后果是静默的：
    // 测试头被忽略、设备与 IP 维度退化成「没注入」，压测照常跑完，数字是另一件事的。

    @Nested
    class HeaderLiterals {

        @Test
        @DisplayName("测试头与幂等键头的字面值与 RiskContextResolver 逐字一致")
        void headersMatchTheResolverThatReadsThem() throws Exception {
            String source = readPresentationSource("risk/RiskContextResolver.java");

            assertTrue(source.contains("TEST_DEVICE_HEADER = \""
                            + RiskTrafficClient.RiskTestHeaders.DEVICE_ID + "\""),
                    "服务端读的设备头不是本客户端发的那个（" + RiskTrafficClient.RiskTestHeaders.DEVICE_ID
                            + "）。改任意一侧之前，先想清楚设备维度会静默消失");
            assertTrue(source.contains("TEST_IP_HEADER = \""
                            + RiskTrafficClient.RiskTestHeaders.CLIENT_IP + "\""),
                    "服务端读的测试 IP 头不是本客户端发的那个（"
                            + RiskTrafficClient.RiskTestHeaders.CLIENT_IP + "）");
            assertTrue(source.contains("REQUEST_ID_HEADER = \""
                            + RiskTrafficClient.REQUEST_ID_HEADER + "\""),
                    "幂等键头对不上：每个请求都会拿到服务端生成的新 id，幂等与窗口去重都会失效");
        }

        @Test
        @DisplayName("源码文件找得到——找不到时这条守卫等于没跑，必须红而不是跳过")
        void theSourceIsActuallyWhereWeThinkItIs() throws Exception {
            assertFalse(readPresentationSource("risk/RiskContextResolver.java").isBlank());
        }

        /**
         * 读 presentation 侧的源码文本。
         *
         * <p>两个候选路径对应两种跑法：{@code mvn -pl dash-bench test} 的工作目录是模块目录，
         * 而某些 IDE 会把工作目录设成仓库根。两处都找不到时**直接失败**：
         * 这一条的存在意义就是「守卫跑了」，静默跳过会让「绿」变成假绿。
         */
        private static String readPresentationSource(String relative) throws Exception {
            List<Path> candidates = List.of(
                    Path.of("..", "dash-presentation", "src", "main", "java",
                            "com", "campusdash", "presentation", relative),
                    Path.of("dash-presentation", "src", "main", "java",
                            "com", "campusdash", "presentation", relative));
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            }
            fail("两个候选路径下都没有 presentation 源码，跨模块字面量这一条没跑成："
                    + candidates.stream().map(Path::toAbsolutePath).toList());
            return null;
        }
    }
}
