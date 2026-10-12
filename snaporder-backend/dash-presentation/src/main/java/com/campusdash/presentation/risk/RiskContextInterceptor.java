package com.campusdash.presentation.risk;

import com.campusdash.domain.risk.model.RiskPolicy;
import com.campusdash.presentation.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collections;
import java.util.List;

/**
 * 解析风控上下文并放进 request attribute，<b>不做任何风控调用</b>。
 *
 * <p>采集的调用点在 P9.3（由抢单用例触发）。本阶段这个拦截器的全部作用是把
 * HTTP 层的事实解析好、放在一个约定的位置，让 P9.3 不必再碰 servlet 类型。
 *
 * <p><b>为什么不并入 {@code AuthInterceptor}</b>：认证是守卫，失败直接 401 中断；
 * 风控上下文是附加信息，解析失败不该中断请求。混在一起会让「解析失败怎么办」
 * 的语义变含混——一个应该 401，另一个应该照常放行。
 */
@Component
public class RiskContextInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RiskContextInterceptor.class);

    private final RiskContextResolver resolver;
    private final boolean enabled;
    private final boolean testContextEnabled;

    public RiskContextInterceptor(
            @Value("${dash.risk.trusted-proxy-cidrs:}") String trustedProxyCidrs,
            @Value("${dash.risk.test-context-enabled:false}") boolean testContextEnabled,
            RiskPolicy policy,
            Environment environment) {
        // 测试上下文生效需要两个条件同时满足，在这里一次算完：
        //   1. 配置开关打开（生产配置是 false）
        //   2. 当前 profile 含 test
        // 第二个条件不是多余的：只靠配置开关的话，一个「本地调试时顺手打开、
        // 提交时忘了关」的 yaml 就能让测试头在生产生效。
        boolean testProfile = environment.acceptsProfiles(Profiles.of("test"));
        this.testContextEnabled = testContextEnabled && testProfile;
        // 复用 ClientIpResolver，而不是另写一份 CIDR 解析：
        // 网段格式错误时它会抛异常让应用起不来，这条行为必须两处一致。
        this.resolver = new RiskContextResolver(
                new ClientIpResolver(trustedProxyCidrs), this.testContextEnabled);
        // 总开关来自领域 bean 而不是自己 @Value 读一遍（P9.3 决策 D10）：
        // 原来这里与 RiskPreCheckUseCase 各读一次 dash.risk.enabled，两处默认值
        // 只能靠注释维系一致（F-17）。现在两边注入的是同一个 RiskPolicy 实例，
        // 「关掉风控」只有一个真值来源——但它仍然是**守卫语义**上的关：
        // 关掉时连上下文都不解析，与 SHADOW 下照常解析、照常算不是一回事。
        this.enabled = policy.enabled();
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if (!enabled) {
            return true;
        }
        // 没有已认证用户就没有风控上下文可言。走到这里说明本条路径被
        // AuthInterceptor 排除了（登录、健康检查、内部观测端点），
        // 此时**什么都不存**——而不是塞一个 userId=0 的上下文。
        // 一个不存在的值一路传进风险事件，比没有上下文危险得多。
        Object current = req.getAttribute(CurrentUser.ATTR);
        if (!(current instanceof Long userId)) {
            return true;
        }
        try {
            req.setAttribute(RiskRequest.ATTR, resolver.resolve(sourceOf(req), userId));
        } catch (RuntimeException e) {
            // 绝不因为解析风控上下文而让请求失败：风控在本阶段没有任何消费者，
            // 为它中断一次正常的抢单是本末倒置。
            //
            // 日志里只能出现异常本身，不能打 request / header 的原始值——
            // 这里手上恰好有原始设备号与 IP（决策 D1）。
            log.warn("风控上下文解析失败，本次请求按无上下文继续：{}", e.toString());
        }
        return true;
    }

    /**
     * 从请求里抠出解析器需要的原始值。
     *
     * <p>两个测试头**只在开关打开时才读**（而不是读了再忽略）——
     * 减少一次「值已经进了内存、只差一个判断写错」的机会。
     * 这里用的 {@code testContextEnabled} 是「配置开关 AND test profile」的结果，
     * 与解析器内部那道闸是同一个布尔值。
     *
     * <p>转发头用 {@code getHeaders()} 取**全部**值，不是 {@code getHeader()} 取第一条：
     * 攻击者发一条、代理不追加内容而是再发一条，是转发头走私的经典手法，
     * 只读第一条就会读到伪造值。
     */
    private RiskContextResolver.RiskRequestSource sourceOf(HttpServletRequest req) {
        List<String> forwarded = Collections.list(req.getHeaders(RiskContextResolver.FORWARDED_FOR_HEADER));
        return new RiskContextResolver.RiskRequestSource(
                req.getHeader(RiskContextResolver.REQUEST_ID_HEADER),
                req.getHeader(RiskContextResolver.DEVICE_ID_HEADER),
                req.getRemoteAddr(),
                forwarded,
                testContextEnabled ? req.getHeader(RiskContextResolver.TEST_DEVICE_HEADER) : null,
                testContextEnabled ? req.getHeader(RiskContextResolver.TEST_IP_HEADER) : null);
    }
}
