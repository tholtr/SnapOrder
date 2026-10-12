package com.campusdash.presentation.risk;

import java.util.List;

/**
 * 从 TCP 对端地址与转发头中解析出真实客户端 IP。
 *
 * <p>这是本阶段唯一有<b>主动攻击面</b>的逻辑。X-Forwarded-For 是客户端可以随便写的
 * 字符串，一个处理不当就等于把 IP 维度整条拱手让人：伪造头绕过 IP 限流、
 * 给每个请求换一个假 IP 让聚集规则永不触发、或者反过来把别人的 IP 写进去栽赃。
 *
 * <p><b>算法是「从右向左」的，方向写反是这个文件最容易犯且最难发现的错误。</b>
 * XFF 的语义是每个代理把自己的上游对端追加到末尾，所以<b>最右边才是离我们最近、
 * 由我们自己代理写入的那一段</b>，最左边完全由客户端控制。因此：
 * <ol>
 *   <li>TCP 对端不是可信代理 → 转发头一个字都不看，直接用对端地址。
 *       直连的客户端可以随便伪造 XFF，这一条挡住了绝大多数攻击。</li>
 *   <li>对端可信 → 从右往左走，跳过所有可信代理，
 *       <b>第一个不可信的地址就是客户端</b>，立刻返回、不再往左看。</li>
 *   <li>中途遇到无法解析的段 → 信任链已断，左边的一切都无法验证，返回对端地址兜底。</li>
 * </ol>
 *
 * <p>第 2 步「立刻返回」是安全关键：继续往左走就会进入客户端完全可控的区域。
 *
 * <p><b>已知取舍</b>：只支持 IPv4。可信代理网段里若真的存在 IPv6 代理，
 * 它的对端会解析失败并退回对端地址——宁可让同一个代理后面的用户共用一个桶，
 * 也不能把无法验证的地址当成客户端。
 */
public final class ClientIpResolver {

    private final CidrMatcher trustedProxies;

    public ClientIpResolver(String trustedProxyCidrs) {
        this.trustedProxies = CidrMatcher.parse(trustedProxyCidrs);
    }

    /**
     * @param remoteAddr      TCP 连接的真实对端地址，由容器提供，客户端无法伪造
     * @param forwardedFor    已按出现顺序拼接的 X-Forwarded-For，可为 {@code null}
     * @return 客户端 IPv4；对端地址本身就无法解析时返回 {@code null}，
     *         调用方须把 {@code null} 当作「该维度不可用」直接跳过，
     *         不要退化成空字符串或某个固定值——那会让所有这类请求共用一个风控桶
     */
    public String resolve(String remoteAddr, String forwardedFor) {
        Integer peer = Ipv4.parse(remoteAddr);
        if (peer == null) {
            return null;
        }

        // 直连：转发头不可信。这一条同时覆盖了「没配可信代理」的空配置情况。
        if (!trustedProxies.isTrusted(remoteAddr)) {
            return Ipv4.format(peer);
        }

        String leftmost = null;
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] entries = forwardedFor.split(",");
            for (int i = entries.length - 1; i >= 0; i--) {
                String entry = stripPort(entries[i].trim());
                Integer candidate = Ipv4.parse(entry);
                if (candidate == null) {
                    // 链断了，左边全是未经验证的内容，用对端地址兜底
                    break;
                }
                if (!trustedProxies.isTrusted(entry)) {
                    return Ipv4.format(candidate);
                }
                leftmost = Ipv4.format(candidate);
            }
        }

        // 走到这里有两种情况：
        //   1) 转发头为空 → leftmost 为 null，客户端就是那个可信对端本身（内部调用）
        //   2) 整条链都是可信代理 → 最左边那个就是最初的发起方
        return leftmost != null ? leftmost : Ipv4.format(peer);
    }

    /**
     * 拼接多条 X-Forwarded-For 头后再解析。
     *
     * <p>用 {@code List} 而不是只取第一条，是因为「另起一行」是转发头走私的经典手法：
     * 攻击者发一条 {@code X-Forwarded-For}，代理不追加内容而是<b>再发一条头</b>。
     * 只读第一条就会读到攻击者伪造的值。按出现顺序拼接后从右往左走，
     * 真实的代理段始终在右侧，伪造段被挡在外面。
     */
    public String resolve(String remoteAddr, List<String> forwardedForHeaders) {
        if (forwardedForHeaders == null || forwardedForHeaders.isEmpty()) {
            return resolve(remoteAddr, (String) null);
        }
        return resolve(remoteAddr, String.join(",", forwardedForHeaders));
    }

    /** 该地址是否为可信代理。供日志区分「直连解析」与「经代理解析」。 */
    public boolean isTrustedProxy(String ip) {
        return trustedProxies.isTrusted(ip);
    }

    /** 是否配置了可信代理网段。为 false 时转发头完全不起作用。 */
    public boolean hasTrustedProxies() {
        return !trustedProxies.isEmpty();
    }

    /**
     * 去掉 {@code IP:端口} 里的端口。真实部署中代理常常带端口追加，
     * 不处理的话整条链会因解析失败而退回对端地址——表现是<b>一个代理后面的
     * 所有用户挤进同一个 IP 桶</b>，合法用户会被误伤。
     *
     * <p>无法确认是「IPv4 + 端口」时原样返回，交给 {@link Ipv4#parse} 统一拒绝：
     * 只有一个拒绝出口，就不会出现两条路径判断不一致的漏洞。
     * 冒号多于一个（IPv6、畸形输入）不在这里处理。
     */
    private static String stripPort(String entry) {
        int colon = entry.indexOf(':');
        if (colon < 0 || entry.indexOf(':', colon + 1) >= 0) {
            return entry;
        }
        String port = entry.substring(colon + 1);
        if (port.isEmpty() || !port.chars().allMatch(Character::isDigit)) {
            return entry;
        }
        return entry.substring(0, colon);
    }
}
