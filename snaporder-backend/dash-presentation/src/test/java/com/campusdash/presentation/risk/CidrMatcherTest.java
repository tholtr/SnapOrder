package com.campusdash.presentation.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("可信代理网段匹配")
class CidrMatcherTest {

    private static final String PROXIES = "10.0.0.0/8, 172.16.0.0/12, 192.168.1.5";

    @Test
    @DisplayName("/8 覆盖整段，边界两侧一真一假")
    void matchesSlashEight() {
        CidrMatcher matcher = CidrMatcher.parse("10.0.0.0/8");

        assertTrue(matcher.isTrusted("10.0.0.0"), "网段第一个地址");
        assertTrue(matcher.isTrusted("10.255.255.255"), "网段最后一个地址");
        assertFalse(matcher.isTrusted("9.255.255.255"), "网段前一个地址不能命中");
        assertFalse(matcher.isTrusted("11.0.0.0"), "网段后一个地址不能命中");
    }

    @Test
    @DisplayName("不带前缀长度按单机 /32 处理")
    void bareAddressIsSlashThirtyTwo() {
        CidrMatcher matcher = CidrMatcher.parse("192.168.1.5");

        assertTrue(matcher.isTrusted("192.168.1.5"));
        assertFalse(matcher.isTrusted("192.168.1.6"));
        assertFalse(matcher.isTrusted("192.168.1.4"));
    }

    /**
     * Java 的移位是模 32 的：{@code 0xFFFFFFFF << (32 - 0)} 就是 {@code << 32}，
     * 结果等于不位移，掩码变成全 1——/0 会退化成 /32，语义正好相反。
     * 这条用例锁死这个坑。
     */
    @Test
    @DisplayName("/0 表示信任全部，不能因移位模 32 而退化成 /32")
    void slashZeroTrustsEverything() {
        CidrMatcher matcher = CidrMatcher.parse("0.0.0.0/0");

        assertTrue(matcher.isTrusted("0.0.0.0"));
        assertTrue(matcher.isTrusted("1.2.3.4"));
        assertTrue(matcher.isTrusted("255.255.255.255"));
    }

    @Test
    @DisplayName("非零网络地址配 /0 同样匹配全部——掩码为 0 时网络位不参与比较")
    void slashZeroIgnoresNetworkBits() {
        assertTrue(CidrMatcher.parse("1.2.3.4/0").isTrusted("203.0.113.9"));
    }

    @Test
    @DisplayName("网络地址带主机位时按掩码归位，不要求逐位相等")
    void masksOffHostBits() {
        // 10.1.2.3/8 与 10.0.0.0/8 是同一个网段，写成前者也不该失效
        assertTrue(CidrMatcher.parse("10.1.2.3/8").isTrusted("10.9.9.9"));
    }

    @ParameterizedTest
    @DisplayName("前缀长度边界")
    @ValueSource(strings = {"10.0.0.0/31", "10.0.0.0/30", "10.0.0.0/32", "10.0.0.0/0", "10.0.0.0/8"})
    void acceptsValidPrefixes(String cidr) {
        assertTrue(CidrMatcher.parse(cidr).isTrusted("10.0.0.0"));
    }

    @ParameterizedTest
    @DisplayName("非法前缀长度直接拒绝，让应用起不来")
    @ValueSource(strings = {"10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/abc", "10.0.0.0/", "10.0.0.0/8/8"})
    void rejectsInvalidPrefix(String cidr) {
        assertThrows(IllegalArgumentException.class, () -> CidrMatcher.parse(cidr));
    }

    @ParameterizedTest
    @DisplayName("非法网段直接拒绝——静默忽略会表现成「代理突然不可信」")
    @ValueSource(strings = {"10.0.0", "10.0.0.0.0", "256.0.0.1", "010.0.0.0", "abc", "10.0.0.0 /8x"})
    void rejectsInvalidNetwork(String cidr) {
        assertThrows(IllegalArgumentException.class, () -> CidrMatcher.parse(cidr));
    }

    @Test
    @DisplayName("多个网段逗号分隔，空白与尾随逗号容错")
    void parsesList() {
        CidrMatcher matcher = CidrMatcher.parse(" 10.0.0.0/8 ,172.16.0.0/12, 192.168.1.5 , ");

        assertTrue(matcher.isTrusted("10.1.1.1"));
        assertTrue(matcher.isTrusted("172.16.0.1"), "第二段的起点");
        assertTrue(matcher.isTrusted("172.31.255.255"), "第二段的终点");
        assertFalse(matcher.isTrusted("172.32.0.1"), "第二段之外");
        assertTrue(matcher.isTrusted("192.168.1.5"));
        assertFalse(matcher.isTrusted("192.168.1.4"));
    }

    @Test
    @DisplayName("空配置表示谁都不信，这是默认值")
    void emptyConfigTrustsNobody() {
        assertTrue(CidrMatcher.parse(null).isEmpty());
        assertTrue(CidrMatcher.parse("").isEmpty());
        assertTrue(CidrMatcher.parse("   ").isEmpty());
        assertFalse(CidrMatcher.parse(PROXIES).isEmpty());

        assertFalse(CidrMatcher.parse("").isTrusted("10.0.0.1"));
        // 只写了逗号也算空，但不该抛异常
        assertFalse(CidrMatcher.parse(" , , ").isTrusted("10.0.0.1"));
    }

    @ParameterizedTest
    @DisplayName("IPv6 与畸形地址一律不可信——安全默认，宁可漏信一个真代理")
    @ValueSource(strings = {"::1", "2001:db8::1", "localhost", "10.0.0.0/8", "010.0.0.1", "", " 10.0.0.1"})
    void nonIpv4IsNeverTrusted(String ip) {
        assertFalse(CidrMatcher.parse(PROXIES).isTrusted(ip), ip + " 不该被当成可信代理");
        assertFalse(CidrMatcher.parse("0.0.0.0/0").isTrusted(ip), ip + " 连全信任配置都不该认它");
    }

    @Test
    @DisplayName("null 不可信，不抛异常")
    void nullIsNotTrusted() {
        assertFalse(CidrMatcher.parse(PROXIES).isTrusted(null));
    }
}
