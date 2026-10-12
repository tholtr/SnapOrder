package com.campusdash.presentation.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("IPv4 严格解析")
class Ipv4Test {

    /** 用 intValue() 取值，避开 assertEquals(int, Integer) 的重载二义性。 */
    private static int parse(String ip) {
        return Ipv4.parse(ip).intValue();
    }

    @Test
    @DisplayName("常规地址按四段打包，高位在前")
    void parsesDottedQuad() {
        assertEquals(0, parse("0.0.0.0"));
        assertEquals(1, parse("0.0.0.1"));
        assertEquals(0x01020304, parse("1.2.3.4"));
        // 最高位字节超过 127 时不能因为 int 有符号而出错
        assertEquals(-1, parse("255.255.255.255"));
        assertEquals(0x0A000001, parse("10.0.0.1"));
    }

    @Test
    @DisplayName("边界值 255 可解析，256 不可")
    void octetRange() {
        assertEquals(0xFFFFFF00, parse("255.255.255.0"));
        assertNull(Ipv4.parse("256.1.1.1"));
        assertNull(Ipv4.parse("1.2.3.256"));
    }

    @Test
    @DisplayName("段数必须恰好四段")
    void requiresExactlyFourOctets() {
        assertNull(Ipv4.parse("1.2.3"));
        assertNull(Ipv4.parse("1.2.3.4.5"));
        assertNull(Ipv4.parse("1"));
    }

    @ParameterizedTest
    @DisplayName("空段一律拒绝")
    @ValueSource(strings = {"1..2.3.4", ".1.2.3", "1.2.3.", "..", "."})
    void rejectsEmptyOctet(String input) {
        assertNull(Ipv4.parse(input));
    }

    /**
     * 这是本类存在的主要理由。{@code Integer.parseInt("010")} 得到 10（十进制），
     * 但 C 的 {@code inet_aton} 和不少反向代理把它当八进制解析成 8。
     * 同一串字符在链路两端指向不同机器，就是绕过 IP 白名单与限流的经典手法。
     */
    @ParameterizedTest
    @DisplayName("前导零一律拒绝——同一串地址在链路两端可能指向不同机器")
    @ValueSource(strings = {"010.1.1.1", "1.02.3.4", "1.2.3.004", "00.0.0.1", "0x0A.0.0.1"})
    void rejectsLeadingZero(String input) {
        assertNull(Ipv4.parse(input));
    }

    @Test
    @DisplayName("单独的 0 是合法段，别把 0.0.0.0 误伤")
    void singleZeroIsValid() {
        assertEquals(0, parse("0.0.0.0"));
        assertEquals(0x00000001, parse("0.0.0.1"));
    }

    @ParameterizedTest
    @DisplayName("IPv6、主机名、空白、端口都不能解析成 IPv4")
    @ValueSource(strings = {
            "::1", "2001:db8::1", "fe80::1",
            "localhost", "example.com",
            " 1.2.3.4", "1.2.3.4 ", "1.2.3.4:80", "[1.2.3.4]",
            "1.2.3.4\n", "1.2.3.4\t",
    })
    void rejectsNonIpv4(String input) {
        assertNull(Ipv4.parse(input));
    }

    @Test
    @DisplayName("null 与空串不能抛出异常——调用方靠 null 判断跳过该维度")
    void rejectsNullAndEmpty() {
        assertNull(Ipv4.parse(null));
        assertNull(Ipv4.parse(""));
    }

    @Test
    @DisplayName("格式化与解析互逆")
    void formatRoundTrips() {
        for (String ip : new String[]{"0.0.0.0", "1.2.3.4", "10.0.0.1", "255.255.255.255"}) {
            assertEquals(ip, Ipv4.format(Ipv4.parse(ip)));
        }
    }

    @Test
    @DisplayName("格式化按无符号处理，不能出现负的段")
    void formatTreatsValueAsUnsigned() {
        assertEquals("255.255.255.255", Ipv4.format(-1));
        assertEquals("192.168.1.1", Ipv4.format(0xC0A80101));
    }
}
