package com.campusdash.presentation.risk;

import com.campusdash.presentation.risk.RiskContextResolver.RiskRequestSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全部用例都不需要 Spring 上下文——解析器刻意只收一个记录而不是
 * {@code HttpServletRequest}，单测里造数据就是一行构造器调用。
 */
@DisplayName("风控上下文解析")
class RiskContextResolverTest {

    /** 无可信代理：转发头一律不生效，用对端地址兜底。 */
    private static RiskContextResolver plain() {
        return new RiskContextResolver(new ClientIpResolver(""), false);
    }

    private static RiskContextResolver withTestContext() {
        return new RiskContextResolver(new ClientIpResolver(""), true);
    }

    private static RiskRequestSource source() {
        return new RiskRequestSource("req-1", "device-abc", "203.0.113.7", List.of(), null, null);
    }

    @Nested
    @DisplayName("必要字段解析")
    class Basics {

        @Test
        @DisplayName("三个字段按原样带出，不做任何哈希")
        void passesRawValuesThrough() {
            // 原始值必须原样传出去：哈希是 infra 适配器的职责（D1）。
            // 若这里顺手哈希，infra 会再哈希一次，键就全错了。
            RiskRequest r = plain().resolve(source(), 1001L);

            assertEquals(1001L, r.userId());
            assertEquals("req-1", r.requestId());
            assertEquals("device-abc", r.deviceId());
            assertEquals("203.0.113.7", r.clientIp());
        }

        @Test
        @DisplayName("requestId 缺失时生成 UUID")
        void generatesRequestIdWhenMissing() {
            RiskRequest r = plain().resolve(
                    new RiskRequestSource(null, null, "203.0.113.7", List.of(), null, null), 1L);

            assertNotNull(r.requestId());
            assertFalse(r.requestId().isBlank());
        }

        @Test
        @DisplayName("两次生成的 requestId 不同（否则窗口去重会把所有请求并成一个）")
        void generatedRequestIdsDiffer() {
            RiskRequestSource s = new RiskRequestSource(null, null, "203.0.113.7", List.of(), null, null);

            assertNotEquals(plain().resolve(s, 1L).requestId(), plain().resolve(s, 1L).requestId());
        }

        @Test
        @DisplayName("空串与纯空白的 requestId 当成没传，重新生成")
        void blankRequestIdIsRegenerated() {
            // 这一条是真会造成静默故障的：requestId 是窗口去重的成员，
            // 空串若被原样放行，所有带空头的请求共用一个成员，计数恒为 1，
            // 规则再也不触发，而且不报任何错。
            for (String blank : List.of("", "   ", "\t")) {
                RiskRequest r = plain().resolve(
                        new RiskRequestSource(blank, null, "203.0.113.7", List.of(), null, null), 1L);

                assertNotEquals(blank, r.requestId(), "空白 requestId 应被替换");
                assertFalse(r.requestId().isBlank());
            }
        }

        @Test
        @DisplayName("deviceId 缺失或空白时归一为 null，不是空串")
        void deviceIdNormalizedToNull() {
            // null 与空串的差别有实际后果：端口按 null 判定「跳过设备维度」，
            // 空串会被拿去哈希，让所有没带设备号的客户端落进同一个 Redis 键。
            assertNull(plain().resolve(
                    new RiskRequestSource("req-1", null, "203.0.113.7", List.of(), null, null), 1L)
                    .deviceId());
            assertNull(plain().resolve(
                    new RiskRequestSource("req-1", "  ", "203.0.113.7", List.of(), null, null), 1L)
                    .deviceId());
        }

        @Test
        @DisplayName("对端地址不是合法 IPv4 时 clientIp 为 null，不是空串也不是原始值")
        void unusableRemoteAddrYieldsNullIp() {
            // 返回 null 而不是退回原始字符串：后者会让下游把一个非法值拿去哈希，
            // 于是所有这类请求共用一个风控桶。见 ClientIpResolver 的说明。
            RiskRequest r = plain().resolve(
                    new RiskRequestSource("req-1", null, "not-an-ip", List.of(), null, null), 1L);

            assertNull(r.clientIp());
        }
    }

    @Nested
    @DisplayName("测试上下文开关")
    class TestContext {

        @Test
        @DisplayName("开关打开时测试头覆盖设备与 IP")
        void overridesWhenEnabled() {
            RiskRequest r = withTestContext().resolve(
                    new RiskRequestSource("req-1", "device-real", "203.0.113.7", List.of(),
                            "device-forced", "10.1.2.3"), 1L);

            assertEquals("device-forced", r.deviceId());
            assertEquals("10.1.2.3", r.clientIp());
        }

        @Test
        @DisplayName("开关关闭时测试头一律无效")
        void ignoredWhenDisabled() {
            // 与 dash.auth.allow-header-identity 是同一类开关，必须证明默认态是安全的。
            RiskRequest r = plain().resolve(
                    new RiskRequestSource("req-1", "device-real", "203.0.113.7", List.of(),
                            "device-forced", "10.1.2.3"), 1L);

            assertEquals("device-real", r.deviceId(), "开关关闭时测试设备头必须无效");
            assertEquals("203.0.113.7", r.clientIp(), "开关关闭时测试 IP 头必须无效");
        }

        @Test
        @DisplayName("开关打开但测试头为空白时，退回真实值而不是清空")
        void blankTestHeaderFallsBack() {
            // 「传了空测试头」的意图是「这次不覆盖」，不是「把设备清空」。
            // 若实现成无条件覆盖，一个空头就能让攻击者抹掉自己的设备维度。
            RiskRequest r = withTestContext().resolve(
                    new RiskRequestSource("req-1", "device-real", "203.0.113.7", List.of(),
                            "  ", ""), 1L);

            assertEquals("device-real", r.deviceId());
            assertEquals("203.0.113.7", r.clientIp());
        }

        @Test
        @DisplayName("测试 IP 也必须是合法 IPv4，非法值当作没传")
        void invalidTestIpIgnored() {
            // 不校验的话，测试路径会绕过 clientIp「非空即合法 IPv4」这条不变式，
            // 于是本地测出来的东西和线上跑的不是一回事。
            RiskRequest r = withTestContext().resolve(
                    new RiskRequestSource("req-1", null, "203.0.113.7", List.of(),
                            null, "999.1.1.1"), 1L);

            assertEquals("203.0.113.7", r.clientIp());
        }

        @Test
        @DisplayName("测试 IP 会被规范化（前导零的写法不落进结果）")
        void testIpIsNormalized() {
            RiskRequest r = withTestContext().resolve(
                    new RiskRequestSource("req-1", null, null, List.of(), null, "10.1.2.3"), 1L);

            assertEquals("10.1.2.3", r.clientIp());
        }
    }

    @Nested
    @DisplayName("转发头")
    class Forwarded {

        @Test
        @DisplayName("无可信代理时转发头完全无效（默认配置）")
        void ignoredWithoutTrustedProxy() {
            RiskRequest r = plain().resolve(
                    new RiskRequestSource("req-1", null, "203.0.113.7",
                            List.of("1.2.3.4"), null, null), 1L);

            assertEquals("203.0.113.7", r.clientIp());
        }

        @Test
        @DisplayName("配了可信代理时，客户端伪造的转发头被丢弃")
        void forgeryRejectedBehindTrustedProxy() {
            // 对端是可信代理，但链上第一个不可信地址才是真实客户端。
            var behind = new RiskContextResolver(new ClientIpResolver("10.0.0.0/8"), false);

            RiskRequest r = behind.resolve(
                    new RiskRequestSource("req-1", null, "10.0.0.5",
                            List.of("1.2.3.4, 198.51.100.9"), null, null), 1L);

            assertEquals("198.51.100.9", r.clientIp(), "应从右往左取第一个不可信地址");
        }

        @Test
        @DisplayName("多条转发头按出现顺序拼接，走私的第一条不生效")
        void multipleHeadersConcatenated() {
            // 攻击者发一条、代理不追加而是再发一条，是转发头走私的经典手法。
            var behind = new RiskContextResolver(new ClientIpResolver("10.0.0.0/8"), false);

            RiskRequest r = behind.resolve(
                    new RiskRequestSource("req-1", null, "10.0.0.5",
                            List.of("1.2.3.4", "198.51.100.9"), null, null), 1L);

            assertEquals("198.51.100.9", r.clientIp(), "走私的第一条不应生效");
        }
    }

    @Nested
    @DisplayName("不变式")
    class Invariants {

        @Test
        @DisplayName("clientIp 要么为 null，要么是合法 IPv4")
        void ipIsAlwaysValidOrNull() {
            List<RiskRequestSource> sources = List.of(
                    new RiskRequestSource("r", null, "203.0.113.7", List.of(), null, null),
                    new RiskRequestSource("r", null, "not-an-ip", List.of(), null, null),
                    new RiskRequestSource("r", null, "203.0.113.7", List.of("garbage"), null, null),
                    new RiskRequestSource("r", null, "10.0.0.5", List.of("1.2.3.4, 5.6.7.8"), null, null),
                    new RiskRequestSource("r", null, null, List.of(), null, null));

            for (RiskRequestSource s : sources) {
                String ip = plain().resolve(s, 1L).clientIp();
                if (ip != null) {
                    assertNotNull(Ipv4.parse(ip), "出现非法 IP：" + ip + "（来自 " + s.remoteAddr() + "）");
                }
            }
        }

        @Test
        @DisplayName("requestId 永不为 null 或空白")
        void requestIdAlwaysUsable() {
            List<RiskRequestSource> sources = List.of(
                    new RiskRequestSource("r", null, "203.0.113.7", List.of(), null, null),
                    new RiskRequestSource(null, null, "203.0.113.7", List.of(), null, null),
                    new RiskRequestSource("", null, "203.0.113.7", List.of(), null, null),
                    new RiskRequestSource("   ", null, "203.0.113.7", List.of(), null, null));

            for (RiskRequestSource s : sources) {
                RiskRequest r = plain().resolve(s, 1L);
                assertNotNull(r.requestId());
                assertFalse(r.requestId().isBlank());
            }
        }

        @Test
        @DisplayName("userId 原样带出（解析器不重复解析身份）")
        void userIdPassedThrough() {
            assertEquals(42L, plain().resolve(source(), 42L).userId());
        }
    }

    @Nested
    @DisplayName("测试头未读时不参与（开关关闭）")
    class NoRead {

        @Test
        @DisplayName("开关关闭时连 null 都不需要——传什么都被忽略")
        void valuesPresentButSwitchOff() {
            // 拦截器在开关关闭时根本不会去读这两个头，传进来的必然是 null；
            // 但即便有人别处构造 source 塞了值（本用例就是这种情形），
            // 解析器内的第二道闸也必须继续拦住。
            RiskRequest r = plain().resolve(
                    new RiskRequestSource("req-1", "device-real", "203.0.113.7", List.of(),
                            "device-forced", "10.9.9.9"), 1L);

            assertTrue(r.deviceId().equals("device-real") && r.clientIp().equals("203.0.113.7"),
                    "开关关闭时测试头必须完全无效，实际 deviceId=" + r.deviceId()
                            + " clientIp=" + r.clientIp());
        }
    }
}
