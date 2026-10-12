package com.campusdash.presentation.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 判断一个 IP 是否落在配置的「可信代理」网段内。
 *
 * <p>只用来回答一个问题：<b>这条 TCP 连接的对端是不是我们自己的代理</b>。
 * 是，才有资格去读它追加的转发头；不是，转发头一律当伪造处理。
 * 这个判断是整条 IP 维度信任链的根——它错了，后面写得再对也没用。
 *
 * <p>配置形如 {@code "10.0.0.0/8, 172.16.0.0/12, 192.168.1.5"}，
 * 不带前缀长度按单机 /32 处理。空配置表示不信任任何代理，
 * 此时一律使用 TCP 连接的真实对端地址（最保守，也是默认值）。
 *
 * <p>网段格式错误会直接抛异常让应用起不来。这是刻意的：一个敲错的
 * 网段如果被静默忽略，表现是「代理突然不可信、全体用户挤在同一个 IP 桶里」，
 * 排查成本远高于启动失败。
 */
public final class CidrMatcher {

    /** 谁都不信。用于空配置，等价于完全关闭转发头读取。 */
    private static final CidrMatcher NONE = new CidrMatcher(List.of());

    private final List<Rule> rules;

    private CidrMatcher(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * @param cidrConfig 逗号分隔的网段；{@code null} 或空白表示不信任任何代理
     * @throws IllegalArgumentException 存在无法解析的网段
     */
    public static CidrMatcher parse(String cidrConfig) {
        if (cidrConfig == null || cidrConfig.isBlank()) {
            return NONE;
        }

        List<Rule> parsed = new ArrayList<>();
        for (String entry : cidrConfig.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;   // 容忍尾随逗号，那是手写配置的常见笔误且无歧义
            }
            parsed.add(Rule.parse(trimmed));
        }
        return parsed.isEmpty() ? NONE : new CidrMatcher(List.copyOf(parsed));
    }

    /**
     * @param ip 点分十进制 IPv4；{@code null}、IPv6 或任何非法格式一律返回 {@code false}
     */
    public boolean isTrusted(String ip) {
        Integer value = Ipv4.parse(ip);
        if (value == null) {
            return false;
        }
        for (Rule rule : rules) {
            if (rule.covers(value)) {
                return true;
            }
        }
        return false;
    }

    /** 是否配置了任何网段。为 false 时转发头完全不起作用。 */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    @Override
    public String toString() {
        return "CidrMatcher" + rules;
    }

    private record Rule(int network, int mask, String source) {

        static Rule parse(String text) {
            int slash = text.indexOf('/');
            String ipPart = slash < 0 ? text : text.substring(0, slash);
            int prefix = slash < 0 ? 32 : parsePrefix(text, slash);

            Integer network = Ipv4.parse(ipPart);
            if (network == null) {
                throw new IllegalArgumentException(
                        "可信代理网段「" + text + "」不是合法的 IPv4 地址或网段。"
                                + "本轮只支持 IPv4 与 0-32 的前缀长度，"
                                + "例如 10.0.0.0/8、192.168.1.5。");
            }
            // 前缀 0 时掩码为 0，不能走 (0xFFFFFFFF << 32)——Java 的移位是模 32 的，
            // << 32 等于不位移，会得到「全 32 位都必须相等」即 /32，正好与 /0 相反。
            int mask = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
            return new Rule(network & mask, mask, text);
        }

        private static int parsePrefix(String text, int slash) {
            String prefixPart = text.substring(slash + 1).trim();
            int prefix;
            try {
                prefix = Integer.parseInt(prefixPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "可信代理网段「" + text + "」的前缀长度不是数字：" + prefixPart);
            }
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException(
                        "可信代理网段「" + text + "」的前缀长度必须在 0-32 之间，收到：" + prefix);
            }
            return prefix;
        }

        boolean covers(int value) {
            return (value & mask) == network;
        }

        @Override
        public String toString() {
            return Objects.requireNonNull(source);
        }
    }
}
