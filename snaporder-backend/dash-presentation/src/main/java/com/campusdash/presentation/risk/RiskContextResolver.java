package com.campusdash.presentation.risk;

import java.util.List;
import java.util.UUID;

/**
 * 把 HTTP 请求里的原始字段解析成 {@link RiskRequest}。纯逻辑，不含 servlet 类型，
 * 因此不需要 Spring 上下文就能单测——与 {@link Ipv4} / {@link CidrMatcher} /
 * {@link ClientIpResolver} 保持同一种形态。
 *
 * <p><b>解析与哈希的分界线在本类</b>（决策 D1）：这里产出的是**原始**设备标识与 IP，
 * 由 infra 层负责哈希。因此本类产出的对象带有个人信息，不得入日志。
 */
public final class RiskContextResolver {

    /** 客户端可传的请求 id 头。与 {@code ErrandController} 读的是同一个。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 客户端设备标识头。 */
    public static final String DEVICE_ID_HEADER = "X-Device-Id";

    /** 转发头。取值必须用 {@code getHeaders()} 而不是 {@code getHeader()}，见 {@link ClientIpResolver}。 */
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    /** 测试用设备标识头，仅在测试上下文开关打开时被读取。 */
    public static final String TEST_DEVICE_HEADER = "X-Test-Device-Id";

    /** 测试用客户端 IP 头，同上。 */
    public static final String TEST_IP_HEADER = "X-Test-Client-Ip";

    private final ClientIpResolver ipResolver;
    private final boolean testContextEnabled;

    /**
     * @param ipResolver         已按 {@code dash.risk.trusted-proxy-cidrs} 配好
     * @param testContextEnabled 已把「配置开关」与「test profile」两者取过与，
     *                           见 {@link RiskContextInterceptor}。本类只认这个最终布尔值，
     *                           免得「开关怎么算」散落两处
     */
    public RiskContextResolver(ClientIpResolver ipResolver, boolean testContextEnabled) {
        this.ipResolver = ipResolver;
        this.testContextEnabled = testContextEnabled;
    }

    public RiskRequest resolve(RiskRequestSource source, long userId) {
        String deviceId = normalize(source.deviceIdHeader());
        String clientIp = ipResolver.resolve(source.remoteAddr(), source.forwardedForHeaders());

        // 测试头只在开关打开时才可能生效。
        //
        // 这个开关与 dash.auth.allow-header-identity 是同一类东西，但要避免后者
        // 踩过的坑——那个开关是「后门优先于正门」：只要解析成功就跳过 token 校验。
        // 这里的形态是「测试头覆盖解析结果」，同样危险，所以两道闸：
        //   1. 拦截器在开关关闭时**根本不读**这两个头（连读取都不发生）
        //   2. 本类再判一次开关，开关关闭时忽略传进来的值
        // 第 2 道是防「有人直接在别处构造 source 并填了测试头」。
        if (testContextEnabled) {
            String testDevice = normalize(source.testDeviceId());
            if (testDevice != null) {
                deviceId = testDevice;
            }
            String testIp = normalizeTestIp(source.testClientIp());
            if (testIp != null) {
                clientIp = testIp;
            }
        }

        return new RiskRequest(userId, requestIdOf(source.requestIdHeader()), deviceId, clientIp);
    }

    /**
     * 客户端没传、传了空串、或传了纯空白时，由服务端生成。
     *
     * <p><b>空白必须当成「没传」</b>，这一点比看上去重要：用户滑动窗口拿
     * {@code requestId} 当去重成员，若把空串原样放行，所有「带了空 X-Request-Id」
     * 的请求会共用一个去重成员，窗口计数恒为 1，风控规则**静默失效**且不报错。
     * （{@code RiskContext} 的紧凑构造器也拦了这一条，但那是最后一道防线，
     * 不该指望调用方先撞上去。）
     *
     * <p><b>公开而非私有，是为了让 {@code ErrandController} 复用同一份口径。</b>
     * 抢单链路把 {@code requestId} 当幂等键，风控链路把它当窗口去重成员——
     * 两个用途对「这次请求是哪个请求」必须给出同一个答案。若各写一份判断，
     * 就会出现「风控认为这是新请求、抢单认为这是重试」这类只在组合里才暴露的矛盾。
     */
    public static String requestIdOf(String header) {
        String value = normalize(header);
        return value != null ? value : UUID.randomUUID().toString();
    }

    /**
     * 测试 IP 走与真实 IP 同一套严格解析，保证 {@code RiskRequest.clientIp} 的不变式
     * 「非空即合法 IPv4」不因测试路径破例。
     *
     * <p>非法值当作没传（返回 null），而不是原样放行：放行会让下游拿到一个
     * 无法解析的 IP 并照常哈希，测出来的东西和线上不是一回事。
     */
    private static String normalizeTestIp(String raw) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        Integer parsed = Ipv4.parse(value);
        return parsed == null ? null : Ipv4.format(parsed);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 拦截器从 {@code HttpServletRequest} 里抠出来的原始值。
     *
     * <p>做成一个记录而不是直接收 {@code HttpServletRequest}：单测里造一个记录是
     * 一行，造一个 servlet 请求要手搓几十个方法（本仓库没有 Mockito）。
     * 顺带也把「解析器需要请求的哪几个字段」写成了可读的契约。
     */
    public record RiskRequestSource(
            String requestIdHeader,
            String deviceIdHeader,
            String remoteAddr,
            List<String> forwardedForHeaders,
            String testDeviceId,
            String testClientIp) {
    }
}
