package com.campusdash.presentation.risk;

/**
 * IPv4 地址的严格解析与格式化。
 *
 * <p><b>为什么不用 {@code InetAddress.getByName}</b>：X-Forwarded-For 的内容完全由
 * 客户端控制。{@code getByName} 对非数字输入会走 DNS 解析——攻击者只要塞一个
 * 主机名进来，就能让服务器替他做一次 DNS 查询，既是 SSRF 跳板也是放大攻击。
 * 所以这里只认严格的点分十进制，不认主机名、不认 IPv6、不认缩写形式。
 *
 * <p>本轮只支持 IPv4（蓝图 §3.4 默认只信任服务端真实连接地址）。
 * IPv6 一律解析失败，由调用方按「不可信」处理——这是安全默认：
 * 宁可漏信一个真实代理，也不能把伪造的转发头当成本地流量。
 */
final class Ipv4 {

    private Ipv4() {
    }

    /**
     * 解析点分十进制 IPv4。必须恰好四段、每段 0-255、无前导零。
     *
     * @return 32 位无符号值；任何非法输入返回 {@code null}
     */
    static Integer parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        int result = 0;
        int octet = 0;
        int digits = 0;
        int completed = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                if (digits == 0 || ++completed > 3) {
                    return null;   // 空段（"1..2.3.4"、".1.2.3"、末尾带点）或段数超过四
                }
                result = (result << 8) | octet;
                octet = 0;
                digits = 0;
            } else if (c >= '0' && c <= '9') {
                // 前导零必须拒绝。"010.1.1.1" 在 Java 里会被 Integer.parseInt 当成十进制 10，
                // 但不少系统（C 的 inet_aton、部分代理）按八进制解析成 8——
                // 同一串地址在链路两端指向不同机器，这正是绕过 IP 白名单的经典手法。
                boolean hasMore = i + 1 < text.length() && text.charAt(i + 1) != '.';
                if (digits == 0 && c == '0' && hasMore) {
                    return null;
                }
                octet = octet * 10 + (c - '0');
                if (++digits > 3 || octet > 255) {
                    return null;
                }
            } else {
                return null;   // IPv6 的 ':'、主机名的字母、空白与端口都在这里被挡掉
            }
        }

        if (digits == 0 || completed != 3) {
            return null;
        }
        return (result << 8) | octet;
    }

    static String format(int value) {
        return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "."
                + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
