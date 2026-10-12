package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.RiskEventGateType;

import java.util.Locale;

/**
 * 风控 Redis 键的唯一构造入口。
 *
 * <p>存在的理由与 {@link DeviceHasher} 相同：黑名单键有两个使用方——
 * 应用侧（P9.2 的精确黑名单查询）和运维侧（dash-bench 的 BlacklistKeyTool），
 * 两边必须算出逐字节相同的键。键格式散落成字符串字面量时，改一处忘一处
 * 就会让运维写进去的黑名单**永远查不到**，而且不报错。
 *
 * <p>目前已交付：滑动窗口键、设备/IP 聚集键（P9.1）、三类黑名单键（P9.1 交付键名、
 * P9.2 首次使用）、用户限频键（P9.2）、四类事件聚合闸门键（P9.4）。
 */
public final class RiskKeys {

    /** 三类黑名单共用前缀，便于 SCAN 与运维批量查看。 */
    private static final String BLACKLIST = "risk:bl:";

    /** 哈希后的标识长度，与 {@link DeviceHasher} 的截断长度一致。 */
    private static final String HASH_PATTERN = "[0-9a-f]{32}";

    /** 四类事件聚合闸门键的公共前缀（P9.4）。 */
    private static final String EVENT_GATE = "risk:event:gate:";

    /**
     * 实例标识的合法字符集（P9.4 D4）。
     *
     * <p>比 {@link #HASH_PATTERN} 宽——实例名是运维起的，不是哈希值——但同样是一条
     * **白名单**：左花括号、右花括号、冒号与空白都不在里面。为什么必须挡住花括号，
     * 见 {@link #eventGate(RiskEventGateType, String)}。
     */
    private static final String INSTANCE_ID_PATTERN = "[A-Za-z0-9._-]+";

    /**
     * 缺失维度传给 Lua 的占位键。
     *
     * <p>Redis 的 EVAL 要求 key 位置固定，所以即使某一维度不存在，
     * {@code risk_cluster.lua} 也必须收到三个 KEYS。脚本用 ARGV 里的开关跳过它，
     * <b>绝不访问</b>——它的全部意义就是「占着第 N 个位置」。
     *
     * <p>取这个名字是为了万一防线失效时一眼能认出来：它一旦出现在
     * {@code SCAN risk:*} 的结果里，就说明脚本的开关判断被改坏了。
     */
    public static final String ABSENT = "risk:_absent_";

    private RiskKeys() {
    }

    /**
     * 用户滑动窗口键。
     *
     * <p>{@code {} 里的 userId} 是 Redis Cluster 的 hash tag：同一用户的两个窗口键
     * 必须落在同一个 slot，否则 {@code risk_user_window.lua} 在 Cluster 下直接
     * CROSSSLOT 报错。单机模式下 hash tag 无害，提前写好，将来上 Cluster 不用改代码
     * （与 {@code RedisGrabSlotAdapter} 的做法一致）。
     */
    public static String userWindow(long userId, int windowSeconds) {
        return "risk:win:{" + userId + "}:" + windowSeconds + "s";
    }

    /** 设备关联账号集合。{@code deviceHash} 必须是哈希值，不是原始设备号。 */
    public static String deviceCluster(String deviceHash) {
        return "risk:dev:" + requireHash(deviceHash, "设备");
    }

    /** IP 关联账号集合。 */
    public static String ipAccounts(String ipHash) {
        return "risk:ip:" + requireHash(ipHash, "IP") + ":users";
    }

    /**
     * IP 速率窗口。
     *
     * <p>与 {@link #ipAccounts(String)} 共用 {@code {ipHash}} 这个 hash tag，
     * 因此两者在 Cluster 下同 slot——{@code risk_cluster.lua} 的 IP 部分跨这两个键是安全的。
     * （设备键与 IP 键之间就没有这个保证，见脚本头部的说明。）
     */
    public static String ipRate(String ipHash) {
        return "risk:ip:" + requireHash(ipHash, "IP") + ":rate";
    }

    public static String blacklistUser(long userId) {
        return BLACKLIST + "user:" + userId;
    }

    /**
     * @param deviceHash {@link DeviceHasher#hash(String)} 的输出，不是原始设备号
     */
    public static String blacklistDevice(String deviceHash) {
        return BLACKLIST + "device:" + requireHash(deviceHash, "设备");
    }

    /**
     * @param ipHash {@link DeviceHasher#hash(String)} 的输出，不是原始 IP
     */
    public static String blacklistIp(String ipHash) {
        return BLACKLIST + "ip:" + requireHash(ipHash, "IP");
    }

    /**
     * 用户级限频键（蓝图 §3.6）。只在 {@code ENFORCE} 模式下读写，TTL 取
     * {@code dash.risk.limit-cooldown-ms}。
     *
     * <p><b>花括号在这里是真字符</b>，与 {@link #userWindow(long, int)} 同型——
     * 它们是 Redis Cluster 的 hash tag。对单个键来说 hash tag 不起任何作用
     * （它只在一次操作涉及多个键、需要保证同 slot 时才有意义），这里保留是因为
     * 蓝图 §3.6 与实施计划 §6.2 都**逐字**写着 {@code risk:limit:user:{userId}}，
     * 而运维会照着文档敲 {@code redis-cli} 去查或手动解除限频。
     * 键名与文档不一致的后果是：敲出来的键应用永远读不到，限频看起来配好了却毫无作用。
     *
     * <p><b>与 {@link #blacklistUser(long)} 的不对称是刻意的</b>：那边输出
     * {@code risk:bl:user:1001}，没有花括号。两处都按各自文档的写法来，
     * <b>不要去「统一」它们</b>——黑名单键的写法已经被 P9.1 交付的
     * {@code BlacklistKeyTool} 与运维操作固化下来了，改名等于让已写入的黑名单全部失联。
     */
    public static String limitKey(long userId) {
        return "risk:limit:user:{" + userId + "}";
    }

    /**
     * 事件聚合闸门键，主体是**用户**（P9.4 D4）。
     *
     * <p>形状是 {@code risk:event:gate:{limit|review|block}:{userId}}，例如
     * {@code risk:event:gate:limit:{1001}}。与蓝图 §3.9 的键名示例相比**少了一段**：
     * 那边写的是 {@code ...:{userId}:{10sBucket}}，本阶段落地时把时间桶去掉了，
     * 窗口靠键的 TTL 表达（{@code SET key 1 NX EX 10}）。理由见 D4——带桶的方案
     * 在窗口边界上会泄漏（{@code t=9.9s} 与 {@code t=10.1s} 落进两个桶、各自都抢到），
     * 而「距上次发送满 N 秒才允许下一次」与规则「同一用户 10 秒最多一条」逐字对应。
     *
     * <p><b>花括号在这里是真字符</b>，理由与 {@link #limitKey(long)} 完全一样：
     * D4 与实施计划逐字写着这个形状，运维会照着 {@code redis-cli} 敲它去查聚合状态。
     * 键名与文档不一致的后果那边已经写过一次了。
     *
     * <p><b>为什么是两个重载而不是一个 {@code Object} 参数</b>：主体是 {@code long}
     * （用户）还是 {@code String}（实例标识）**由闸门类型唯一决定**，写成 {@code Object}
     * 就把「给 {@code LIMIT} 传实例名」这类错误从编译期挪到了运行期——而运行期它不会
     * 报错，只会算出一个没人会去查的键，聚合于是恒不生效。传错类型在这里直接抛
     * {@link IllegalArgumentException}。
     *
     * @param type   只能是 {@link RiskEventGateType#LIMIT} / {@link RiskEventGateType#REVIEW}
     *               / {@link RiskEventGateType#BLOCK}；{@code DEGRADED} 走
     *               {@link #eventGate(RiskEventGateType, String)}
     * @param userId 用户 id
     */
    public static String eventGate(RiskEventGateType type, long userId) {
        return gateKey(type, GateSubject.USER_ID, String.valueOf(userId));
    }

    /**
     * 事件聚合闸门键，主体是**实例**（P9.4 D4、D7）：{@code risk:event:gate:degraded:{instanceId}}。
     *
     * <p>降级按实例聚合、不按用户，理由写在 {@link RiskEventGateType#DEGRADED} 上。
     *
     * <p><b>为什么 {@code instanceId} 要校验</b>：这个键里真的有一对花括号，而左花括号
     * 是 Redis Cluster 的 hash tag 分隔符。实例名里混进一个右花括号，键的 hash tag
     * 就会**提前闭合**——{@code risk:event:gate:degraded:{a}b} 的 hash tag 是 {@code a}，
     * 而不是 {@code a} 加上一个右花括号。后果是两个不同的实例名可能算进同一个 slot
     * （也可能不，取决于名字），而这类问题只在上了 Cluster 之后才现形，单机下永远
     * 测不出来。所以这里和 {@link #requireHash(String, String)} 一样是白名单校验，
     * 只是换了一条正则：实例名是运维起的（{@code app-1}、{@code worker.b}），
     * 不是 32 位十六进制哈希。
     *
     * <p>非法值抛 {@link IllegalArgumentException}，与 {@link #requireHash(String, String)}
     * 一致——键名构造器是纯函数，不该产生一个「看起来合法但没人查得到」的键。
     *
     * @param type       只能是 {@link RiskEventGateType#DEGRADED}
     * @param instanceId 实例标识，{@code [A-Za-z0-9._-]+}
     */
    public static String eventGate(RiskEventGateType type, String instanceId) {
        return gateKey(type, GateSubject.INSTANCE_ID, requireInstanceId(instanceId));
    }

    /**
     * 包内入口：主体已经是 {@code String} 时的分派（{@link RedisRiskEventGate} 用）。
     *
     * <p>闸门拿到的主体是上游给的一个字符串，而它**必须**分派到上面两个重载之一——
     * 于是「这个闸门类型的主体是谁」这个判断出现了第二个使用点。把它写在这里而不是
     * 在闸门里再抄一份 {@code switch}：两份 switch 各自都能编译通过，加第五类闸门时
     * 编译器只会点出其中一处（有 switch 表达式的那处），另一处会静静地漏掉。
     *
     * <p>名字不叫 {@code eventGate}：那会和上面那个
     * {@code (RiskEventGateType, String)} 重载**撞签名**——一个类里不能有两个只差
     * 访问修饰符的同签名方法。所以取了个显式的名字，代价是调用点略啰嗦。
     *
     * <p>用户主体按 {@code long} 解析后再渲染，因此 {@code "1001"} 与 {@code "01001"}
     * 会归一到同一个键，与 {@link #eventGate(RiskEventGateType, long)} 的输出逐字节相同。
     * 解析失败抛 {@link NumberFormatException}，它是 {@link IllegalArgumentException}
     * 的子类——与另外两个入口的约定一致。
     */
    static String eventGateFor(RiskEventGateType type, String subject) {
        return subjectOf(type) == GateSubject.USER_ID
                ? gateKey(type, GateSubject.USER_ID, String.valueOf(Long.parseLong(subject)))
                : gateKey(type, GateSubject.INSTANCE_ID, requireInstanceId(subject));
    }

    /**
     * 闸门主体是用户还是实例——**由闸门类型唯一决定**，这里是唯一的判断点。
     *
     * <p>写成不带 {@code default} 的 switch **表达式**是刻意的：将来加第五类闸门时，
     * 它会因为「没有覆盖全部取值」直接编译失败。这正是
     * {@link RiskEventGateType} 的类注释承诺的那处摩擦——加闸门的人会被编译器按着头
     * 同时来看键名段与 TTL 两处。
     */
    private static GateSubject subjectOf(RiskEventGateType type) {
        return switch (type) {
            case LIMIT, REVIEW, BLOCK -> GateSubject.USER_ID;
            case DEGRADED -> GateSubject.INSTANCE_ID;
        };
    }

    /**
     * 键名拼装的**唯一**出口：先验主体种类，再拼。
     *
     * <p>段名由枚举名现算（{@link RiskEventGateType} 的类注释要求如此），于是键名段
     * 与 {@link RiskEventGateType#windowSeconds()} 不可能漂移——两者同源于一个枚举常量。
     *
     * <p><b>{@code Locale.ROOT} 不是可有可无的</b>：枚举名里有大写的 {@code I}
     * （{@code LIMIT}、{@code REVIEW}），而土耳其语区的 {@code toLowerCase()} 会把
     * {@code 'I'} 变成无点的 {@code 'ı'}，键名就成了 {@code risk:event:gate:lımıt}——
     * 应用照常读写、不报错，只是换一台默认 locale 不同的机器就查不到自己写的键。
     */
    private static String gateKey(RiskEventGateType type, GateSubject expected, String subject) {
        GateSubject actual = subjectOf(type);
        if (actual != expected) {
            throw new IllegalArgumentException(
                    "闸门 " + type + " 的主体是" + actual.description + "，不是" + expected.description
                            + "。两个重载的区别就在这里：给 " + RiskEventGateType.DEGRADED
                            + " 传用户 id（或反过来）不会报错，只会算出一个人人都查不到的键，"
                            + "聚合于是恒不生效。");
        }
        return EVENT_GATE + type.name().toLowerCase(Locale.ROOT) + ":{" + subject + "}";
    }

    /**
     * 拦住实例名里的花括号。
     *
     * <p>与 {@link #requireHash(String, String)} 是同一类防线：非法值在这里抛出去，
     * 而不是拼出一个语法合法、无人查得到的键。后果的完整说明见
     * {@link #eventGate(RiskEventGateType, String)}。
     */
    private static String requireInstanceId(String instanceId) {
        if (instanceId == null || !instanceId.matches(INSTANCE_ID_PATTERN)) {
            throw new IllegalArgumentException(
                    "实例标识只允许 [A-Za-z0-9._-]+，收到的是：" + instanceId + "。"
                            + "闸门键里那一对花括号是 Redis Cluster 的 hash tag 分隔符，"
                            + "实例名里混进 } 会让 hash tag 提前闭合，两个实例的键可能撞进同一个 slot——"
                            + "而这只在上了 Cluster 之后才现形。");
        }
        return instanceId;
    }

    /**
     * 闸门主体的两种形态。做成枚举而不是布尔，是为了让
     * {@link #subjectOf(RiskEventGateType)} 那个 switch 的返回值自解释，
     * 并且给错误消息提供 {@link #description}。
     */
    private enum GateSubject {

        USER_ID("用户 id"),
        INSTANCE_ID("实例标识");

        private final String description;

        GateSubject(String description) {
            this.description = description;
        }
    }

    /**
     * 拦住「传了原始值而不是哈希值」。
     *
     * <p>这不是防御性编程，是防一类具体的静默故障：若把原始 IP 直接拼进键，
     * 得到的键语法完全合法、写进去也成功，只是应用查询时用的是哈希值，
     * **永远查不到**。黑名单看起来配好了，实际毫无作用，且没有任何报错。
     */
    private static String requireHash(String hash, String dimension) {
        if (hash == null || !hash.matches(HASH_PATTERN)) {
            throw new IllegalArgumentException(
                    dimension + "黑名单键需要 DeviceHasher 输出的 32 位十六进制哈希值，"
                            + "收到的是原始值或非法格式：" + hash + "。"
                            + "直接把原始 IP 拼进键写进去不会报错，但应用永远查不到。");
        }
        return hash;
    }
}
