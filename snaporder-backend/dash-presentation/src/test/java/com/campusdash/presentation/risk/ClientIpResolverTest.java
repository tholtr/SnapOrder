package com.campusdash.presentation.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本文件的主线是<b>攻击用例</b>，不是正常路径用例。
 *
 * <p>这个解析器正常工作时看起来平平无奇，写反了也不报错、不崩溃——
 * 唯一的症状是 IP 维度可以被一行 X-Forwarded-For 完全绕过，
 * 而这种绕过不会留下任何异常日志。所以先把伪造的输入钉死，再谈其余。
 */
@DisplayName("客户端 IP 解析")
class ClientIpResolverTest {

    /** 两个可信代理网段，覆盖本次要验证的各类形状。 */
    private static final String PROXIES = "10.0.0.0/8, 172.16.0.0/12";

    private static final String PROXY = "10.0.0.1";
    private static final String CLIENT = "203.0.113.9";

    private final ClientIpResolver resolver = new ClientIpResolver(PROXIES);

    @Nested
    @DisplayName("伪造与绕过")
    class Forgery {

        @Test
        @DisplayName("直连客户端伪造的转发头必须被完全忽略")
        void directClientCannotSpoof() {
            // 攻击者不经过任何代理，直接发请求并自带 XFF。此时 TCP 对端就是他本人，
            // 可信性最高，转发头一个字都不该信。
            assertEquals(CLIENT, resolver.resolve(CLIENT, "1.2.3.4"));
            assertEquals(CLIENT, resolver.resolve(CLIENT, "10.0.0.1"));   // 伪造成本地地址也没用
            assertEquals(CLIENT, resolver.resolve(CLIENT, "1.1.1.1, 2.2.2.2, 3.3.3.3"));
        }

        @Test
        @DisplayName("经过代理时，客户端伪造的前缀不能覆盖代理追加的真实地址")
        void forgedPrefixDoesNotWin() {
            // 这是最重要的一条。代理把真实对端追加在<b>末尾</b>，
            // 攻击者注入的内容永远在左边。从右往左读才能拿到真值；
            // 写成从左往右就会返回 1.2.3.4，整个 IP 维度当场失守。
            assertEquals(CLIENT, resolver.resolve(PROXY, "1.2.3.4, " + CLIENT));
        }

        @Test
        @DisplayName("伪造值恰好落在可信网段内，也不能因此继续向左读取")
        void forgedTrustedAddressDoesNotUnlockLeftSide() {
            // 攻击者把伪造值写成内网地址，诱使实现「看到可信就继续往左找」。
            // 正确行为是：右起第一个不可信地址即为客户端，命中就停。
            assertEquals(CLIENT, resolver.resolve(PROXY, "10.9.9.9, " + CLIENT));
        }

        @Test
        @DisplayName("另起一行走私的转发头：拼接后仍然取右侧真实地址")
        void multiHeaderSmuggling() {
            // 攻击者发一条 XFF，代理不追加内容而是再发一条头。
            // 只读第一条（getHeader）就会读到伪造值，所以必须按顺序拼接全部。
            assertEquals(CLIENT, resolver.resolve(PROXY, List.of("1.2.3.4", CLIENT)));
        }

        @Test
        @DisplayName("伪造出多段代理链，只取右起第一个不可信地址")
        void onlyFirstUntrustedFromRightWins() {
            assertEquals(CLIENT, resolver.resolve(PROXY, "1.1.1.1, 2.2.2.2, " + CLIENT));
        }

        @Test
        @DisplayName("前导零地址不能绕过——两端解析不一致正是白名单绕过手法")
        void leadingZeroCannotSneakThrough() {
            // "010.0.0.1" 若被当成 8.0.0.1，攻击者就能用同一串字符冒充另一个地址。
            // 解析失败 → 信任链断 → 退回对端地址。
            assertEquals(PROXY, resolver.resolve(PROXY, "010.0.0.1"));
        }

        /**
         * 已知限制，写成用例是为了让它显式存在而不是被误当成 bug 修掉。
         * 真实客户端恰好来自可信网段时，无法与伪造段区分，会继续向左读到伪造值。
         * 这个代价换来的是「其余所有情况都正确」，并且只有内网互访才会触发。
         */
        @Test
        @DisplayName("已知限制：真实客户端本身来自可信网段时，无法与伪造段区分")
        void clientFromTrustedRangeIsIndistinguishable() {
            assertEquals("1.2.3.4", resolver.resolve(PROXY, "1.2.3.4, 10.9.9.9"));
        }
    }

    @Nested
    @DisplayName("正常路径")
    class NormalPath {

        @Test
        @DisplayName("一层代理：转发头里的客户端地址")
        void singleProxy() {
            assertEquals(CLIENT, resolver.resolve(PROXY, CLIENT));
        }

        @Test
        @DisplayName("多层可信代理：跳过中间代理，取最左侧的发起方")
        void multipleTrustedProxies() {
            assertEquals(CLIENT, resolver.resolve(PROXY, CLIENT + ", 10.1.1.1, 172.16.0.1"));
        }

        @Test
        @DisplayName("整条链都是可信代理：取最左侧")
        void allTrusted() {
            assertEquals("10.2.2.2", resolver.resolve(PROXY, "10.2.2.2, 10.1.1.1"));
        }

        @Test
        @DisplayName("代理追加了端口：剥掉端口再判定")
        void stripsPort() {
            // 不剥端口会让解析失败并退回代理地址，
            // 表现是同一个代理后面的所有用户共用一个 IP 桶，合法用户被误伤。
            assertEquals(CLIENT, resolver.resolve(PROXY, CLIENT + ":5678"));
        }

        @Test
        @DisplayName("转发头里的空白不影响解析")
        void toleratesWhitespace() {
            assertEquals(CLIENT, resolver.resolve(PROXY, "  " + CLIENT + "  "));
            assertEquals(CLIENT, resolver.resolve(PROXY, "10.1.1.1 ,  " + CLIENT));
        }

        @Test
        @DisplayName("可信代理但转发头为空：客户端就是该代理本身")
        void emptyForwardedHeader() {
            assertEquals(PROXY, resolver.resolve(PROXY, (String) null));
            assertEquals(PROXY, resolver.resolve(PROXY, ""));
            assertEquals(PROXY, resolver.resolve(PROXY, "   "));
            assertEquals(PROXY, resolver.resolve(PROXY, List.<String>of()));
        }
    }

    @Nested
    @DisplayName("信任链断裂")
    class BrokenChain {

        @ParameterizedTest
        @DisplayName("无法解析的段中断信任链，退回对端地址")
        @ValueSource(strings = {"::1", "2001:db8::1", "localhost", "999.1.1.1", "1.2.3", "garbage", "1.2.3.4:abc"})
        void unparsableSegmentFallsBackToPeer(String bad) {
            assertEquals(PROXY, resolver.resolve(PROXY, bad));
        }

        @Test
        @DisplayName("断裂点右侧已确认的地址仍然可用")
        void keepsAddressesToTheRightOfTheBreak() {
            // "垃圾, 真实地址" —— 从右往左先命中真实地址，根本走不到垃圾段
            assertEquals(CLIENT, resolver.resolve(PROXY, "garbage, " + CLIENT));
        }

        @Test
        @DisplayName("对端地址本身无法解析时返回 null，由调用方跳过该维度")
        void unparsablePeerYieldsNull() {
            // null 表示「IP 维度不可用」。不能退化成空字符串或固定值，
            // 否则所有这类请求会共用一个风控桶。
            assertNull(resolver.resolve(null, CLIENT));
            assertNull(resolver.resolve("", CLIENT));
            assertNull(resolver.resolve("::1", CLIENT));
            assertNull(resolver.resolve("localhost", CLIENT));
            assertNull(resolver.resolve("10.0.0.1:8080", CLIENT));
        }
    }

    @Nested
    @DisplayName("未配置可信代理")
    class NoTrustedProxies {

        private final ClientIpResolver directOnly = new ClientIpResolver("");

        @Test
        @DisplayName("默认配置下转发头完全不起作用")
        void forwardedHeaderIsIgnoredEntirely() {
            // 这是最保守也是默认的形态：谁都不可信，一律用 TCP 对端。
            // 反过来说，如果有人把可信网段配成了内网全段，这条保护就消失了。
            assertEquals(CLIENT, directOnly.resolve(CLIENT, "1.2.3.4"));
            assertEquals(PROXY, directOnly.resolve(PROXY, "1.2.3.4"));
        }

        @Test
        @DisplayName("对外暴露是否配置了可信代理，供日志与启动自检使用")
        void exposesConfigurationState() {
            assertFalse(directOnly.hasTrustedProxies());
            assertTrue(resolver.hasTrustedProxies());
            assertTrue(resolver.isTrustedProxy(PROXY));
            assertFalse(resolver.isTrustedProxy(CLIENT));
        }
    }
}
