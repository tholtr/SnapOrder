package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.RiskMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这些用例全部不需要 Spring 上下文——这正是把校验写在紧凑构造器（而不是
 * @Validated + @NotBlank）换来的好处：可以直接 new，验的就是真实生效的那段代码。
 *
 * <p>用 JUnit 原生断言而非 AssertJ：AssertJ 只随 dash-bootstrap 的
 * spring-boot-starter-test 引入，本模块测试类路径上没有，与既有 infra 测试保持一致。
 */
@DisplayName("风控配置")
class RiskPropertiesTest {

    /**
     * 策略两项的定值。本类用例关心的是窗口与阈值，策略项取合法值即可——
     * 与 {@code RiskConfigTest} 不同，这里不靠取值区分能力来发现问题，
     * 所以不需要两两不等。
     */
    private static final boolean FAIL_OPEN = true;

    private static final int DEADLINE_MS = 10;

    /**
     * P9.4 两项的定值。本类用例关心的是窗口、阈值与冷却时长，这两项取合法值即可：
     * 聚合开关只决定「有几条上了 MQ」，与决策无关；实例标识只进降级闸门的键名。
     */
    private static final boolean EVENT_AGGREGATION = true;

    private static final String INSTANCE_ID = "it-instance";

    private static RiskProperties props(String secret) {
        return props(secret,
                new RiskProperties.UserWindow(10, 5, 60, 30),
                device(600, 8),
                new RiskProperties.IpAccountWindow(600, 10),
                new RiskProperties.IpRateWindow(60, 100));
    }

    /**
     * 显式传四个窗口，其余取定值。做成重载而不是在每处内联 new：外层 record
     * 每加一个字段，内联写法就要改十几处测试，而它们大多和那个新字段无关。
     */
    private static RiskProperties props(String secret,
                                        RiskProperties.UserWindow userWindow,
                                        RiskProperties.DeviceWindow deviceWindow,
                                        RiskProperties.IpAccountWindow ipAccountWindow,
                                        RiskProperties.IpRateWindow ipRateWindow) {
        return new RiskProperties(true, secret, RiskMode.SHADOW, FAIL_OPEN, DEADLINE_MS,
                10_000, "1",
                userWindow, deviceWindow, ipAccountWindow, ipRateWindow,
                EVENT_AGGREGATION, INSTANCE_ID);
    }

    /** 只换预检预算，其余取定值。 */
    private static RiskProperties props(int deadlineMs) {
        return new RiskProperties(true, "s", RiskMode.SHADOW, FAIL_OPEN, deadlineMs,
                10_000, "1",
                new RiskProperties.UserWindow(10, 5, 60, 30),
                device(600, 8),
                new RiskProperties.IpAccountWindow(600, 10),
                new RiskProperties.IpRateWindow(60, 100),
                EVENT_AGGREGATION, INSTANCE_ID);
    }

    /**
     * 设备窗口简写。本类用例几乎都只关心高低阈值里的**高**阈值（它决定成员上限），
     * 低阈值统一取 3——合法值域是 {@code 1 <= low < high}，而这里的高阈值最小也是 8。
     */
    private static RiskProperties.DeviceWindow device(int seconds, int highThreshold) {
        return new RiskProperties.DeviceWindow(seconds, highThreshold, 3);
    }

    @Nested
    @DisplayName("device-hmac-secret 必填")
    class SecretRequired {

        @ParameterizedTest(name = "值为 [{0}] 时启动失败")
        @ValueSource(strings = {"", "   ", "\t"})
        @DisplayName("空串与纯空白都要拦住，不能只判 null")
        void rejectsBlank(String blank) {
            // 纯空白最危险：它不是 null，配置里看起来「填过了」，
            // 但会变成所有实例共享的空密钥，而且不报错。
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> props(blank));

            assertTrue(ex.getMessage().contains("dash.risk.device-hmac-secret"));
        }

        @Test
        @DisplayName("null 同样拦住")
        void rejectsNull() {
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> props(null));

            assertTrue(ex.getMessage().contains("dash.risk.device-hmac-secret"));
        }

        @Test
        @DisplayName("异常信息必须给出可执行的修复方式，否则排障只能靠猜")
        void messageIsActionable() {
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> props(null));

            assertTrue(ex.getMessage().contains("DASH_RISK_DEVICE_HMAC_SECRET"));
        }

        @Test
        @DisplayName("正常值通过")
        void acceptsSecret() {
            assertDoesNotThrow(() -> props("local-dev-secret"));
        }
    }

    @Nested
    @DisplayName("成员上限由阈值推导")
    class CapDerivation {

        @Test
        @DisplayName("cap 恒等于 threshold + 1")
        void capEqualsThresholdPlusOne() {
            // 规则是「超过 N 次」，封顶 N+1 才刚够表达「已越线」。
            // 若 cap <= threshold，计数永远到不了阈值，规则会静默失效且不报错——
            // 这就是不把 cap 做成独立配置项的原因。
            RiskProperties p = props("s");

            assertEquals(6, p.userWindow().shortCap());    // 阈值 5
            assertEquals(31, p.userWindow().longCap());    // 阈值 30
            assertEquals(9, p.deviceWindow().cap());       // 阈值 8
            assertEquals(11, p.ipAccountWindow().cap());   // 阈值 10
            assertEquals(101, p.ipRateWindow().cap());     // 阈值 100
        }

        @Test
        @DisplayName("阈值改了，上限跟着变")
        void capFollowsThreshold() {
            assertEquals(21, new RiskProperties.UserWindow(10, 20, 60, 30).shortCap());
        }

        @Test
        @DisplayName("设备窗口的上限只由高阈值决定，低阈值不参与")
        void lowThresholdDoesNotAffectCap() {
            // 低阈值一定先于高阈值到达（校验保证了 low < high），
            // 所以本窗口里最大的判定点始终是高阈值，cap 只跟它走。
            assertEquals(9, new RiskProperties.DeviceWindow(600, 8, 1).cap());
            assertEquals(9, new RiskProperties.DeviceWindow(600, 8, 7).cap());
        }
    }

    @Nested
    @DisplayName("设备维度两档阈值")
    class ThresholdPair {

        @Test
        @DisplayName("低阈值不低于高阈值时启动失败")
        void rejectsLowAboveHigh() {
            // 两条规则判定的是同一个计数，领域层写成 else-if 链取最高档。
            // low >= high 时低档分支永远走不到——规则表里标着 REVIEW 的那档
            // 成了永不触发的死配置，且没有任何报错。
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new RiskProperties(true, "s", RiskMode.SHADOW, FAIL_OPEN, DEADLINE_MS,10_000, "1",
                            new RiskProperties.UserWindow(10, 5, 60, 30),
                            new RiskProperties.DeviceWindow(600, 8, 8),   // low == high
                            new RiskProperties.IpAccountWindow(600, 10),
                            new RiskProperties.IpRateWindow(60, 100),
                            EVENT_AGGREGATION, INSTANCE_ID));

            assertTrue(ex.getMessage().contains("dash.risk.device-window.low-threshold"));
            assertTrue(ex.getMessage().contains("dash.risk.device-window.threshold"));
        }

        @Test
        @DisplayName("低阈值高于高阈值同样启动失败")
        void rejectsLowAboveHighStrictly() {
            assertThrows(IllegalStateException.class,
                    () -> new RiskProperties(true, "s", RiskMode.SHADOW, FAIL_OPEN, DEADLINE_MS,10_000, "1",
                            new RiskProperties.UserWindow(10, 5, 60, 30),
                            new RiskProperties.DeviceWindow(600, 8, 9),
                            new RiskProperties.IpAccountWindow(600, 10),
                            new RiskProperties.IpRateWindow(60, 100),
                            EVENT_AGGREGATION, INSTANCE_ID));
        }

        @ParameterizedTest(name = "低阈值为 {0} 时启动失败")
        @ValueSource(ints = {0, -1})
        @DisplayName("低阈值为 0 或负数时启动失败——这会让低档规则恒真")
        void rejectsNonPositiveLow(int low) {
            // 这是设计文档 §6.2 之外补的一条，防的是「想关掉低档规则」这个很自然的
            // 运维动作：最顺手的两种写法（0 和 -1）都让「账号数 > 低阈值」恒真，
            // 结果是每个带设备维度的请求都被判 REVIEW——一次配置笔误就是全站拦截，
            // 而日志里只会显示「规则正常命中」。
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new RiskProperties(true, "s", RiskMode.SHADOW, FAIL_OPEN, DEADLINE_MS,10_000, "1",
                            new RiskProperties.UserWindow(10, 5, 60, 30),
                            new RiskProperties.DeviceWindow(600, 8, low),
                            new RiskProperties.IpAccountWindow(600, 10),
                            new RiskProperties.IpRateWindow(60, 100),
                            EVENT_AGGREGATION, INSTANCE_ID));

            assertTrue(ex.getMessage().contains("dash.risk.device-window.low-threshold"));
        }

        @Test
        @DisplayName("低阈值取 1（合法下界）与 high-1（合法上界）都通过")
        void acceptsBothBoundaries() {
            assertDoesNotThrow(() -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 30),
                    new RiskProperties.DeviceWindow(600, 8, 1),
                    new RiskProperties.IpAccountWindow(600, 10),
                    new RiskProperties.IpRateWindow(60, 100)));
            assertDoesNotThrow(() -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 30),
                    new RiskProperties.DeviceWindow(600, 8, 7),
                    new RiskProperties.IpAccountWindow(600, 10),
                    new RiskProperties.IpRateWindow(60, 100)));
        }
    }

    @Nested
    @DisplayName("限频冷却时长")
    class LimitCooldown {

        @ParameterizedTest(name = "冷却时长为 {0} ms 时启动失败")
        @ValueSource(ints = {0, -1, -10_000})
        @DisplayName("非正的冷却时长启动失败——限频键会写完即刻过期")
        void rejectsNonPositive(int cooldown) {
            // 它同时是 risk:limit:user:{userId} 的 TTL。非正值让键写完就没了，
            // 而调用方看到的是「写入成功」——限频静默失效，日志里查不出原因。
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new RiskProperties(true, "s", RiskMode.SHADOW, FAIL_OPEN, DEADLINE_MS,cooldown, "1",
                            new RiskProperties.UserWindow(10, 5, 60, 30),
                            new RiskProperties.DeviceWindow(600, 8, 3),
                            new RiskProperties.IpAccountWindow(600, 10),
                            new RiskProperties.IpRateWindow(60, 100),
                            EVENT_AGGREGATION, INSTANCE_ID));

            assertTrue(ex.getMessage().contains("dash.risk.limit-cooldown-ms"));
        }

        @Test
        @DisplayName("1 ms 是合法下界")
        void acceptsOneMillisecond() {
            assertDoesNotThrow(() -> new RiskProperties(true, "s", RiskMode.SHADOW, FAIL_OPEN, DEADLINE_MS,1, "1",
                    new RiskProperties.UserWindow(10, 5, 60, 30),
                    new RiskProperties.DeviceWindow(600, 8, 3),
                    new RiskProperties.IpAccountWindow(600, 10),
                    new RiskProperties.IpRateWindow(60, 100),
                    EVENT_AGGREGATION, INSTANCE_ID));
        }
    }

    @Nested
    @DisplayName("预检时间预算")
    class Deadline {

        @ParameterizedTest(name = "预算为 {0} ms 时启动失败")
        @ValueSource(ints = {-1, -10, -2_000})
        @DisplayName("负数启动失败——它让每一次预检都从第一毫秒起超支")
        void rejectsNegative(int deadlineMs) {
            // 负数配合 fail-open=false 就是全站抢单不可用，而它在配置里看起来
            // 只是「把超时配小了一点」，不像一次故障——所以要在启动期拦住。
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> props(deadlineMs));

            assertTrue(ex.getMessage().contains("dash.risk.deadline-ms"));
        }

        @Test
        @DisplayName("0 是合法值——它是强制走降级路径的唯一纯配置手段")
        void acceptsZero() {
            // 0 的语义是「一步都不许跑」。它必须能通过启动校验：否则端到端验证
            // 降级分支就只能靠关 Redis 或打桩，而那两种手段要么不可复现、
            // 要么测的不是真实路径（P9.3 决策 D11）。
            assertDoesNotThrow(() -> props(0));
        }

        @Test
        @DisplayName("正数是常态：10 ms（默认值）与更大的值都通过")
        void acceptsPositive() {
            assertDoesNotThrow(() -> props(10));
            assertDoesNotThrow(() -> props(2_000));
        }
    }

    @Nested
    @DisplayName("ZSet 紧凑编码边界")
    class CompactEncoding {

        /**
         * 上界是 127 而不是 128，这一点差一就白防了。
         *
         * 脚本是「先 ZADD、再裁剪」，键在调用瞬间会达到 cap+1 个成员；而 ZSet 的
         * 编码只升不降。所以 cap=128 时瞬时到 129，当场转成 skiplist 就再也回不来：
         * 实测 127 成员 7232 B（listpack）→ 128 成员 17601 B（skiplist）。
         * 直接塞 128 个成员确实仍是 listpack，但那不是本项目的写入路径。
         */
        @Test
        @DisplayName("推导出的上限为 127 时允许（按写入路径的真实峰值算）")
        void allowsExactly127() {
            assertDoesNotThrow(() -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 30),
                    device(600, 8),
                    new RiskProperties.IpAccountWindow(600, 10),
                    new RiskProperties.IpRateWindow(60, 126)));
        }

        @Test
        @DisplayName("上限推到 128 时启动即失败——瞬时 129 成员会永久转成 skiplist")
        void rejectsAbove127() {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 30),
                    device(600, 8),
                    new RiskProperties.IpAccountWindow(600, 10),
                    new RiskProperties.IpRateWindow(60, 127)));

            assertTrue(ex.getMessage().contains("127"));
            assertTrue(ex.getMessage().contains("dash.risk.ip-rate-window.threshold"));
        }

        @Test
        @DisplayName("长窗口阈值 126 推导出 127，是恰好合法的上界")
        void longWindowAtBoundary() {
            assertDoesNotThrow(() -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 126),
                    device(600, 8),
                    new RiskProperties.IpAccountWindow(600, 10),
                    new RiskProperties.IpRateWindow(60, 100)));

            assertThrows(IllegalStateException.class, () -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 127),
                    device(600, 8),
                    new RiskProperties.IpAccountWindow(600, 10),
                    new RiskProperties.IpRateWindow(60, 100)));
        }

        @Test
        @DisplayName("用户窗口的阈值同样受约束")
        void appliesToUserWindows() {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 200),
                    device(600, 8),
                    new RiskProperties.IpAccountWindow(600, 10),
                    new RiskProperties.IpRateWindow(60, 100)));

            assertTrue(ex.getMessage().contains("dash.risk.user-window.long-threshold"));
        }

        @Test
        @DisplayName("Set 的阈值不套用 ZSet 的 127 上界")
        void setCapsUseTheirOwnBound() {
            // 这两个键的成员是小整数 userId，走 intset 而非 listpack，悬崖在 512。
            // 501 远超 ZSet 的 127 上界却是合法的——两种键的边界不同，不能混用。
            assertDoesNotThrow(() -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 30),
                    device(600, 500),
                    new RiskProperties.IpAccountWindow(600, 500),
                    new RiskProperties.IpRateWindow(60, 100)));
        }

        /**
         * Set 的悬崖在 512，不是「没有悬崖」。
         *
         * 这一点原先被写反过：脚本注释与测试都声称 Set「不存在编码跳变」，
         * 于是没有任何守卫。实测走 risk_cluster.lua 真实写入路径：
         * 512 个成员 intset 1336 B，513 个成员 hashtable 24720 B，跳涨 18.5 倍，
         * 比 ZSet 那条（2.4 倍）陡得多。
         */
        @Test
        @DisplayName("Set 上限为 512 时允许")
        void allowsSetCapAt512() {
            assertDoesNotThrow(() -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 30),
                    device(600, 511),                             // cap = 512
                    new RiskProperties.IpAccountWindow(600, 511),
                    new RiskProperties.IpRateWindow(60, 100)));
        }

        @Test
        @DisplayName("Set 上限推到 513 时启动即失败——转 hashtable 后跳涨 18.5 倍")
        void rejectsSetCapAbove512() {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 30),
                    device(600, 512),                             // cap = 513
                    new RiskProperties.IpAccountWindow(600, 10),
                    new RiskProperties.IpRateWindow(60, 100)));

            assertTrue(ex.getMessage().contains("512"));
            assertTrue(ex.getMessage().contains("dash.risk.device-window.threshold"));
        }

        @Test
        @DisplayName("IP 账号集合同样受约束")
        void appliesToIpAccountSet() {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> props("s",
                    new RiskProperties.UserWindow(10, 5, 60, 30),
                    device(600, 8),
                    new RiskProperties.IpAccountWindow(600, 512),
                    new RiskProperties.IpRateWindow(60, 100)));

            assertTrue(ex.getMessage().contains("dash.risk.ip-account-window.threshold"));
        }
    }

    @Nested
    @DisplayName("instance-id 必填且不得为空白（P9.4）")
    class InstanceId {

        @ParameterizedTest(name = "值为 [{0}] 时启动失败")
        @ValueSource(strings = {"", "   ", "\t"})
        @DisplayName("空串与纯空白都要拦住——空白的标识会让闸门从每实例一条变成全局一条")
        void rejectsBlank(String blank) {
            // 它没有 @DefaultValue，「完全没配」在绑定期就失败了；这一条挡的是
            // 「配了但配成空串」——yaml 里写 ${HOSTNAME:} 就是这个结果。
            // 后果不是启动失败，而是降级闸门键 risk:event:gate:degraded:{instanceId}
            // 在所有实例上算成同一个键：闸门从「每实例 10 秒一条」悄悄变成「全局一条」，
            // 指标上看不出来，只是分母错了——而 P9.5 读抑制率时的分母正是实例数。
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> propsWithInstanceId(blank));

            assertTrue(ex.getMessage().contains("dash.risk.instance-id"));
        }

        @Test
        @DisplayName("null 同样拦住")
        void rejectsNull() {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> propsWithInstanceId(null));

            assertTrue(ex.getMessage().contains("dash.risk.instance-id"));
        }

        @Test
        @DisplayName("异常信息要给出可执行的修复方式")
        void messageIsActionable() {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> propsWithInstanceId(""));

            assertTrue(ex.getMessage().contains("HOSTNAME"),
                    "报错要告诉人怎么修，否则排障还得回去读代码：" + ex.getMessage());
        }

        @Test
        @DisplayName("正常值通过")
        void acceptsInstanceId() {
            assertDoesNotThrow(() -> propsWithInstanceId("it-instance"));
        }
    }

    /** 只换实例标识，其余取定值。 */
    private static RiskProperties propsWithInstanceId(String instanceId) {
        return new RiskProperties(true, "s", RiskMode.SHADOW, FAIL_OPEN, DEADLINE_MS,
                10_000, "1",
                new RiskProperties.UserWindow(10, 5, 60, 30),
                device(600, 8),
                new RiskProperties.IpAccountWindow(600, 10),
                new RiskProperties.IpRateWindow(60, 100),
                EVENT_AGGREGATION, instanceId);
    }
}
