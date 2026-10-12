package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.RiskMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 风控配置。
 *
 * 这是本仓库第一个 @ConfigurationProperties。仓库既有写法是 @Value 内联默认值
 * （仅 infra 模块就有 20 处），改用绑定类的理由是：风控配置同属一个前缀且数量多，
 * 若沿用 @Value，RedisRiskFeatureAdapter 的构造函数要背 9～10 个参数，
 * 单测里手搓适配器时参数顺序写错还不报错。
 *
 * presentation 用的两个 key 不在这个类里（test-context-enabled、trusted-proxy-cidrs）：
 * presentation 只依赖 application，看不见 dash-infrastructure，
 * 那两个 key 由 RiskContextInterceptor 与 ClientIpResolver 各自用 @Value 读取。
 *
 * <p>成员上限（cap）不作为配置项，而是由阈值推导（cap = threshold + 1）。
 * 见 {@link UserWindow#shortCap()} 的说明。
 *
 * <p><b>P9.4 新增的两项与同步决策无关</b>：{@code eventAggregationEnabled} 只决定
 * 「有几条风险事件上了 MQ」，{@code instanceId} 只参与降级闸门的键名。蓝图 §3.9 的硬约束
 * 是「聚合不得改变同步风险决策结果」——关掉聚合，同一个请求的 {@code RiskDecision}
 * 逐字段不变，变的只是它有没有被写进 broker。
 *
 * <p>{@code mode} 直接用领域枚举 {@link RiskMode}，不做 String → 枚举的转换层：
 * 写错值时 Spring 在绑定期就报「无法把 X 转成 RiskMode」，比自定义解析更早、更明确。
 * infrastructure 依赖 domain 是允许的方向（ArchUnit 只禁 domain 反向依赖）。
 */
@ConfigurationProperties(prefix = "dash.risk")
public record RiskProperties(
        @DefaultValue("true") boolean enabled,
        String deviceHmacSecret,
        @DefaultValue("SHADOW") RiskMode mode,
        @DefaultValue("true") boolean failOpen,
        @DefaultValue("10") int deadlineMs,
        @DefaultValue("10000") int limitCooldownMs,
        @DefaultValue("1") String ruleVersion,
        @DefaultValue UserWindow userWindow,
        @DefaultValue DeviceWindow deviceWindow,
        @DefaultValue IpAccountWindow ipAccountWindow,
        @DefaultValue IpRateWindow ipRateWindow,
        @DefaultValue("true") boolean eventAggregationEnabled,
        String instanceId) {

    /**
     * 只做必须在绑定期完成的事：必填校验，以及两类键的紧凑编码校验
     * （ZSet 走 {@link #requireCompactEncoding}，Set 走 {@link #requireCompactIntset}）。
     *
     * <p>关于 deviceHmacSecret 为什么不用 @Validated + @NotBlank：
     * 本仓库没有任何 validation 依赖，加 spring-boot-starter-validation 只为校验
     * 一个字段不成比例；更要紧的是注解在缺依赖时会**静默失效**——没有 validator 时
     * @NotBlank 不报错、只是不生效，应用带着空密钥正常启动，比没有校验更糟。
     * 紧凑构造器在绑定期就抛，时机与校验注解相同，且能用普通单测覆盖（不需要 Spring 上下文）。
     */
    public RiskProperties {
        if (deviceHmacSecret == null || deviceHmacSecret.isBlank()) {
            throw new IllegalStateException(
                    "dash.risk.device-hmac-secret 未配置或为空白。"
                            + "本地运行请用 DASH_RISK_DEVICE_HMAC_SECRET=<secret> mvn spring-boot:run；"
                            + "集成测试的值在 dash-bootstrap/src/test/resources/application.yaml。"
                            + "该密钥用于设备/IP 标识的 HMAC，缺失时不能退化为明文或固定值。");
        }
        // 阈值对与冷却时长：这两类配错的后果都是「规则静默变形」而不是启动报错，
        // 所以必须在这里拦住，说明见各自的方法注释。
        requireThresholdPair(deviceWindow.lowThreshold(), deviceWindow.threshold(),
                "dash.risk.device-window.low-threshold", "dash.risk.device-window.threshold");
        requirePositiveCooldown(limitCooldownMs);
        requireNonNegativeDeadline(deadlineMs);
        // ZSet 型（滑窗）：受 listpack -> skiplist 的编码悬崖约束。
        requireCompactEncoding(userWindow.shortCap(), "dash.risk.user-window.short-threshold");
        requireCompactEncoding(userWindow.longCap(), "dash.risk.user-window.long-threshold");
        requireCompactEncoding(ipRateWindow.cap(), "dash.risk.ip-rate-window.threshold");
        // Set 型（设备/IP 账号聚集）：成员是整数 userId，走 intset 而非 listpack，
        // 但 intset 同样有编码悬崖，只是阈值不同、跳变更陡——见下面的实测数字。
        requireCompactIntset(deviceWindow.cap(), "dash.risk.device-window.threshold");
        requireCompactIntset(ipAccountWindow.cap(), "dash.risk.ip-account-window.threshold");
        requireNonBlankInstanceId(instanceId);
    }

    /**
     * 实例标识不能为空白（P9.4 决策 D5）。
     *
     * <p>它没有 {@code @DefaultValue}，所以「完全没配」在绑定期就失败了——这一条挡的是
     * 另一种情形：**配了，但配成空串**。yaml 里写 {@code instance-id: ${HOSTNAME:}}、
     * 或者直接把值留空，都会绑出一个空字符串。
     *
     * <p>后果不是启动失败，而是**闸门语义静默改变**：降级闸门键是
     * {@code risk:event:gate:degraded:{instanceId}}，标识为空时所有实例算出同一个键，
     * 闸门从「每实例 10 秒一条」变成「全局 10 秒一条」。指标上看不出来，只是分母错了——
     * 而 P9.5 读抑制率时的分母正是实例数。空白的标识在键名里也看不见（渲染出来就是
     * 一段空白），肉眼与按分隔符切分的脚本都发现不了。
     */
    private static void requireNonBlankInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalStateException(
                    "dash.risk.instance-id 未配置或为空白。它是降级事件聚合闸门键的主体"
                            + "（risk:event:gate:degraded:{instanceId}），空白会让所有实例"
                            + "算出同一个键，闸门从「每实例一条」悄悄变成「全局一条」。"
                            + "容器里用 ${HOSTNAME:unknown}，测试 yaml 里写死一个可预测的字面量。");
        }
    }

    /**
     * 守住设备维度这一对阈值的取值域：{@code 1 <= low < high}。
     *
     * <p>上界（{@code low < high}）：设备的两条规则是**同一个计数**上的两档
     * （蓝图 §3.3，P9.2 决策 D2），在领域层写成一个 {@code else if} 链——
     * {@code if (accounts > high) …… else if (accounts > low) ……}。
     * low 一旦 ≥ high，低档那一支**永远走不到**：不是少算分，而是规则表里
     * 标注为「REVIEW」的那行变成一句谎话，且没有任何报错。反过来若写成
     * 两条独立判断，同一个账号数会被计两次分（9 个账号得 60 + 80 = 140）。
     * 两种写法都不对，所以配置期就必须堵住这个取值域。
     *
     * <p>下界（{@code low >= 1}）：这条是设计文档 §6.2 之外补的，防的是
     * 「想关掉低档规则」这个很自然的运维动作——最顺手的两种写法恰好都是灾难：
     *
     * <pre>
     *   low-threshold: 0     deviceAccounts > 0  恒真 → 每个带设备的请求都加 60 分
     *   low-threshold: -1    deviceAccounts > -1 恒真 → 同上
     * </pre>
     *
     * <p>后果不是「低档规则失效」，而是**全部带设备维度的请求都被判 REVIEW**，
     * 一次配置笔误就是全站拦截。本阶段没有提供「关掉低档」的开关，
     * 真要关得走代码或后续阶段加显式开关，不能靠填一个数来暗示。
     */
    private static void requireThresholdPair(int low, int high, String lowKey, String highKey) {
        if (low < 1) {
            throw new IllegalStateException(
                    "风控低阈值 " + low + "（" + lowKey + "）必须至少为 1。"
                            + "取 0 或负数会让「账号数 > 低阈值」恒真，"
                            + "每个带设备维度的请求都被判 REVIEW，且不报错。"
                            + "本阶段不提供「关闭低档规则」的配置开关，不要用 0/-1 来表达关闭。");
        }
        if (low >= high) {
            throw new IllegalStateException(
                    "风控低阈值 " + low + "（" + lowKey + "）必须严格小于高阈值 "
                            + high + "（" + highKey + "）。"
                            + "两条规则判定的是同一个计数，实现为 else-if 链取最高档；"
                            + "低阈值不低于高阈值时低档分支永远走不到，"
                            + "规则表里标着 REVIEW 的这一档成了永远不触发的死配置。");
        }
    }

    /**
     * 限频冷却时长必须为正。
     *
     * <p>它同时是 {@code risk:limit:user:{userId}} 的 TTL。填 0 或负数时
     * Redis 要么直接报错（EXPIRE 收到负值会删键），要么让键立刻过期——
     * 两种结果都是「限频键写进去就没了」，而调用方看到的是写入成功。
     * 于是限频规则静默失效：该被拦住的下一次请求畅通无阻，日志里也查不出原因。
     */
    private static void requirePositiveCooldown(int limitCooldownMs) {
        if (limitCooldownMs <= 0) {
            throw new IllegalStateException(
                    "dash.risk.limit-cooldown-ms 必须为正，当前为 " + limitCooldownMs + "。"
                            + "它同时是 risk:limit:user:{userId} 的 TTL，"
                            + "非正值会让限频键写完即刻过期，限频静默失效。");
        }
    }

    /**
     * 预检的时间预算必须非负。<b>0 是合法值，负数不是</b>（P9.3 决策 D11）。
     *
     * <p>0 的语义是「一步都不许跑」：预检从第一毫秒起就判定预算耗尽，直接走降级路径。
     * 这是故障降级**唯一的纯配置触发方式**——不需要关 Redis、不需要 mock，
     * 一个配置项就能确定性地跑一遍真实的降级分支（降级并发 IT 用的就是它），
     * 所以它必须能通过启动校验。把它当成「没配」而拒掉，等于把这条路堵死。
     *
     * <p>负数则完全是另一回事：它让**每一次**预检都从第一毫秒起就超支，
     * 配合 {@code fail-open=false} 就是全站抢单不可用。而它在配置里看起来
     * 只是「把超时配小了一点」，不像一次故障——正是这种「看起来只是调小」的取值
     * 需要启动期拦住，而不是留给压测去发现。
     *
     * <p>与 {@code spring.data.redis.timeout} 的关系（预算大于它就没有意义）
     * **不做校验**：本类看不见那个 key，为一条提示引入跨配置项的耦合不划算，
     * 这条关系写在主 yaml 的注释里由运维保证（P9.3 §4.3）。
     */
    private static void requireNonNegativeDeadline(int deadlineMs) {
        if (deadlineMs < 0) {
            throw new IllegalStateException(
                    "dash.risk.deadline-ms 不能为负，当前为 " + deadlineMs + "。"
                            + "负数会让每一次预检从第一毫秒起就超支，配合 fail-open=false "
                            + "就是全站抢单不可用。0 是合法值（语义为「一步都不许跑」，"
                            + "用于强制走降级路径），不要用负数表达这个意思。");
        }
    }

    /**
     * 守住 ZSet 的紧凑编码边界。
     *
     * <p>Redis 7.4 的 ZSet 在成员数不超过 zset-max-listpack-entries（默认 128）时用
     * listpack 编码，超过即转 skiplist，且**编码只升不降**——一旦转过去，即使后来
     * 裁剪回 100 个成员也不会变回 listpack。
     *
     * <p><b>为什么上界是 127 而不是 128</b>：Lua 脚本是「先 ZADD、再裁剪」，
     * 所以键在调用的瞬间会达到 {@code cap + 1} 个成员。cap 取 128 时瞬时到 129，
     * 当场触发转换。实测（Redis 7.4.11，成员为真实形态）：
     *
     * <pre>
     *   cap=126  listpack   7232 B
     *   cap=127  listpack   7232 B
     *   cap=128  skiplist  17601 B   ← 跳涨 2.4 倍
     * </pre>
     *
     * <p>直接往一个 ZSet 里塞恰好 128 个成员确实仍是 listpack，但那不是本项目的写入路径。
     * 按写入路径的真实峰值算，安全上界是 127。
     *
     * <p>这种退化不报错、不告警，只在压测里表现为 Redis 内存异常。
     * 所以在这里直接拦住——阈值一旦被调过 127，应用启动就失败，而不是线上慢慢吃掉内存。
     */
    private static void requireCompactEncoding(int cap, String key) {
        if (cap > 127) {
            throw new IllegalStateException(
                    "风控 ZSet 成员上限 " + cap + " 超过 127（由 " + key + " 推导）。"
                            + "脚本先 ZADD 后裁剪，瞬时成员数会达到「上限 +1」，"
                            + "超过 128 后编码由 listpack 永久转为 skiplist，单键内存翻倍以上。"
                            + "确需更大窗口请改用其他结构或降低窗口精度，不要直接把阈值调大。");
        }
    }

    /**
     * 守住 Set 的 intset 编码边界：设备/IP 账号聚集集合。
     *
     * <p>这两个键的成员是整数 userId，所以走 intset 而不是 listpack，编码悬崖的
     * 位置也不同——由 {@code set-max-intset-entries}（默认 512）决定，**超过 512
     * 个成员即转为 hashtable，且与 ZSet 一样只升不降**。
     *
     * <p><b>为什么是 512 而不是 513</b>：Set 没有 ZSet 那个「先写后裁」的瞬时
     * {@code cap + 1} 问题——脚本是 {@code if SCARD < cap then SADD}，最多正好到 cap。
     * 实测（Redis 7.4.11，走 {@code risk_cluster.lua} 真实写入路径）：
     *
     * <pre>
     *   cap=512  intset      1336 B
     *   cap=513  hashtable  24720 B   ← 跳涨 18.5 倍
     * </pre>
     *
     * <p>跳变比 ZSet 那条（2.4 倍）陡得多，且同样不报错、不告警。
     * 生产配置是 9 和 11，离悬崖很远；但「误报太多就把阈值调大」是本模块最自然的
     * 一次配置改动，所以不能只靠注释提醒。
     */
    private static void requireCompactIntset(int cap, String key) {
        if (cap > 512) {
            throw new IllegalStateException(
                    "风控 Set 成员上限 " + cap + " 超过 512（由 " + key + " 推导）。"
                            + "成员数超过 set-max-intset-entries（默认 512）后，编码由 intset "
                            + "永久转为 hashtable，单键内存跳涨 18.5 倍（实测 1336 B → 24720 B）。"
                            + "确需更大窗口请改用其他结构，不要直接把阈值调大。");
        }
    }

    /**
     * 用户滑动窗口。蓝图 §3.2：10 秒超过 5 次 LIMIT，60 秒超过 30 次 REVIEW。
     */
    public record UserWindow(
            @DefaultValue("10") int shortSeconds,
            @DefaultValue("5") int shortThreshold,
            @DefaultValue("60") int longSeconds,
            @DefaultValue("30") int longThreshold) {

        /**
         * 成员上限 = 阈值 + 1。
         *
         * <p>为什么由阈值推导而不是单独配置：规则是「超过 N 次」，集合封顶在 N+1
         * 就足以表达「已越线」——N+1 和 N+900 对决策没有区别，因为 Lua 只返回计数、
         * 阈值比较在领域层。把它做成独立配置项只会多一种配错的方式：
         * cap 若小于 threshold + 1，计数永远到不了阈值，规则**静默失效**且不报错。
         */
        public int shortCap() {
            return shortThreshold + 1;
        }

        public int longCap() {
            return longThreshold + 1;
        }
    }

    /**
     * 设备多账号窗口。蓝图 §3.3 是**同一个计数上的两档阈值**：
     * 10 分钟内同设备关联账号超过 3 个 REVIEW，超过 8 个 BLOCK。
     *
     * <p>P9.1 建这个 record 时只有单个 {@code threshold}（8），因为那时还没有决策层，
     * 装不下成对的阈值；P9.2 补上 {@link #lowThreshold}。
     *
     * <p>{@code threshold}（高）决定 {@link #cap()}，低阈值不参与推导：cap 的作用是
     * 「到了就够用」，高档阈值是本窗口里最大的那个判定点，低阈值一定先于它到达。
     *
     * <p>两者的取值域（{@code 1 <= lowThreshold < threshold}）由外层紧凑构造器校验，
     * 理由见 {@code requireThresholdPair}。
     */
    public record DeviceWindow(
            @DefaultValue("600") int seconds,
            @DefaultValue("8") int threshold,
            @DefaultValue("3") int lowThreshold) {

        public int cap() {
            return threshold + 1;
        }
    }

    /**
     * IP 账号聚集窗口。蓝图 §3.4：10 分钟内同 IP 出现超过 10 个账号，风险加分或 REVIEW。
     * threshold 取 10，cap 推导为 11。
     */
    public record IpAccountWindow(
            @DefaultValue("600") int seconds,
            @DefaultValue("10") int threshold) {

        public int cap() {
            return threshold + 1;
        }
    }

    /**
     * IP 速率窗口。蓝图 §3.4：60 秒内同 IP 抢单超过 100 次 LIMIT。
     * threshold 取 100，cap 推导为 101（距 127 的编码上界留 26 的余量）。
     */
    public record IpRateWindow(
            @DefaultValue("60") int seconds,
            @DefaultValue("100") int threshold) {

        public int cap() {
            return threshold + 1;
        }
    }
}
