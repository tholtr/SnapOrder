package com.campusdash.domain.risk.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("风控请求上下文")
class RiskContextTest {

    @Test
    @DisplayName("完整上下文原样保留")
    void keepsAllFields() {
        var ctx = new RiskContext(1001L, 2002L, "req-1", "device-abc", "1.2.3.4");

        assertEquals(1001L, ctx.userId());
        assertEquals(2002L, ctx.errandId());
        assertEquals("req-1", ctx.requestId());
        assertEquals("device-abc", ctx.deviceId());
        assertEquals("1.2.3.4", ctx.clientIp());
        assertTrue(ctx.hasDevice());
        assertTrue(ctx.hasClientIp());
    }

    /**
     * 空白归一化成 null 是这个 record 唯一的实质逻辑。少了它，下游每处
     * 判断缺失都得写成 {@code x == null || x.isBlank()}，而漏写一处不会报错——
     * 表现是某条风控键被空字符串拼出来（如 {@code risk:dev:}），所有缺设备的
     * 请求挤在同一个键上，共享同一个账号计数。
     */
    @ParameterizedTest
    @DisplayName("空白设备标识统一归一化为 null")
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    void normalizesBlankDeviceToNull(String blank) {
        var ctx = new RiskContext(1L, 1L, "req-1", blank, "1.2.3.4");

        assertNull(ctx.deviceId());
        assertFalse(ctx.hasDevice());
    }

    @ParameterizedTest
    @DisplayName("空白 IP 统一归一化为 null")
    @ValueSource(strings = {"", "   ", "\t"})
    void normalizesBlankIpToNull(String blank) {
        var ctx = new RiskContext(1L, 1L, "req-1", "device-abc", blank);

        assertNull(ctx.clientIp());
        assertFalse(ctx.hasClientIp());
    }

    @Test
    @DisplayName("null 与空串不区分，两种缺失形态归成一种")
    void treatsNullAndBlankIdentically() {
        var fromNull = new RiskContext(1L, 1L, "req-1", null, null);
        var fromBlank = new RiskContext(1L, 1L, "req-1", "", "  ");

        assertEquals(fromNull.deviceId(), fromBlank.deviceId());
        assertEquals(fromNull.clientIp(), fromBlank.clientIp());
        assertEquals(fromNull, fromBlank);
    }

    @Test
    @DisplayName("旧客户端不传设备：上下文仍然成立，只是设备维度不可用")
    void deviceIsOptional() {
        var ctx = new RiskContext(1L, 1L, "req-1", null, "1.2.3.4");

        assertFalse(ctx.hasDevice());
        assertTrue(ctx.hasClientIp(), "设备缺失不该影响 IP 维度");
    }

    /**
     * IP 解析失败时（例如 IPv6 直连）{@code ClientIpResolver} 返回 null，
     * 所以这里必须允许 null。若构造器强制非空，这类请求会在建上下文时就炸掉，
     * 把一次可降级的请求变成一次 500。
     */
    @Test
    @DisplayName("IP 解析不出来时上下文仍可构造，由下游跳过该维度")
    void ipMayBeAbsent() {
        var ctx = new RiskContext(1L, 1L, "req-1", "device-abc", null);

        assertFalse(ctx.hasClientIp());
        assertTrue(ctx.hasDevice());
    }

    /**
     * requestId 空白是本模块最隐蔽的失效方式：它不会让任何调用报错，
     * 只会让所有请求在窗口里塌缩成同一个成员，去重把每次都判成重复，计数恒为 1，
     * 规则再也触发不了。宁可在这里炸掉。
     */
    @Test
    @DisplayName("requestId 为空直接拒绝——它会让窗口去重把所有请求当成同一个")
    void rejectsBlankRequestId() {
        assertThrows(IllegalArgumentException.class,
                () -> new RiskContext(1L, 1L, null, "device-abc", "1.2.3.4"));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskContext(1L, 1L, "", "device-abc", "1.2.3.4"));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskContext(1L, 1L, "   ", "device-abc", "1.2.3.4"));
    }

    @Test
    @DisplayName("requestId 前后空白不裁剪，只判断是否为空白——裁剪会改变幂等语义")
    void doesNotTrimRequestId() {
        var ctx = new RiskContext(1L, 1L, " req-1 ", null, null);

        assertEquals(" req-1 ", ctx.requestId());
    }
}
