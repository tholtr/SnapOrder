package com.campusdash.presentation.risk;

/**
 * 一次 HTTP 请求里与风控相关的那些字段，由 {@link RiskContextInterceptor} 解析后
 * 放进 request attribute（键是 {@link #ATTR}）。
 *
 * <p><b>为什么不直接存 {@code RiskContext}</b>：{@code RiskContext} 有
 * {@code errandId} 字段，而本拦截器挂在 {@code /api/**} 上，preHandle 阶段
 * 绝大多数路由（用户列表、钱包、登录后首页……）根本没有任务 id。
 * 若在那里塞一个 0 或 -1 顶替，这个「不存在」的值会一路流进 §6 的日志行和
 * P9.4 的风险事件，且没有任何地方会报错。所以本层只交付它真正知道的东西，
 * 由知道 {@code errandId} 的一方（P9.3 的抢单用例）组装 {@code RiskContext}。
 *
 * <p><b>⚠ 本对象持有原始设备标识与原始 IP。</b>它们在这里进入系统，
 * 由 infra 适配器负责哈希（决策 D1）。<b>禁止整体入日志</b>，
 * 排障只能打印哈希后的值——见 {@code RedisRiskFeatureAdapter}。
 *
 * @param userId    已由 {@code AuthInterceptor} 认证的用户
 * @param requestId 非空。客户端没传或传了空白时由服务端生成 UUID
 * @param deviceId  可空。客户端没传时为 {@code null}，此时设备维度整体跳过
 * @param clientIp  可空，<b>非空时一定是合法的 IPv4</b>（见 {@link Ipv4}）。
 *                  不可用时为 {@code null}，调用方跳过 IP 维度
 */
public record RiskRequest(long userId, String requestId, String deviceId, String clientIp) {

    /** request attribute 的键。 */
    public static final String ATTR = "riskRequest";
}
