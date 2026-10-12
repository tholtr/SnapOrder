package com.campusdash.bench;

import com.campusdash.infrastructure.risk.DeviceHasher;
import com.campusdash.infrastructure.risk.RiskKeys;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Locale;

/**
 * 风控黑名单的键计算 + 写入工具。
 *
 * <p>本轮不提供黑名单管理 API（蓝图 §3.5），黑名单靠初始化脚本、Redis CLI 或测试
 * fixture 写入。但设备与 IP 的键是 HMAC 后的值，运维没法手算——没有这个工具，
 * P9.2 的黑名单根本写不进去。
 *
 * <p><b>为什么必须复用应用的 DeviceHasher 而不是另写一份</b>：工具算出的键与应用
 * 查询的键必须逐字节相同。复制一份 HMAC 逻辑迟早漂移，而漂移的后果是黑名单
 * <b>静默失效</b>——键写得进去、语法合法、查询也不报错，就是永不命中。
 *
 * <p><b>为什么密钥只从环境变量读、不接受命令行参数</b>：命令行里的密钥会出现在
 * {@code ps} 输出和 shell 历史里。工具与应用读同一个环境变量，既避免了密钥泄露，
 * 也顺带保证了「工具和应用用的是同一个密钥」——手敲密钥敲错一个字符就会落进
 * 上面说的静默失效。
 *
 * <p><b>为什么写入必须是显式的动词</b>：{@code add} / {@code remove} 是单独的
 * 动作，两参数的旧写法（只算键）**永远不会写**。黑名单是生产数据，
 * 「以为只是算个键、结果写进去了」比「以为写进去了、结果只算了个键」危险得多——
 * 后者再跑一次就好，前者可能把一个正常用户永久封禁。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... BlacklistKeyTool user   1001                 # 只算键
 *   java -cp ... BlacklistKeyTool device &lt;原始设备号&gt;
 *   java -cp ... BlacklistKeyTool ip     1.2.3.4
 *
 *   java -cp ... BlacklistKeyTool add    device &lt;原始设备号&gt;  # 算键 + 写入
 *   java -cp ... BlacklistKeyTool remove ip     1.2.3.4        # 算键 + 解除
 * </pre>
 * 需要环境变量 {@code DASH_RISK_DEVICE_HMAC_SECRET}（密钥）；
 * 可选 {@code DASH_REDIS_HOST} / {@code DASH_REDIS_PORT}（默认 {@value #DEFAULT_HOST}:{@value #DEFAULT_PORT}，
 * 与主配置的 {@code spring.data.redis} 一致）。<b>端点必须可覆盖</b>：
 * 一个只能连本机 Redis 的工具，在生产上毫无用处，运维只能回去手拼
 * {@code redis-cli}——那正是本工具要消灭的动作。
 *
 * <p><b>本工具同时固定了黑名单的存储形态</b>：Redis Set，成员为 {@code 1}，
 * 存在即拉黑。蓝图 §3.5 只说了「精确键/集合判断」，具体形态由这里定下来，
 * P9.2 的查询侧必须用同样的形态读取。
 *
 * <p><b>但没有 TTL</b>：拉黑是永久的，直到显式 {@code remove}。这也是 {@code remove}
 * 用 {@code DEL} 而不是 {@code SREM} 的原因——查询侧 {@code RedisRiskBlacklistAdapter}
 * 用的是 {@code EXISTS}，与键的类型无关，所以一个被手工 {@code SET} 成字符串的键
 * <b>同样会被当成命中</b>。{@code DEL} 对两种形态都有效，{@code SREM} 只对 Set 有效。
 */
public final class BlacklistKeyTool {

    private static final String SECRET_ENV = "DASH_RISK_DEVICE_HMAC_SECRET";
    private static final String HOST_ENV = "DASH_REDIS_HOST";
    private static final String PORT_ENV = "DASH_REDIS_PORT";

    /** 与主配置 {@code spring.data.redis.host/port} 的默认值一致。 */
    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 6380;

    private static final String DEFAULT_ENDPOINT = DEFAULT_HOST + ":" + DEFAULT_PORT;

    /** 只算键、不写。**唯一不改动 Redis 的动作**，也是两参数写法的默认。 */
    static final String ACTION_KEY = "key";
    static final String ACTION_ADD = "add";
    static final String ACTION_REMOVE = "remove";

    /** 黑名单集合的成员值。用固定值而不是 userId，是为了让键本身承载全部语义。 */
    private static final String MEMBER = "1";

    /** Redis 的「键不存在」TTL 读数是 -2，「无过期」是 -1，两者都不是错误码。 */
    private static final long TTL_NO_EXPIRY = -1L;
    private static final long TTL_MISSING = -2L;

    private BlacklistKeyTool() {
    }

    public static void main(String[] args) {
        Command command;
        try {
            command = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        String secret = System.getenv(SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            System.err.println("缺少环境变量 " + SECRET_ENV + "。");
            System.err.println("工具与应用必须使用同一个密钥，否则算出的键对不上。");
            System.exit(2);
            return;
        }

        try {
            DeviceHasher hasher = new DeviceHasher(secret);
            String dimension = command.dimension();
            String rawIdentifier = command.rawIdentifier();

            // 先算键、再连 Redis：维度写错、userId 不是数字这类问题必须在**打开连接之前**
            // 就报出来，而不是连上生产库之后才发现自己连的是生产库。
            String key = keyOf(dimension, rawIdentifier, hasher);

            switch (command.action()) {
                case ACTION_KEY -> System.out.println(
                        buildReport(dimension, rawIdentifier, hasher, endpointFromEnv()));
                case ACTION_ADD -> {
                    Outcome outcome = add(endpointFromEnv(), key);
                    System.out.println(buildAddReport(dimension, rawIdentifier, hasher,
                            outcome.endpoint(), outcome));
                    requireVerified(outcome.existsAfter(), "写入后键仍不可见");
                }
                case ACTION_REMOVE -> {
                    Outcome outcome = remove(endpointFromEnv(), key);
                    System.out.println(buildRemoveReport(dimension, rawIdentifier, hasher,
                            outcome.endpoint(), outcome));
                    requireVerified(!outcome.existsAfter(), "解除后键仍然存在");
                }
                default -> throw new IllegalStateException("未处理的动作：" + command.action());
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        } catch (RuntimeException e) {
            // Redis 连不上、认证失败、键类型不对都会走到这里。**不要吞掉异常**
            // 然后打印一份看起来很正常的报告——那是本模块最怕的「静默」。
            System.err.println("Redis 操作失败：" + e);
            System.exit(3);
        }
    }

    /**
     * 命令行解析结果。抽成记录而不是在 {@link #main} 里就地读 {@code args}，
     * 是为了让「哪个动作」这件事能被断言（见 {@link #parse}）。
     *
     * @param action {@link #ACTION_KEY} / {@link #ACTION_ADD} / {@link #ACTION_REMOVE}，小写
     */
    record Command(String action, String dimension, String rawIdentifier) {
    }

    /**
     * 解析命令行。
     *
     * <p><b>抽出来当纯函数是为了能被单测钉住一条性质：两参数的旧写法永远解析成
     * {@link #ACTION_KEY}，也就是只算键、不写。</b>本工具最危险的行为是「写」——
     * 黑名单是生产数据，「以为只是算个键、结果写进去了」比「以为写进去了、
     * 结果只算了个键」危险得多：后者再跑一次就好，前者可能把一个正常用户永久封禁。
     * 这条性质如果只写在注释里，改动参数解析时没有任何东西会拦住它。
     *
     * <p>三参数时首个参数是动作，所以 {@code key user 1001} 与 {@code user 1001} 等价。
     */
    static Command parse(String[] args) {
        if (args.length == 2) {
            return new Command(ACTION_KEY, args[0], args[1]);
        }
        if (args.length != 3) {
            throw new IllegalArgumentException(usage());
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!ACTION_KEY.equals(action) && !ACTION_ADD.equals(action) && !ACTION_REMOVE.equals(action)) {
            throw new IllegalArgumentException(
                    "未知动作「" + args[0] + "」，只能是 " + ACTION_KEY + " / " + ACTION_ADD + " / " + ACTION_REMOVE);
        }
        return new Command(action, args[1], args[2]);
    }

    /**
     * 退出码跟着核验走，让这个工具能被脚本用：「跑完了」和「生效了」不是一回事，
     * 而黑名单失效是一条不报错的失败路径。
     */
    private static void requireVerified(boolean ok, String what) {
        if (!ok) {
            System.err.println("核验失败：" + what + "，请人工确认。");
            System.exit(3);
        }
    }

    private static String usage() {
        return """
                用法：BlacklistKeyTool <user|device|ip> <标识>                    只算键，不写入
                   或：BlacklistKeyTool <key|add|remove> <user|device|ip> <标识>

                  user   用户维度，标识为 userId（数字，不哈希）
                  device 设备维度，标识为原始设备号（工具负责哈希）
                  ip     IP 维度，标识为原始 IP（工具负责哈希）

                  key    只算键并打印可直接粘贴的命令（默认，两参数写法等价于它）
                  add    算键并写入黑名单（幂等：已存在则只是复核一遍）
                  remove 算键并解除黑名单（幂等：不存在则什么都不做）

                写入是永久的：这些键不带 TTL，直到显式 remove。写入必须是一个
                显式的动词——旧的两参数写法永远不会写。

                环境变量：
                  %s  密钥（必填）
                  %s        Redis 地址，默认 %s
                  %s        Redis 端口，默认 %d""".formatted(
                SECRET_ENV, HOST_ENV, DEFAULT_HOST, PORT_ENV, DEFAULT_PORT);
    }

    /**
     * 生成「只算键」的报告。与 {@link #main} 分开是为了能被单测直接覆盖——
     * main 里只有参数解析和输出，没有可测的逻辑。
     */
    static String buildReport(String dimension, String rawIdentifier, DeviceHasher hasher) {
        return buildReport(dimension, rawIdentifier, hasher, DEFAULT_ENDPOINT);
    }

    /**
     * 端点作为参数而不是内部读环境变量：报告里的 {@code redis-cli} 命令**必须指向
     * 工具实际用的那个库**。写死默认端口的话，在生产上照着复制的命令会打到本机 Redis——
     * 不报错，只是黑名单没生效。
     */
    static String buildReport(String dimension, String rawIdentifier, DeviceHasher hasher, String endpoint) {
        String key = keyOf(dimension, rawIdentifier, hasher);

        return """
                维度     : %s
                原始值   : %s
                %sRedis 键 : %s

                写入（存在即拉黑）：
                  redis-cli -u redis://%s SADD %s %s

                核对：
                  redis-cli -u redis://%s SISMEMBER %s %s

                或者让本工具直接写（推荐：键与应用同源，且写前写后各核验一次）：
                  BlacklistKeyTool add %s %s""".formatted(
                dimension.toLowerCase(Locale.ROOT), rawIdentifier, hashLine(dimension, key),
                key, endpoint, key, MEMBER, endpoint, key, MEMBER,
                dimension.toLowerCase(Locale.ROOT), rawIdentifier);
    }

    /** {@code add} 之后的报告。 */
    static String buildAddReport(String dimension, String rawIdentifier, DeviceHasher hasher,
                                 String endpoint, Outcome outcome) {
        String key = keyOf(dimension, rawIdentifier, hasher);
        String verb = outcome.existedBefore() ? "已存在，本次只是复核" : "已写入（此前不存在）";

        return """
                维度     : %s
                原始值   : %s
                %sRedis 键 : %s
                Redis 端点 : %s

                结果     : %s
                成员数   : %d
                TTL      : %s
                核验     : 应用侧同一条 EXISTS 判定 → %d

                解除封禁：
                  BlacklistKeyTool remove %s %s""".formatted(
                dimension.toLowerCase(Locale.ROOT), rawIdentifier, hashLine(dimension, key),
                key, endpoint, verb, outcome.memberCount(), ttlText(outcome.ttlSeconds()),
                outcome.existsAfter() ? 1 : 0,
                dimension.toLowerCase(Locale.ROOT), rawIdentifier);
    }

    /** {@code remove} 之后的报告。 */
    static String buildRemoveReport(String dimension, String rawIdentifier, DeviceHasher hasher,
                                    String endpoint, Outcome outcome) {
        String key = keyOf(dimension, rawIdentifier, hasher);
        String verb = outcome.existedBefore() ? "已解除（此前是黑名单）" : "本来就不在黑名单里，无事发生";

        return """
                维度     : %s
                原始值   : %s
                %sRedis 键 : %s
                Redis 端点 : %s

                结果     : %s
                核验     : 应用侧同一条 EXISTS 判定 → %d

                如需重新封禁：
                  BlacklistKeyTool add %s %s""".formatted(
                dimension.toLowerCase(Locale.ROOT), rawIdentifier, hashLine(dimension, key),
                key, endpoint, verb, outcome.existsAfter() ? 1 : 0,
                dimension.toLowerCase(Locale.ROOT), rawIdentifier);
    }

    /**
     * 写入动作执行后的**原始读数**，工具不做好坏判断。
     *
     * @param endpoint     这次操作打在哪个库上。与读数放在同一个记录里，否则报告里
     *                     会出现一个数字却说不清它来自哪个 Redis（本机还是生产）
     * @param existedBefore 动作之前键是否已存在（{@code EXISTS}，与查询侧同一条命令）
     * @param existsAfter   动作之后键是否还存在。核验就是拿它跟预期比
     * @param memberCount   动作之后的 {@code SCARD}。非 Set 类型的键会让这条命令报错，
     *                      那种情况在写之前就已经抛异常了，走不到构造记录
     * @param ttlSeconds    动作之后的 {@code TTL}：-1 永久，-2 键不存在
     */
    record Outcome(String endpoint, boolean existedBefore, boolean existsAfter,
                   long memberCount, long ttlSeconds) {
    }

    private static Outcome add(String endpoint, String key) {
        return withConnection(endpoint, commands -> {
            boolean existedBefore = exists(commands, key);
            commands.sadd(key, MEMBER);
            return new Outcome(endpoint, existedBefore, exists(commands, key),
                    commands.scard(key), commands.ttl(key));
        });
    }

    private static Outcome remove(String endpoint, String key) {
        return withConnection(endpoint, commands -> {
            boolean existedBefore = exists(commands, key);
            // DEL 而不是 SREM：查询侧用 EXISTS，与键的类型无关，所以一个被手工
            // SET 成字符串的键同样算命中。DEL 对两种形态都有效。
            commands.del(key);
            return new Outcome(endpoint, existedBefore, exists(commands, key),
                    commands.scard(key), commands.ttl(key));
        });
    }

    private interface RedisAction {
        Outcome apply(RedisCommands<String, String> commands);
    }

    /**
     * 连接、执行、关闭。
     *
     * <p>不确定式重试：写黑名单没有幂等之外的诉求（SET/SADD/DEL 都是幂等的），
     * 失败就如实失败并让退出码非零，让运维看见。
     */
    private static Outcome withConnection(String endpoint, RedisAction action) {
        RedisClient client = RedisClient.create(RedisURI.create("redis://" + endpoint));
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            return action.apply(connection.sync());
        } finally {
            client.shutdown();
        }
    }

    private static boolean exists(RedisCommands<String, String> commands, String key) {
        Long count = commands.exists(key);
        return count != null && count > 0L;
    }

    /**
     * {@code keyOf} 的结果里最后一段是哈希值，打印出来便于排查——用户维度没有哈希步骤，
     * 也就没有这一行。
     */
    private static String hashLine(String dimension, String key) {
        if (dimension.equalsIgnoreCase("user")) {
            return "";
        }
        return "哈希值   : " + key.substring(key.lastIndexOf(':') + 1) + System.lineSeparator();
    }

    /**
     * TTL 的三种读数分开说。
     *
     * <p>正数那一档要**显眼地报警**：手写进去的键可能带过期时间，而带 TTL 的黑名单
     * 会自己消失——不报错、不告警，只是某天开始不再拦截。这正是本模块反复出现的
     * 那类静默失效，所以它不能只显示成一个数字。
     */
    private static String ttlText(long ttlSeconds) {
        if (ttlSeconds == TTL_NO_EXPIRY) {
            return "-1（永久，不会自动解除）";
        }
        if (ttlSeconds == TTL_MISSING) {
            return "-2（键不存在）";
        }
        return ttlSeconds + " 秒后过期——**这个黑名单会自己失效**，请确认是不是手写键时带了 TTL";
    }

    private static String keyOf(String dimension, String rawIdentifier, DeviceHasher hasher) {
        return switch (dimension.toLowerCase(Locale.ROOT)) {
            case "user" -> RiskKeys.blacklistUser(parseUserId(rawIdentifier));
            case "device" -> RiskKeys.blacklistDevice(hasher.hash(rawIdentifier));
            case "ip" -> RiskKeys.blacklistIp(hasher.hash(rawIdentifier));
            default -> throw new IllegalArgumentException(
                    "未知维度「" + dimension + "」，只能是 user / device / ip");
        };
    }

    private static long parseUserId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("用户维度的标识必须是数字 userId，收到：" + raw);
        }
    }

    private static String endpointFromEnv() {
        String host = envOrDefault(HOST_ENV, DEFAULT_HOST);
        String rawPort = envOrDefault(PORT_ENV, String.valueOf(DEFAULT_PORT));
        int port;
        try {
            port = Integer.parseInt(rawPort.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    PORT_ENV + " 必须是数字端口，收到：" + rawPort);
        }
        return host + ":" + port;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
