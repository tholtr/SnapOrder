package com.campusdash.presentation.risk;

import com.campusdash.domain.risk.model.RiskMode;
import com.campusdash.domain.risk.model.RiskPolicy;
import com.campusdash.presentation.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拦截器里那道「开关 × profile」双闸的门禁测试。
 *
 * <p>为什么必须测到这一层：解析器内部那道闸（{@code RiskContextResolver}）已经有单测，
 * 但真正决定「测试头会不会被读进内存」的是拦截器构造函数里的
 * {@code testContextEnabled && testProfile}。这条 AND 一旦被简化成其中一个条件，
 * 解析器的单测**照样全绿**——因为传给解析器的布尔值在测试里是我们自己写的。
 *
 * <p>与 {@code dash.auth.allow-header-identity} 的教训对应：那个开关是「后门优先于正门」，
 * 只要解析成功就跳过 token 校验。风控这边要证的是相反的性质——
 * 开关关闭时**连读取都不发生**，而不只是「读了之后忽略」。
 * 本测试用假请求记录每一次 header 读取，把这条性质变成可断言的事实。
 *
 * <p>不需要 Spring 上下文：{@code StandardEnvironment} 是 spring-core 里的普通类，
 * 设置 active profile 就是一行。这样这条用例留在秒级的单测里，
 * 不因为需要起应用而被塞进 IT。
 */
@DisplayName("风控上下文拦截器")
class RiskContextInterceptorTest {

    private static final String DEVICE_HEADER = RiskContextResolver.DEVICE_ID_HEADER;
    private static final String TEST_DEVICE_HEADER = RiskContextResolver.TEST_DEVICE_HEADER;
    private static final String TEST_IP_HEADER = RiskContextResolver.TEST_IP_HEADER;

    /** 带 test profile 的环境。 */
    private static StandardEnvironment testProfileEnv() {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("test");
        return env;
    }

    /** 不带任何 profile 的环境（生产形态）。 */
    private static StandardEnvironment prodEnv() {
        return new StandardEnvironment();
    }

    /**
     * 拦截器只看 {@code enabled} 一项，另外三项对它是无关字段，取生产默认值即可——
     * 它们会不会被误读在这里没有可观测的差别，而 DEFAULT 值一旦写错，
     * 本类用例也测不出来（那是 {@code RiskConfigTest} 的职责）。
     */
    private static RiskPolicy policy(boolean enabled) {
        return new RiskPolicy(enabled, RiskMode.SHADOW, true, 10);
    }

    private static RiskContextInterceptor interceptor(boolean switchOn, boolean testProfile) {
        return new RiskContextInterceptor(
                "",                    // 无可信代理
                switchOn,              // dash.risk.test-context-enabled
                policy(true),          // dash.risk.enabled 走领域 bean（P9.3 D10）
                testProfile ? testProfileEnv() : prodEnv());
    }

    /** 带已认证用户的请求，默认设备号 device-real、测试头 device-forced。 */
    private static FakeRequest authenticatedRequest() {
        FakeRequest req = new FakeRequest();
        req.attributes.put(CurrentUser.ATTR, 1001L);
        req.headers.put(DEVICE_HEADER, "device-real");
        req.headers.put(TEST_DEVICE_HEADER, "device-forced");
        req.headers.put(TEST_IP_HEADER, "10.9.9.9");
        req.headers.put(RiskContextResolver.REQUEST_ID_HEADER, "req-1");
        return req;
    }

    private static RiskRequest contextOf(FakeRequest req) {
        return (RiskRequest) req.attributes.get(RiskRequest.ATTR);
    }

    @Nested
    @DisplayName("双闸：开关 × profile")
    class Gates {

        @Test
        @DisplayName("开关打开且 profile 含 test 时，测试头生效")
        void bothGatesOpen() {
            FakeRequest req = authenticatedRequest();

            interceptor(true, true).preHandle(req, null, new Object());

            RiskRequest ctx = contextOf(req);
            assertNotNull(ctx);
            assertEquals("device-forced", ctx.deviceId());
            assertEquals("10.9.9.9", ctx.clientIp());
            assertTrue(req.headersRead.contains(TEST_DEVICE_HEADER), "两闸都开时应当读取测试头");
        }

        @Test
        @DisplayName("开关关闭时测试头不生效，而且根本没有被读取")
        void switchOffDoesNotEvenRead() {
            FakeRequest req = authenticatedRequest();

            interceptor(false, true).preHandle(req, null, new Object());

            assertEquals("device-real", contextOf(req).deviceId(), "开关关闭时测试头必须无效");
            // 「读了再忽略」和「根本不读」在行为上等价，但后者少一次
            // 「值已经进了内存、只差一个判断写错」的机会。这条断言就是那句注释的证据。
            assertFalse(req.headersRead.contains(TEST_DEVICE_HEADER),
                    "开关关闭时不该读取测试设备头，实际读取过：" + req.headersRead);
            assertFalse(req.headersRead.contains(TEST_IP_HEADER),
                    "开关关闭时不该读取测试 IP 头，实际读取过：" + req.headersRead);
        }

        @Test
        @DisplayName("profile 不含 test 时，即使开关打开测试头也不生效、也不读")
        void profileGateAloneIsEnoughToBlock() {
            // 这一条防的是「本地调试顺手把 yaml 里的开关打开、提交时忘了关」。
            // 只有配置开关一个条件的话，这种提交就能让测试头在生产生效。
            FakeRequest req = authenticatedRequest();

            interceptor(true, false).preHandle(req, null, new Object());

            assertEquals("device-real", contextOf(req).deviceId(), "非 test profile 下测试头必须无效");
            assertFalse(req.headersRead.contains(TEST_DEVICE_HEADER),
                    "非 test profile 下不该读取测试设备头，实际读取过：" + req.headersRead);
        }

        @Test
        @DisplayName("dash.risk.enabled=false 时整个拦截器不做事")
        void disabledInterceptorDoesNothing() {
            FakeRequest req = authenticatedRequest();
            RiskContextInterceptor off =
                    new RiskContextInterceptor("", true, policy(false), testProfileEnv());

            assertTrue(off.preHandle(req, null, new Object()), "拦截器永远不该中断请求");

            assertNull(contextOf(req), "关闭时不该写入任何上下文");
            assertTrue(req.headersRead.isEmpty(), "关闭时不该读任何头，实际读取过：" + req.headersRead);
        }
    }

    @Nested
    @DisplayName("上下文写入")
    class Context {

        @Test
        @DisplayName("真实字段进入 RiskRequest，requestId 与设备号原样带出")
        void writesResolvedContext() {
            FakeRequest req = authenticatedRequest();

            interceptor(false, false).preHandle(req, null, new Object());

            RiskRequest ctx = contextOf(req);
            assertNotNull(ctx);
            assertEquals(1001L, ctx.userId());
            assertEquals("req-1", ctx.requestId());
            assertEquals("device-real", ctx.deviceId());
            assertEquals("203.0.113.9", ctx.clientIp(), "无可信代理时应取对端地址");
        }

        @Test
        @DisplayName("没有已认证用户时什么都不存（不是存一个 userId=0 的上下文）")
        void skipsWhenUnauthenticated() {
            // 登录、健康检查、内部观测端点被 AuthInterceptor 排除，走到这里就是没有身份。
            // 塞一个 userId=0 的上下文比没有上下文危险得多：一个不存在的值会一路
            // 流进日志和风险事件，且没有任何地方会报错。
            FakeRequest req = new FakeRequest();
            req.headers.put(DEVICE_HEADER, "device-real");

            interceptor(true, true).preHandle(req, null, new Object());

            assertNull(contextOf(req), "没有已认证用户时不该写入上下文");
        }

        @Test
        @DisplayName("userId 不是 Long 时同样跳过（不信任 attribute 里塞了别的类型）")
        void skipsWhenUserIdIsWrongType() {
            FakeRequest req = new FakeRequest();
            req.attributes.put(CurrentUser.ATTR, "1001");

            interceptor(true, true).preHandle(req, null, new Object());

            assertNull(contextOf(req));
        }

        @Test
        @DisplayName("解析失败不中断请求，也不写入半个上下文")
        void resolveFailureDoesNotBreakRequest() {
            // 风控在本阶段没有任何消费者，为它中断一次正常的抢单是本末倒置。
            FakeRequest req = authenticatedRequest();
            req.throwOnRemoteAddr = true;

            boolean proceed = interceptor(true, true).preHandle(req, null, new Object());

            assertTrue(proceed, "解析失败也必须放行");
            assertNull(contextOf(req), "失败时不该留下半个上下文");
        }
    }

    /**
     * 手写的假请求，不需要 Mockito——全仓都没有 Mockito 依赖，
     * 这里只覆盖拦截器真正会调的 5 个方法。
     */
    private static final class FakeRequest extends HttpServletRequestWrapper {

        /**
         * {@code ServletRequestWrapper} 的构造函数拒绝 null，所以塞一个占位代理进去。
         * 它永远不会被调到：本类覆盖了拦截器会用到的全部方法。
         */
        private static final HttpServletRequest PLACEHOLDER = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> null);

        final Map<String, Object> attributes = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        /** 按顺序记下每一次 header 读取，用来证明「开关关闭时连读取都不发生」。 */
        final List<String> headersRead = new ArrayList<>();

        boolean throwOnRemoteAddr;

        private FakeRequest() {
            super(PLACEHOLDER);
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public String getHeader(String name) {
            headersRead.add(name);
            return headers.get(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            headersRead.add(name);
            return Collections.emptyEnumeration();
        }

        @Override
        public String getRemoteAddr() {
            if (throwOnRemoteAddr) {
                throw new IllegalStateException("模拟解析期异常");
            }
            return "203.0.113.9";
        }
    }
}
