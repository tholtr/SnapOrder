package com.campusdash.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P9.5 攻击流量压测客户端（设计 §6.1）。
 *
 * <p>它只做一件事：按 scenario 决定的「用户 / 设备 / IP 组合」造抢单流量，并把
 * 请求数与延迟按车道记下来。风控指标不在这里算——那些数字来自服务端的
 * {@code risk_decision} 行（{@code analyze_risk_decisions.py}）与事件行
 * （{@code analyze_risk_events.py}）。本客户端产出的是**分母与对照**：每个车道发了多少次、
 * 客户端侧看到的动作分布是什么样。
 *
 * <pre>
 *   java -cp ... com.campusdash.bench.RiskTrafficClient \
 *        &lt;scenario&gt; [baseUrl] [concurrency] [durationSeconds] [taskPool] [seed] [userPool]
 * </pre>
 *
 * <p><b>为什么新开一个客户端而不是给 {@code GrabRampLoadClient} 加参数</b>（设计 D1）：
 * 那两个客户端产生的数字（S1 的 2240 req/s、S2 的 7735 qps、S3 的 2000→10000 阶梯）是
 * 跨阶段对照的锚点。给它们加可选 header 意味着每次请求的构造路径变了，于是
 * 「这次 7434 与上次 7434 是不是同一个东西」变成一个需要论证的问题。
 *
 * <h2>三条与设计文档的字面不同之处（2026-09-12 经用户逐条决定）</h2>
 *
 * <p>设计 §6.1 写的是「{@code normal} 与 {@code hot-user} 不带任何测试头」。实际执行时
 * 三条都改了，理由都是**单机环境让字面写法得不到它想要的结论**：
 *
 * <ol>
 *   <li><b>每个虚拟用户注入一个独立的假 IP</b>（{@code X-Test-Client-Ip}）。单机压测下
 *       所有流量都来自 {@code 127.0.0.1}（{@code ClientIpResolver.resolve} 对直连直接用
 *       对端地址，而 {@code trusted-proxy-cidrs} 为空使转发头完全不起作用），
 *       于是 {@code risk:ip:{hash}:rate} 是全机共用的一份计数，{@code IP_RATE}（60 秒 100 次，
 *       30 分 = {@code LIMIT_MIN}）从第 101 个请求起命中**每一个**请求——{@code normal}
 *       会得到 ≈100% {@code LIMIT}，对照 B 的误伤率变成环境伪影。给它一个独立的 IP 才是
 *       真实世界里正常用户的样子。</li>
 *   <li><b>身份走真实 token</b>（{@code POST /api/auth/login}），不用
 *       {@code dash.auth.allow-header-identity} 后门。压测应用因此可以按生产默认启动
 *       （后门 {@code false}），报告里不必为「开了后门」加限定。代价是每个用户一次登录，
 *       所以登录全部放在预热之前的准备段，**测量段里只有抢单请求**。</li>
 *   <li><b>{@code normal} 用大用户池轮转</b>而不是「每请求一个新用户」：池子按
 *       {@code userPool} 定（默认 60000），每个用户在本轮里出现约 7 次，远低于
 *       {@code USER_WINDOW_SHORT}（10 秒 5 次）与 {@code USER_WINDOW_LONG}（60 秒 30 次）
 *       两条阈值，所以正常流量**不会命中任何规则**——这正是对照 B 需要的基线。
 *       代价是 userId 会重复出现。</li>
 * </ol>
 *
 * <p><b>设备头仍然只在需要它的场景里发</b>：真实前端目前不发 {@code X-Device-Id}
 * （计划 §9.4 的限制原文），所以 {@code normal} / {@code hot-user} 的设备维度是**缺失**的，
 * 设备规则在这两个场景里不该命中——这是设计那句话真正要守住的东西，本客户端照守。
 *
 * <h2>测试头是后门，用它的场景要写在报告里</h2>
 *
 * <p>{@code X-Test-Device-Id} / {@code X-Test-Client-Ip} 只在
 * {@code dash.risk.test-context-enabled=true} **且** test profile 激活时才被读取
 * （{@code RiskContextInterceptor}），生产配置是 {@code false}。带上它们意味着
 * <b>这个请求不是真实前端能发出的</b>——所以设备与 IP 维度的结论全部来自注入，
 * 这一点要写在报告里那两个场景的表头上。
 *
 * <h2>黑名单场景的种子必须由外部先写好</h2>
 *
 * <p>本客户端**不写黑名单**：键是 HMAC 后的值，写它的唯一正确工具是
 * {@code BlacklistKeyTool}（设计 D4，它算的键与应用查的键同源）。客户端在启动时会打印
 * 它期望的三个键对应的原始标识，跑之前照抄那几行执行即可。为了防止「忘了写」或
 * 「写错了导致键对不上」，运行前会做一次**探针自检**：黑名单三个车道必须被拒、
 * 对照车道必须不被拒，否则打印诊断并以退出码 3 结束——黑名单失效是一条不报错的
 * 失败路径（{@code BlacklistKeyTool} 的类注释），探针是唯一能在压测现场抓住它的东西。
 */
public final class RiskTrafficClient {

    private static final String DEFAULT_BASE = "http://127.0.0.1:8080";

    /** 发单人。与既有压测客户端一致，*不* 与 {@code SkeletonSeeder} 的业务账号共用号段。 */
    private static final long PUBLISHER_ID = 1001L;

    private static final String TITLE_PREFIX = "bench_risk_";
    private static final int REWARD_CENTS = 100;
    /**
     * 发布任务时的 {@code slotTotal}。**它是写给发布接口看的，抢单路径根本不读它。**
     *
     * <p>2026-09-12 现场判定：`casLockForRunner` 的 SQL 是
     * {@code WHERE id=? AND status='PUBLISHED' AND version=?}，任务被抢中一次就变 LOCKED，
     * 之后的抢单全部命中 CAS 失败 → {@code GRAB_CONFLICT}（不是 SLOT_FULL：Redis 的
     * Lua 名额数是 2000，够用，所以那边不拦）。库里 1092 条 {@code grab_record}
     * 一条任务只中一次，没有反例。
     *
     * <p>所以**任务池的真实容量 = taskPool**，与这个数字无关：500 条任务的一轮，
     * 无论打 44 万次还是 100 万次请求，成功抢单最多 500 次，其余全是冲突。
     * 设计 §6.1 写的「taskPool × slotTotal」不成立，已记为偏离。
     * 这不影响风控侧的任何指标（决策在抢单之前，每个请求都有一行 {@code risk_decision}），
     * 只影响「抢单分位」的读法——它主要由冲突路径决定，报告里必须注明。
     */
    private static final int SLOT_TOTAL = 2000;

    /** 预热主体的号段。与用户池、黑名单用户都隔开，避免预热的计数落进测量窗口。 */
    private static final long WARM_USER_OFFSET = 900_000L;

    /**
     * 假 IP 的号段，全部取自 RFC 5737 / RFC 2544 保留段：
     * {@code 198.18.0.0/15}（benchmark）、{@code 198.19.0.0/16} 划给对照组、
     * {@code 203.0.113.0/24}（TEST-NET-3）划给共享 IP 与黑名单 IP、
     * {@code 192.0.2.0/24}（TEST-NET-1）给预热。
     *
     * <p>用保留段而不是真实网段，是为了让这些地址**在任何路由表里都不可达**——
     * 压测环境写进去的 IP 不该与真实客户端撞车。
     */
    private static final String POOL_IP_PREFIX = "198.18.";
    private static final String CONTROL_IP_PREFIX = "198.19.";
    static final String SHARED_IP = "203.0.113.10";
    static final String BLACKLIST_IP = "203.0.113.9";
    private static final String WARM_IP = "192.0.2.250";

    /** 每个假 IP 段可用的地址数：后两段里第二段的 0–255、第三段的 1–254。 */
    static final int IP_POOL_SIZE = 254 * 256;

    /**
     * 对照车道的设备被多少个账号共用。
     *
     * <p><b>这个数不是随手取的 3，而是由规则推出来的</b>：设备集合的上限是
     * {@code cap = threshold + 1}（{@code RiskProperties} 的推导），而
     * {@code DEVICE_ACCOUNTS_LOW} 在 {@code deviceAccounts > lowThreshold}（3）时命中。
     * 所以一个设备最多只能被 <b>3</b> 个账号共用，第 4 个账号就会让对照组自己触发
     * {@code REVIEW}——那正是对照要排除的东西。写成 /3 的分组，每组恰好 3 个。
     */
    static final int CONTROL_USERS_PER_DEVICE = 3;

    /**
     * 登录段同时在途的登录请求数上限。
     *
     * <p><b>这个上限是必需的，不是保守</b>：2026-09-12 的烟测里，一次放出 20000 个并发登录，
     * 打出了 10711 个失败，而应用日志里**一条 ERROR 都没有**——Tomcat 的 {@code maxConnections}
     * 默认 8192，超出的连接在内核的 accept 队列上就被丢了，服务端不留任何痕。
     * 压测端看到的症状（大批 ConnectException / Connection reset）与「应用崩了」长得一样，
     * 但根因在压测端自己。256 在途对 6 万个用户是秒级的事，没有理由为它冒险。
     */
    static final int LOGIN_MAX_IN_FLIGHT = 256;

    /**
     * 幂等键头。与 {@code RiskContextResolver.REQUEST_ID_HEADER} 同值，理由见
     * {@link RiskTestHeaders}：字面量错一个字符不会报错，只会让每个请求都拿到服务端生成的
     * 新 id（幂等失效）并让窗口的去重成员失去意义。
     */
    static final String REQUEST_ID_HEADER = "X-Request-Id";

    private RiskTrafficClient() {
    }

    // ================================================================ 入口

    public static void main(String[] args) throws Exception {
        Profile profile;
        try {
            profile = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        try {
            run(client, profile);
        } catch (RuntimeException e) {
            // 与 BlacklistKeyTool 同一条规矩：不吞异常、不打印一份看起来很正常的报告
            System.err.println("压测失败：" + e);
            System.exit(3);
        }
    }

    // ================================================================ 参数

    /**
     * 一次运行的完整参数。
     *
     * <p>抽成记录而不是在 {@link #main} 里就地读 {@code args}，是为了让
     * 「scenario 决定哪些默认值」这件事能被单测直接断言（见 {@link #parse}）。
     */
    record Profile(Scenario scenario, String baseUrl, int concurrency, int durationSeconds,
                   int taskPool, long seed, int userPool) {
    }

    /**
     * 可跑的五个场景（设计 §6.3 的 1–5）。
     *
     * <p>第六个场景「聚合关闭/开启对照」不在这里：它不是一种流量，而是同一个
     * {@code hot-user} 流量跑两遍、中间只改一次配置（设计 §6.4 的对照 A）。
     */
    enum Scenario {
        /** 正常用户混合流量：大用户池、每用户独立 IP、不发设备头。 */
        NORMAL("normal", "S5-RISK-normal", 200, 60_000),
        /** 单用户脚本抢单：少量用户高并发猛打，每个用户独立 IP。 */
        HOT_USER("hot-user", "S5-RISK-hot-user", 400, 4),
        /** 多账号同设备：大用户池共用一个设备号，每个用户仍然有自己的 IP。 */
        SAME_DEVICE("same-device", "S5-RISK-same-device", 200, 60_000),
        /** 多账号同 IP：大用户池共用一个 IP，不发设备头。 */
        SAME_IP("same-ip", "S5-RISK-same-ip", 200, 60_000),
        /** 黑名单三类 + 同流量的干净对照组。 */
        BLACKLIST("blacklist", "S5-RISK-blacklist", 200, 60_000);

        private final String cli;
        private final String scenarioName;
        private final int defaultConcurrency;
        private final int defaultUserPool;

        Scenario(String cli, String scenarioName, int defaultConcurrency, int defaultUserPool) {
            this.cli = cli;
            this.scenarioName = scenarioName;
            this.defaultConcurrency = defaultConcurrency;
            this.defaultUserPool = defaultUserPool;
        }

        /** {@code bench_run.scenario} 的取值。前缀 {@code S5-RISK-} 让清理与历史报告都按它走。 */
        String scenarioName() {
            return scenarioName;
        }

        static Scenario of(String raw) {
            String key = raw.trim().toLowerCase(Locale.ROOT);
            for (Scenario s : values()) {
                if (s.cli.equals(key)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知场景「" + raw + "」，只能是 " + cliList());
        }

        private static String cliList() {
            StringBuilder sb = new StringBuilder();
            for (Scenario s : values()) {
                sb.append(sb.isEmpty() ? "" : " / ").append(s.cli);
            }
            return sb.toString();
        }
    }

    /**
     * 解析命令行。省略的并发与用户池取**场景自己的**默认值——热用户场景要用 400
     * 才能把窗口压满（设计 §6.5），而正常流量 400 只会把机器先压垮。
     */
    static Profile parse(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException(usage());
        }
        Scenario scenario = Scenario.of(args[0]);
        String baseUrl = args.length > 1 ? args[1] : DEFAULT_BASE;
        int concurrency = args.length > 2 ? positive(args[2], "concurrency")
                : scenario.defaultConcurrency;
        int duration = args.length > 3 ? positive(args[3], "durationSeconds") : 60;
        int taskPool = args.length > 4 ? positive(args[4], "taskPool") : 500;
        long seed = args.length > 5 ? parseSeed(args[5]) : 2_100_000L;
        int userPool = args.length > 6 ? positive(args[6], "userPool") : scenario.defaultUserPool;
        return new Profile(scenario, baseUrl, concurrency, duration, taskPool, seed, userPool);
    }

    private static int positive(String raw, String what) {
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " 必须是整数，收到：" + raw);
        }
        if (value <= 0) {
            throw new IllegalArgumentException(what + " 必须 > 0，收到：" + value);
        }
        return value;
    }

    private static long parseSeed(String raw) {
        long seed;
        try {
            seed = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("seed 必须是整数，收到：" + raw);
        }
        if (seed < 2_000_000L) {
            throw new IllegalArgumentException(
                    "seed 必须 ≥ 2000000：压测用户号段固定在 2_000_000+（设计 §3.3），"
                            + "免得与 IT 与业务种子数据共用号段。收到：" + seed);
        }
        return seed;
    }

    static String usage() {
        return """
                用法：RiskTrafficClient <scenario> [baseUrl] [concurrency] [durationSeconds] [taskPool] [seed] [userPool]

                  scenario         normal | hot-user | same-device | same-ip | blacklist
                  baseUrl          默认 %s
                  concurrency      默认按场景取（normal/same-device/same-ip/blacklist 200，hot-user 400）
                  durationSeconds  正式测量时长，不含预热，默认 60
                  taskPool         先发布的任务数，默认 500（每任务 %d 个名额）
                  seed             用户/设备/IP 的编号起点，默认 2100000，必须 ≥ 2000000
                  userPool         用户池大小，默认按场景取（normal/same-device/same-ip/blacklist 60000，hot-user 4）

                userPool 的含义按场景不同：normal 是「轮转的用户数」，hot-user 是「猛打的用户数」，
                same-device / same-ip / blacklist 是「共用那个设备/IP 的账号数」。

                userPool 不能太小：每个用户在一轮里的请求数必须低于 USER_WINDOW_LONG 的
                30 次/60 秒（10 秒窗口的 5 次更紧）。200 并发下 60 秒约 40 万次请求，
                所以池子至少要 14000；默认 60000 给出约 4 倍余量。

                跑之前需要环境里有：
                  · MySQL 127.0.0.1:3307（BenchRunRecorder 登记轮次与任务）
                  · 应用已启动，且**没有**开 dash.auth.allow-header-identity（本客户端走真实 token）
                  · 应用开了 dash.risk.test-context-enabled=true 且 test profile 激活（否则测试头被忽略，
                    设备与 IP 维度会静默退化成「没注入」，场景 3/4 会得到与场景 1 相同的结果）
                  · 黑名单场景要先按启动横幅打印的命令写种子（BlacklistKeyTool add）""".formatted(
                DEFAULT_BASE, SLOT_TOTAL);
    }

    // ================================================================ 车道

    /**
     * 车道 = 一组「主体取值方式」相同的请求。
     *
     * <p>分车道是为了让每个维度的分母可数：同一个请求既可能命中设备规则也可能命中
     * 用户的窗口规则，只看总数分不出「设备规则命中了多少次」。客户端只能看到业务码
     * （{@code RISK_BLOCKED} 等），规则级的归因在服务端的 {@code matchedRules} 里，
     * 而**每类请求发了多少次**只有客户端知道——两边的数字要在报告里对上。
     */
    enum Lane {
        /** 其余四个场景：所有请求同一种取值方式。 */
        SINGLE("single"),
        /** 黑名单场景 · 被拉黑的用户（设备与 IP 每次都换干净的）。 */
        BLACKLIST_USER("bl-user"),
        /** 黑名单场景 · 被拉黑的设备（用户从池子里取，IP 换干净的）。 */
        BLACKLIST_DEVICE("bl-device"),
        /** 黑名单场景 · 被拉黑的 IP（用户从池子里取，不发设备头）。 */
        BLACKLIST_IP("bl-ip"),
        /** 黑名单场景 · 对照组：用户/设备/IP 三者都是干净的。 */
        CONTROL("control");

        private final String label;

        Lane(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    // ================================================================ 主体取值
    //
    // 下面这组静态方法**只有一个调用点**（requestOf），抽成独立方法是为了让单测能直接
    // 断言「不同的 idx 得到不同的 IP」「对照设备每 3 个账号换一个」这类性质——这些性质
    // 一旦破坏，场景会静默地变成另一个场景（比如 IP 撞车 → 所有流量共用一个 IP 桶），
    // 而压测本身不会报任何错。

    static String poolUserId(long seed, int index) {
        return String.valueOf(seed + index + 1L);
    }

    /** 黑名单用户：放在用户池之后，与池子不重叠。 */
    static String blacklistUserId(long seed, int userPool) {
        return String.valueOf(seed + userPool + 1L);
    }

    static String warmUserId(long seed) {
        return String.valueOf(seed + WARM_USER_OFFSET);
    }

    /** 用户池里第 {@code index} 个用户的固定假 IP（{@code 198.18.x.y}）。 */
    static String poolIp(int index) {
        return POOL_IP_PREFIX + (index / 254) % 256 + "." + (index % 254 + 1);
    }

    /**
     * 对照组第 {@code index} 个请求用的干净 IP（{@code 198.19.x.y}）。
     *
     * <p>按请求序号轮转而不是按用户：一个 IP 在一轮里被用到 3 次左右，
     * 远低于 {@code IP_RATE} 的 100 次/60 秒，也远低于 {@code IP_ACCOUNTS} 的 10 个账号。
     */
    static String controlIp(int index) {
        return CONTROL_IP_PREFIX + (index / 254) % 256 + "." + (index % 254 + 1);
    }

    /** 同设备场景共用的那一个设备号。原始值，由服务端哈希。 */
    static String sharedDeviceId(long seed) {
        return "bench-dev-" + seed;
    }

    /** 被拉黑的设备号。前缀与共用的那个不同，避免两个场景互相干扰。 */
    static String blacklistDeviceId(long seed) {
        return "bench-bl-dev-" + seed;
    }

    static String warmDeviceId(long seed) {
        return "bench-warm-dev-" + seed;
    }

    /**
     * 对照组的干净设备号：<b>每 {@value #CONTROL_USERS_PER_DEVICE} 个用户号换一个</b>。
     *
     * <p>参数必须是**用户号**（{@code poolIndex}），不是全局序号：设备集合的成员是 userId，
     * 上限 {@code cap = threshold + 1}，低档规则在第 4 个账号就命中，所以「哪几个用户共用一个
     * 设备」必须直接由用户号决定。用户池与 IP 池的长度不同（60000 / 65024），拿全局序号
     * 去掉两个模数，回绕处就会把 3 个新用户挂到同一个设备上——调用点见
     * {@link #requestOf} 的 CONTROL 分支。
     *
     * <p>它与 {@link #controlIp} 的轮转方式不同，是刻意的：IP 的约束来自「一个 IP 别攒
     * 太多请求」，而设备的约束来自「一个设备别攒太多**账号**」。
     */
    static String controlDeviceId(long seed, int userIndex) {
        return "bench-ctl-dev-" + seed + "-" + (userIndex / CONTROL_USERS_PER_DEVICE);
    }

    /**
     * 第 {@code n} 个请求打哪条任务。
     *
     * <p><b>不能写成 {@code n % taskPool}。</b>用户号是 {@code n % userPool}，而只要
     * {@code taskPool} 整除 {@code userPool}（默认参数 500 整除 60000、烟测的 200 整除 20000
     * 都是），用户号就完全决定了任务号——一个用户在一轮里**永远只打同一条任务**。
     *
     * <p>后果不是「分布有点偏」，而是当场撞唯一索引：第二次抢同一条任务会碰到
     * {@code uk_errand_round_user (errand_id, round, runner_id)}，落库返回 false →
     * {@code GRAB_CONFLICT} → 代码把 Redis 名额回滚掉 → 第三次又走到同一个索引上。
     * 2026-09-12 的烟测里 232931 次请求有 231359 次是这么来的（99.3% 冲突），
     * 而数据库侧的读数是「每条任务恰好被抢中一次、状态停在 LOCKED」。
     *
     * <p>加上「轮次」（{@code n / userPool}，即这个用户第几次出现）之后，同一个用户每次
     * 落在不同的任务上；而任一时刻在途的那几十个请求的 {@code n} 是连续的，落到的任务
     * 也仍然是连续的几十条，负载依旧铺得开。
     */
    static int errandIndex(long n, int userPool, int taskPool) {
        int round = (int) (n / userPool);
        int user = (int) (n % userPool);
        return (round + user) % taskPool;
    }

    // ================================================================ 运行

    private static void run(HttpClient client, Profile p) throws Exception {
        BenchRunRecorder recorder = new BenchRunRecorder();
        String runId = recorder.startRun("BENCH", p.scenario().scenarioName(), p.concurrency(),
                "攻击流量压测；应用与压测端同机；taskPool=" + p.taskPool()
                        + ", userPool=" + p.userPool() + ", seed=" + p.seed()
                        + ", 身份=真实 token（未开后门），IP 走 X-Test-Client-Ip 注入");

        printSubjects(p, runId);

        try {
            runTraffic(client, p, runId, recorder);
        } catch (Exception e) {
            failRun(recorder, runId, e);
            throw e;
        }
    }

    /**
     * 把一轮标成 FAIL 并写上原因。
     *
     * <p>没有这一步，准备段炸掉（发布失败、登录失败）会在 {@code bench_run} 里留一行
     * **永远 RUNNING**——现场看到它无法区分「还在跑」与「早就死了」，而批次 4 整理报告时
     * 只能把这种行当成噪声剔掉。这个方法尽力而为：它自己失败（比如库也连不上）只打印，
     * 不去掩盖真正的原因。
     */
    private static void failRun(BenchRunRecorder recorder, String runId, Throwable e) {
        try {
            recorder.finishRun(runId, "FAIL", "{\"error\":\"" + jsonEscape(String.valueOf(e)) + "\"}");
            recorder.close();
        } catch (RuntimeException suppressed) {
            System.err.println("（把这一轮标成 FAIL 也没成功：" + suppressed + "）");
        }
    }

    /** 异常消息里会有引号与换行，直接拼进 JSON 会让 summary 变成非法文档。 */
    static String jsonEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    /** 准备段 + 测量段 + 落库。把这一段单独拆出来，是为了让 {@link #run} 能兜住它的异常。 */
    private static void runTraffic(HttpClient client, Profile p, String runId,
                                   BenchRunRecorder recorder) throws Exception {
        // ---- 准备段：发任务、登录用户池、预热 ----------------------------------
        // 这一段产生的请求**不进测量窗口**，所以它写下的风控计数也不是报告里的数字。
        // 登录在这里做完的另一个理由：真实 token 的会话是 Redis 里的一个键，
        // 如果边压边登录，测量段里就会混进一半的登录流量，「抢单 P99」就不再是抢单的 P99。
        List<Long> errandIds = seedTasks(client, p);
        recorder.trackErrands(runId, errandIds);
        System.out.printf("任务池：%d 个（每任务 %d 个名额）%n", errandIds.size(), SLOT_TOTAL);

        long loginStart = System.nanoTime();
        Tokens tokens = loginPool(client, p);
        System.out.printf("用户池：%d 个用户已登录（%.1f 秒）%s%n",
                tokens.pool().length, (System.nanoTime() - loginStart) / 1e9,
                tokens.blacklistedUser() == null ? "" : "，另有被拉黑用户自己的 token 1 个");

        warmup(client, p, errandIds);

        if (p.scenario() == Scenario.BLACKLIST) {
            verifyBlacklistSeeds(client, p, errandIds, tokens);
        }

        // ---- 测量段 ----------------------------------------------------------
        Tally tally = new Tally();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong seq = new AtomicLong();
        AtomicLong laneSeq = new AtomicLong();
        AtomicInteger infraErrors = new AtomicInteger();

        long measureStart = System.nanoTime();
        long deadline = measureStart + TimeUnit.SECONDS.toNanos(p.durationSeconds());
        CountDownLatch done = new CountDownLatch(p.concurrency());

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < p.concurrency(); i++) {
                pool.submit(() -> {
                    try {
                        while (System.nanoTime() < deadline) {
                            long n = seq.incrementAndGet();
                            Lane lane = laneOf(p.scenario(), n);
                            Request r = requestOf(p, lane, n, laneSeq.incrementAndGet(),
                                    errandIds, tokens);
                            long started = System.nanoTime();
                            try {
                                HttpResponse<String> resp = client.send(r.request(),
                                        HttpResponse.BodyHandlers.ofString());
                                latencies.add((System.nanoTime() - started) / 1_000_000);
                                tally.add(lane, classifyResponse(resp.statusCode(), resp.body()));
                            } catch (Exception e) {
                                // 基础设施异常（连接被拒、超时）**不是**风控拒绝，必须分开计，
                                // 否则「拦截率」里会混进一堆压测端自己的错误。
                                infraErrors.incrementAndGet();
                                latencies.add((System.nanoTime() - started) / 1_000_000);
                                tally.add(lane, Outcome.ERROR);
                                tally.noteError(e);
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            if (!done.await(p.durationSeconds() + 180L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("压测未在限定时间内完成 concurrency=" + p.concurrency());
            }
        }

        long elapsedMs = Math.max(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - measureStart), 1);
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        int requests = tally.total();
        long qps = Math.round(requests * 1000.0 / elapsedMs);
        long p50 = pct(sorted, 50);
        long p95 = pct(sorted, 95);
        long p99 = pct(sorted, 99);
        long max = sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1);

        System.out.println("================ " + p.scenario().scenarioName() + " 结果 ================");
        System.out.printf("runId=%s concurrency=%d duration=%ds taskPool=%d userPool=%d seed=%d%n",
                runId, p.concurrency(), p.durationSeconds(), p.taskPool(), p.userPool(), p.seed());
        System.out.printf("requests=%d qps=%d%n", requests, qps);
        System.out.println("动作分布（客户端视角，来自业务码）：");
        System.out.print(tally.describeTotal());
        System.out.println("按车道：");
        System.out.print(tally.describeLanes());
        System.out.printf("latency P50/P95/P99/Max=%d/%d/%d/%d ms%n", p50, p95, p99, max);
        if (!tally.errorTypes().isEmpty()) {
            System.out.println("基础设施错误：" + tally.errorTypes());
        }
        System.out.println("=======================================================");

        boolean pass = infraErrors.get() == 0;
        recorder.finishRun(runId, pass ? "PASS" : "FAIL", summaryJson(p, requests, qps, p50, p95, p99, max, tally));
        recorder.close();

        if (!pass) {
            System.err.println("压测出现基础设施错误（非风控拒绝），runId=" + runId);
            System.exit(3);
        }
    }

    /** 黑名单场景按「一半对照、一半黑名单（三类均分）」分流；其余场景只有一个车道。 */
    static Lane laneOf(Scenario scenario, long seq) {
        if (scenario != Scenario.BLACKLIST) {
            return Lane.SINGLE;
        }
        if (seq % 2 == 0) {
            return Lane.CONTROL;
        }
        return switch ((int) ((seq / 2) % 3)) {
            case 0 -> Lane.BLACKLIST_USER;
            case 1 -> Lane.BLACKLIST_DEVICE;
            default -> Lane.BLACKLIST_IP;
        };
    }

    /**
     * 造一个请求。
     *
     * @param n         全局序号，决定用池子里的哪个用户（以及哪条任务）
     * @param laneSeq   本车道的序号，决定对照组的设备与 IP（按车道计数才不会与别的车道撞）
     */
    static Request requestOf(Profile p, Lane lane, long n, long laneSeq,
                                     List<Long> errandIds, Tokens tokens) {
        // token 用 poolIndex 取，而不是再算一次 n % tokens.length：
        // 两个模数写在两处，一旦有一个改了就变成「这个用户拿着别人的 token」，
        // 而症状只是规则命中率不对——宁可让它们必须是同一个数，并在下面挡住不一致的调用。
        if (tokens.pool().length < p.userPool()) {
            throw new IllegalArgumentException("token 数组要与用户池对齐：tokens="
                    + tokens.pool().length + " userPool=" + p.userPool());
        }
        long seed = p.seed();
        int poolIndex = (int) (n % p.userPool());
        long errandId = errandIds.get(errandIndex(n, p.userPool(), errandIds.size()));
        int controlIndex = (int) (laneSeq % IP_POOL_SIZE);

        String userId;
        String deviceId = null;
        String ip;

        switch (lane) {
            case SINGLE -> {
                userId = poolUserId(seed, poolIndex);
                ip = poolIp(poolIndex);
                // 场景 3 的「共用设备」与场景 1/2 的「没有设备」在这里分开：
                // 只有 same-device 才发设备头。
                if (p.scenario() == Scenario.SAME_DEVICE) {
                    deviceId = sharedDeviceId(seed);
                } else if (p.scenario() == Scenario.SAME_IP) {
                    ip = SHARED_IP;
                }
            }
            case BLACKLIST_USER -> {
                userId = blacklistUserId(seed, p.userPool());
                ip = controlIp(controlIndex);
            }
            case BLACKLIST_DEVICE -> {
                userId = poolUserId(seed, poolIndex);
                deviceId = blacklistDeviceId(seed);
                ip = controlIp(controlIndex);
            }
            case BLACKLIST_IP -> {
                userId = poolUserId(seed, poolIndex);
                ip = BLACKLIST_IP;
            }
            case CONTROL -> {
                userId = poolUserId(seed, poolIndex);
                // 分组下标必须来自**用户号**，不能来自全局序号：用户池 60000、IP 池 65024，
                // 两个模数不同，用 controlIndex（= n % 65024）分组时每到一处回绕，
                // 同一个设备号就会再挂上 3 个**不同的**用户。2026-09-12 的正式一轮里，
                // 对照组 388936 个请求因此有 15285 个命中 DEVICE_ACCOUNTS_LOW(REVIEW)、
                // 170 个命中 HIGH(BLOCK)——「对照组的误伤为 0」这句话当场就不成立，
                // 而它看起来像风控误伤，其实是压测端自己造出来的账号堆积。
                deviceId = controlDeviceId(seed, poolIndex);
                ip = controlIp(controlIndex);
            }
            default -> throw new IllegalStateException("未处理的车道：" + lane);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(p.baseUrl() + "/api/errands/" + errandId + "/grab"))
                .header("Authorization", "Bearer " + tokens.forLane(lane, poolIndex))
                .header(REQUEST_ID_HEADER, UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody());

        // 用户号从 token 来，但下面的两个测试头要显式发——它们只在压测环境被认
        // （见类注释）。X-Test-Client-Ip 在每个场景都发：本机直连的流量全都来自
        // 127.0.0.1，不注入的话 IP 维度会成为全机共用的一份计数。
        builder.header(RiskTestHeaders.CLIENT_IP, ip);
        if (deviceId != null) {
            builder.header(RiskTestHeaders.DEVICE_ID, deviceId);
        }
        return new Request(builder.build(), userId, ip, deviceId);
    }

    /**
     * 把构造结果一起带出来，便于单测与排查时看清「这个请求自报了什么身份」。
     *
     * <p>{@code userId} 不参与发请求（身份由 token 携带，服务端只认 token），但它是对照自检
     * 失败时**唯一能指出「哪个账号/哪个标识重了」的东西**：池子配小了、或者两个车道撞了
     * 主体，症状都是「命中率不对劲」而不是报错，那时要靠它去对号。
     */
    record Request(HttpRequest request, String userId, String clientIp, String deviceId) {
    }

    /**
     * 测试头名字的**唯一一处字面量**。
     *
     * <p>与 {@code RiskContextResolver} 里的常量同名同值，但那边在 presentation 模块，
     * bench 不依赖它（{@code dash-bench} 只依赖 domain/infrastructure/shared）。
     * 名字写错两个字符不会有任何报错，只会让测试头被静默忽略、设备与 IP 维度悄悄退化成
     * 「没注入」，而压测照常跑完——所以这里单独放一个常量，并由
     * {@code RiskTrafficClientTest} 直接读 presentation 侧的源码文件、把两个字面值逐字钉住。
     *
     * <p>**这不是多余的重复**：{@code RiskTestHeaderBoundaryIT} 引用的是
     * {@code RiskContextResolver.TEST_DEVICE_HEADER} 这个**常量**，所以它证明的是「装配起来之后
     * 这个头认不认」，而不是「这个头的名字是哪个字符串」——把常量值改掉，那条 IT 仍然全绿。
     * 跨模块的名字一致性在本仓库没有第二种保证方式（bench 不依赖 presentation），
     * 只剩「读源码文本断言」这一条。
     */
    static final class RiskTestHeaders {
        static final String DEVICE_ID = "X-Test-Device-Id";
        static final String CLIENT_IP = "X-Test-Client-Ip";

        private RiskTestHeaders() {
        }
    }

    // ================================================================ 准备段

    /** 发布任务池。赏金 100 分、每任务 {@value #SLOT_TOTAL} 个名额。 */
    private static List<Long> seedTasks(HttpClient client, Profile p) throws Exception {
        String publisherToken = login(client, p.baseUrl(), PUBLISHER_ID);
        List<Long> ids = new ArrayList<>(p.taskPool());
        for (int i = 0; i < p.taskPool(); i++) {
            String json = """
                    {"campusId":1,"type":"DELIVERY","title":"%s%d-%d","rewardCents":%d,"slotTotal":%d}
                    """.formatted(TITLE_PREFIX, p.seed(), i + 1, REWARD_CENTS, SLOT_TOTAL);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(p.baseUrl() + "/api/errands"))
                    .header("Authorization", "Bearer " + publisherToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (!resp.body().contains("\"code\":\"OK\"")) {
                throw new IllegalStateException(
                        "发布任务失败（第 " + (i + 1) + " 个）：" + truncate(resp.body())
                                + "；常见原因是发布人 1001 的余额不足，先跑 bench/scripts/cleanup.sh");
            }
            ids.add(SpikeLoadClient.parseErrandId(resp.body()));
        }
        return ids;
    }

    /**
     * 登录结果：用户池的 token（下标与池子对齐）+ （只有黑名单场景才有的）被拉黑用户自己的 token。
     *
     * <p><b>被拉黑用户为什么必须单独登录</b>：身份是从 token 来的（不开后门），所以
     * 「让这个请求以被拉黑用户的身份发出」的唯一办法就是拿他本人的 token。若沿用池里某个
     * 干净用户的 token，应用看到的是一个干净账号——黑名单车道永远不会被拦，而客户端打印的
     * subject 上却写着「被拉黑用户」。两边对不上，而且没有任何报错。
     * 2026-09-12 的烟测就是这样被自检当场抓住的（`bl-user 没有被拦（OK）`）。
     */
    record Tokens(String[] pool, String blacklistedUser) {

        /** 这个车道该用谁的 token。{@code BLACKLIST_USER} 之外的车道都用池里的用户。 */
        String forLane(Lane lane, int poolIndex) {
            return lane == Lane.BLACKLIST_USER ? blacklistedUser : pool[poolIndex];
        }
    }

    /**
     * 登录整个用户池，返回下标对齐的 token 数组。
     *
     * <p>并行走虚拟线程（池子 6 万时串行登录要几分钟），但**在途数必须封顶**。
     */
    private static Tokens loginPool(HttpClient client, Profile p) throws Exception {
        String[] tokens = new String[p.userPool()];
        CountDownLatch done = new CountDownLatch(p.userPool());
        AtomicInteger failures = new AtomicInteger();
        List<String> samples = Collections.synchronizedList(new ArrayList<>());
        Semaphore gate = new Semaphore(LOGIN_MAX_IN_FLIGHT);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < p.userPool(); i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        gate.acquire();
                        try {
                            tokens[index] = login(client, p.baseUrl(),
                                    Long.parseLong(poolUserId(p.seed(), index)));
                        } finally {
                            gate.release();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                        if (samples.size() < 3) {
                            samples.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            if (!done.await(900L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("用户池登录超时");
            }
        }
        if (failures.get() > 0) {
            // 把前几个原因带出来。只报个数字的话，现场第一件事就是来改这里的代码。
            throw new IllegalStateException("有 " + failures.get() + " 个用户登录失败（共 "
                    + p.userPool() + " 个），前几个原因：" + samples
                    + "；应用没起来，或 /api/auth/login 不可用，或登录并发把连接打满了");
        }
        String blacklisted = null;
        if (p.scenario() == Scenario.BLACKLIST) {
            blacklisted = login(client, p.baseUrl(),
                    Long.parseLong(blacklistUserId(p.seed(), p.userPool())));
        }
        return new Tokens(tokens, blacklisted);
    }

    private static String login(HttpClient client, String baseUrl, long userId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":" + userId + "}"))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String token = parseToken(resp.body());
        if (token == null) {
            throw new IllegalStateException("登录失败 userId=" + userId + "：" + truncate(resp.body()));
        }
        return token;
    }

    /**
     * 从登录响应里取 {@code data.token}。
     *
     * <p>手工取字段而不是引 JSON 库：{@code dash-bench} 的依赖里没有 JSON 解析器，
     * 而为了取一个字符串把 Jackson 拉进压测端的 classpath 得不偿失——
     * {@link SpikeLoadClient#parseErrandId} 也是同样的取法。
     */
    static String parseToken(String body) {
        String marker = "\"token\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int from = start + marker.length();
        int end = body.indexOf('"', from);
        return end < 0 ? null : body.substring(from, end);
    }

    /**
     * 预热：把 JVM 的 JIT、HTTP 连接池与**应用侧**的冷启动都吃掉。
     *
     * <p>应用侧那一条不是可有可无的：新进程的第一个请求会因连接池冷启动耗尽 10ms 预算，
     * 记一行 {@code DEADLINE_EXCEEDED}，而那次降级会跳过该请求的采集体写入——
     * 于是后续规则的起点整体位移（F-36 在批次 1 现场抓到的就是这件事）。
     * 预热用独立号段（{@link #warmUserId} / {@link #WARM_IP} / {@link #warmDeviceId}），
     * 所以它只预热，不往测量窗口里掺计数。
     */
    private static void warmup(HttpClient client, Profile p, List<Long> errandIds) throws Exception {
        int seconds = Math.max(2, Math.min(5, p.durationSeconds() / 12));
        int concurrency = Math.min(64, p.concurrency());
        String token = login(client, p.baseUrl(), Long.parseLong(warmUserId(p.seed())));
        String device = p.scenario() == Scenario.SAME_DEVICE ? warmDeviceId(p.seed()) : null;

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicLong n = new AtomicLong();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    try {
                        while (System.nanoTime() < deadline) {
                            HttpRequest.Builder b = HttpRequest.newBuilder()
                                    .uri(URI.create(p.baseUrl() + "/api/errands/"
                                            + errandIds.get((int) (n.incrementAndGet() % errandIds.size()))
                                            + "/grab"))
                                    .header("Authorization", "Bearer " + token)
                                    .header(REQUEST_ID_HEADER, UUID.randomUUID().toString())
                                    .header(RiskTestHeaders.CLIENT_IP, WARM_IP)
                                    .timeout(Duration.ofSeconds(15))
                                    .POST(HttpRequest.BodyPublishers.noBody());
                            if (device != null) {
                                b.header(RiskTestHeaders.DEVICE_ID, device);
                            }
                            client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                        }
                    } catch (Exception ignored) {
                        // 预热结果一律丢弃
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await(seconds + 60L, TimeUnit.SECONDS);
        }
        System.out.printf("预热：%d 秒 × %d 并发（主体 %s / %s，独立号段，不进测量窗口）%n",
                seconds, concurrency, warmUserId(p.seed()), WARM_IP);
    }

    /**
     * 黑名单探针：**跑之前先证明种子真的生效了**。
     *
     * <p>没有这一步，「拦截率 100%」与「种子根本没写进去、只是恰好都被别的规则拦了」
     * 在客户端侧长得一样。更糟的是另一种：种子写错了键（哈希不一致），黑名单静默失效，
     * 而压测照常跑完、报告里那个 100% 是假的。
     *
     * <p>判据分两侧，都比「被拒 / 没被拒」更严一格：
     *
     * <ul>
     *   <li>黑名单车道要求**恰好是** {@link Outcome#RISK_BLOCKED}。黑名单三条规则各 80 分，
     *       {@code ofScore} 取最高档，所以「被黑名单命中」的唯一可能输出就是 {@code BLOCK}。
     *       只写「是 {@code RISK_*} 就行」会漏掉 {@code RISK_UNAVAILABLE}——那是 fail-closed
     *       下预算耗尽的拒绝，与黑名单毫无关系，却会让自检在种子根本没写进去时照样通过。</li>
     *   <li>对照车道只认 {@code RISK_*} 为失败；{@code SLOT_FULL} 之类的业务码与风控无关。</li>
     * </ul>
     */
    private static void verifyBlacklistSeeds(HttpClient client, Profile p,
                                             List<Long> errandIds, Tokens tokens) throws Exception {
        int probes = 5;
        List<String> failures = new ArrayList<>();
        for (Lane lane : new Lane[]{Lane.BLACKLIST_USER, Lane.BLACKLIST_DEVICE,
                Lane.BLACKLIST_IP, Lane.CONTROL}) {
            for (int i = 0; i < probes; i++) {
                long n = 1_000_000L + (long) lane.ordinal() * probes + i;
                Request r = requestOf(p, lane, n, n, errandIds, tokens);
                HttpResponse<String> resp = client.send(r.request(),
                        HttpResponse.BodyHandlers.ofString());
                Outcome outcome = classifyResponse(resp.statusCode(), resp.body());
                String subject = "user=" + r.userId() + " ip=" + r.clientIp()
                        + " device=" + (r.deviceId() == null ? "-" : r.deviceId());
                if (lane == Lane.CONTROL) {
                    if (outcome.riskRejected()) {
                        failures.add("对照车道被风控拒绝（" + outcome + "，" + subject
                                + "）——对照组本身不干净");
                    }
                } else if (outcome != Outcome.RISK_BLOCKED) {
                    failures.add("黑名单车道 " + lane.label() + " 没有被拦（" + outcome + "，"
                            + subject + "）；黑名单命中时只可能是 RISK_BLOCKED");
                }
            }
        }
        if (!failures.isEmpty()) {
            System.err.println("黑名单种子自检失败：");
            failures.forEach(f -> System.err.println("  · " + f));
            System.err.println("请先用 BlacklistKeyTool 写好上面横幅里的三个键（add），再重跑。");
            System.err.println("这次运行不产生可用的数字，已中止。");
            throw probeFailure(failures);
        }
        System.out.printf("黑名单自检：%d 个车道 × %d 次探针通过（三类黑名单均为 RISK_BLOCKED、对照车道未被拒）%n",
                4, probes);
    }

    /**
     * 自检失败的异常。
     *
     * <p><b>这里必须是抛，不能是 {@code System.exit(3)}。</b>自检跑在 {@code startRun} 之后，
     * 直接退出会绕过 {@link #run} 的兜底，在 {@code bench_run} 里留下一行**永远 RUNNING**——
     * 与批次 1 记过的 F-39 同型。2026-09-12 的烟测里就这么留了一行
     * （{@code 20260912-213853}），事后要从「应用日志里根本没有这一轮」反推它是死的。
     *
     * <p>异常消息里带上每条失败明细：它会经 {@code failRun} 落进 {@code summary.error}，
     * 成为这一轮唯一随数据一起留下的解释（stderr 那几行只活在终端里）。
     */
    static IllegalStateException probeFailure(List<String> failures) {
        return new IllegalStateException("黑名单种子自检失败（" + failures.size() + " 项）："
                + String.join("；", failures) + "。请先用 BlacklistKeyTool 写好种子再重跑");
    }

    // ================================================================ 结果

    /** 业务码 → 客户端视角的动作。{@code ERROR} 是压测端自己的异常，不属于业务码。 */
    enum Outcome {
        OK, RISK_LIMITED, RISK_REVIEW_REQUIRED, RISK_BLOCKED, RISK_UNAVAILABLE,
        SLOT_FULL, ALREADY_GRABBED, GRAB_CONFLICT, GRAB_RATE_LIMITED, ERRAND_NOT_GRABBABLE,
        OTHER, ERROR;

        boolean riskRejected() {
            return this == RISK_LIMITED || this == RISK_REVIEW_REQUIRED
                    || this == RISK_BLOCKED || this == RISK_UNAVAILABLE;
        }
    }

    /**
     * 从响应体里认出业务码。
     *
     * <p>拿不到已知码时返回 {@link Outcome#OTHER} 而不是 {@code ERROR}：业务码是新加的
     * 还是代码写错了，这两件事要分得开——{@code ERROR} 会让整轮被判成基础设施失败。
     */
    static Outcome classifyResponse(int statusCode, String body) {
        if (body == null) {
            return Outcome.OTHER;
        }
        for (Outcome o : Outcome.values()) {
            if (o == Outcome.OTHER || o == Outcome.ERROR) {
                continue;
            }
            if (body.contains("\"code\":\"" + o.name() + "\"")) {
                return o;
            }
        }
        return Outcome.OTHER;
    }

    /** 按「车道 × 动作」计数 + 全局动作计数 + 基础设施错误分类。 */
    static final class Tally {
        private final Map<String, AtomicInteger> cells = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> errorTypes = new ConcurrentHashMap<>();

        void add(Lane lane, Outcome outcome) {
            bump(lane.label() + "|" + outcome.name());
            bump("total|" + outcome.name());
        }

        void noteError(Exception e) {
            String type = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            errorTypes.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
        }

        private void bump(String key) {
            cells.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }

        int count(String key) {
            AtomicInteger v = cells.get(key);
            return v == null ? 0 : v.get();
        }

        int total() {
            int sum = 0;
            for (Outcome o : Outcome.values()) {
                sum += count("total|" + o.name());
            }
            return sum;
        }

        Map<String, Integer> errorTypes() {
            return sorted(errorTypes);
        }

        String describeTotal() {
            StringBuilder sb = new StringBuilder();
            for (Outcome o : Outcome.values()) {
                int v = count("total|" + o.name());
                if (v > 0) {
                    sb.append(String.format("  %-20s %d%n", o.name(), v));
                }
            }
            return sb.toString();
        }

        String describeLanes() {
            StringBuilder sb = new StringBuilder();
            for (Lane lane : Lane.values()) {
                int laneTotal = 0;
                StringBuilder cellsText = new StringBuilder();
                for (Outcome o : Outcome.values()) {
                    int v = count(lane.label() + "|" + o.name());
                    if (v > 0) {
                        laneTotal += v;
                        cellsText.append(cellsText.isEmpty() ? "" : " ").append(o.name()).append('=').append(v);
                    }
                }
                if (laneTotal > 0) {
                    sb.append(String.format("  %-10s n=%-8d %s%n", lane.label(), laneTotal, cellsText));
                }
            }
            return sb.toString();
        }
    }

    private static String summaryJson(Profile p, int requests, long qps, long p50, long p95,
                                      long p99, long max, Tally tally) {
        StringBuilder byLane = new StringBuilder();
        for (Lane lane : Lane.values()) {
            int laneTotal = 0;
            StringBuilder cell = new StringBuilder();
            for (Outcome o : Outcome.values()) {
                int v = tally.count(lane.label() + "|" + o.name());
                if (v > 0) {
                    laneTotal += v;
                    cell.append(cell.isEmpty() ? "" : ",")
                            .append('"').append(o.name()).append("\":").append(v);
                }
            }
            if (laneTotal > 0) {
                byLane.append(byLane.isEmpty() ? "" : ",")
                        .append('"').append(lane.label()).append("\":{\"requests\":")
                        .append(laneTotal).append(',').append(cell).append('}');
            }
        }
        StringBuilder byOutcome = new StringBuilder();
        for (Outcome o : Outcome.values()) {
            byOutcome.append(byOutcome.isEmpty() ? "" : ",")
                    .append('"').append(o.name()).append("\":").append(tally.count("total|" + o.name()));
        }
        return String.format(
                "{\"scenario\":\"%s\",\"concurrency\":%d,\"durationSeconds\":%d,\"taskPool\":%d,"
                        + "\"userPool\":%d,\"seed\":%d,\"requests\":%d,\"qps\":%d,"
                        + "\"p50Ms\":%d,\"p95Ms\":%d,\"p99Ms\":%d,\"maxMs\":%d,"
                        + "\"byOutcome\":{%s},\"byLane\":{%s}}",
                p.scenario().scenarioName(), p.concurrency(), p.durationSeconds(), p.taskPool(),
                p.userPool(), p.seed(), requests, qps, p50, p95, p99, max,
                byOutcome, byLane);
    }

    private static long pct(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int index = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static Map<String, Integer> sorted(Map<String, AtomicInteger> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        raw.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicInteger>comparingByValue(
                        (a, b) -> Integer.compare(b.get(), a.get())))
                .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "<无响应体>";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }

    // ================================================================ 横幅

    /**
     * 打印本轮用到的主体与配置。
     *
     * <p><b>这一段是有意的现场记录</b>：主体的原始值（设备号、假 IP）在这里出现，
     * 与应用日志里「任何日志都不得打印原始 deviceId/clientIp」那条约束不冲突——
     * 那条管的是**生产代码**打印真实用户的标识，而这里是压测端在打印它自己编造的、
     * 每条都可从 seed 重新推导出来的合成标识。不打印它们，报告就无法被复现。
     */
    private static void printSubjects(Profile p, String runId) {
        long seed = p.seed();
        System.out.println("================ 本轮主体 ================");
        System.out.printf("runId=%s scenario=%s%n", runId, p.scenario().scenarioName());
        System.out.printf("用户池     : %s .. %s（%d 个，seed=%d）%n",
                poolUserId(seed, 0), poolUserId(seed, p.userPool() - 1), p.userPool(), seed);
        switch (p.scenario()) {
            case NORMAL, HOT_USER -> System.out.printf(
                    "IP        : %s .. %s（每个用户一个固定假 IP，X-Test-Client-Ip 注入）%n",
                    poolIp(0), poolIp(p.userPool() - 1));
            case SAME_DEVICE -> {
                System.out.printf("共用设备   : %s（X-Test-Device-Id，原始值由服务端哈希）%n",
                        sharedDeviceId(seed));
                System.out.printf("IP        : %s .. %s（每个用户一个，避免 IP 维度干扰）%n",
                        poolIp(0), poolIp(p.userPool() - 1));
            }
            case SAME_IP -> System.out.printf("共用 IP    : %s（X-Test-Client-Ip）%n", SHARED_IP);
            case BLACKLIST -> {
                System.out.printf("被拉黑用户 : %s%n", blacklistUserId(seed, p.userPool()));
                System.out.printf("被拉黑设备 : %s（X-Test-Device-Id）%n", blacklistDeviceId(seed));
                System.out.printf("被拉黑 IP  : %s（X-Test-Client-Ip）%n", BLACKLIST_IP);
                System.out.printf("对照组     : 用户池 %s.. / 干净设备 %s.. / 干净 IP %s..%n",
                        poolUserId(seed, 0), controlDeviceId(seed, 0), controlIp(0));
            }
            default -> throw new IllegalStateException("未处理的场景：" + p.scenario());
        }
        System.out.printf("身份       : 真实 token（POST /api/auth/login），应用**未开** allow-header-identity%n");
        System.out.printf("预热主体   : 用户 %s / IP %s（独立号段，不进测量窗口）%n",
                warmUserId(seed), WARM_IP);
        System.out.println();
        if (p.scenario() == Scenario.BLACKLIST) {
            System.out.println("== 跑之前必须先写好的黑名单种子（BlacklistKeyTool，设计 D4）==");
            System.out.printf("  BlacklistKeyTool add user   %s%n", blacklistUserId(seed, p.userPool()));
            System.out.printf("  BlacklistKeyTool add device %s%n", blacklistDeviceId(seed));
            System.out.printf("  BlacklistKeyTool add ip     %s%n", BLACKLIST_IP);
            System.out.println("== 收尾（跑完请移除；这些键没有 TTL，共享测试 Redis 不能留垃圾）==");
            System.out.printf("  BlacklistKeyTool remove user   %s%n", blacklistUserId(seed, p.userPool()));
            System.out.printf("  BlacklistKeyTool remove device %s%n", blacklistDeviceId(seed));
            System.out.printf("  BlacklistKeyTool remove ip     %s%n", BLACKLIST_IP);
            System.out.println();
        }
        System.out.println("==========================================");
        System.out.println();
    }
}
