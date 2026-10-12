package com.campusdash.application.usecase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link GrabErrandUseCase.Command} 的契约（P9.3 设计 §6.2）。
 *
 * <p>单独成类而不是并进 {@code GrabErrandUseCaseTest}：那里测的是**编排**
 * （哪一步被调用、拒绝之后还发生什么），这里测的是**值对象本身**的形态。
 * 两者的失败原因完全不同，混在一起时一条 toString 断言红了要去翻整条抢单链路。
 * 与 {@code RiskPreCheckResultTest} 单独成类同一个理由。
 */
@DisplayName("抢单指令")
class GrabErrandCommandTest {

    /** 原始设备标识与原始 IP。刻意用能一眼认出的字面量，好让「泄漏」这件事无从争辩。 */
    private static final String DEVICE_ID = "raw-device-abc123";
    private static final String CLIENT_IP = "203.0.113.7";

    private static GrabErrandUseCase.Command full() {
        return new GrabErrandUseCase.Command(10001L, 2001L, "req-1", 60, DEVICE_ID, CLIENT_IP);
    }

    @Test
    @DisplayName("toString 逐字不含设备标识与 IP，且连字段名都不出现")
    void toStringCarriesNoDeviceOrIp() {
        String text = full().toString();

        // 逐字断言而不是「不含子串」：末者是黑名单，只能挡住被想到的那几种写法。
        // 写死整串则任何一处改动（包括「顺手加回一个字段」）都必须过这一关。
        assertEquals("Command[errandId=10001, runnerId=2001, requestId=req-1, creditScore=60]", text);

        // 下面两条在逐字断言下是冗余的，留着是为了让**失败信息自己说话**：
        // 有人改了 toString 之后，红的第一条会告诉他到底泄漏了什么，
        // 而不只是「期望 X 实际 Y」。
        assertFalse(text.contains(DEVICE_ID), "设备标识不能进日志");
        assertFalse(text.contains(CLIENT_IP), "原始 IP 不能进日志");
        // 字段名也不出现：留一个 deviceId=*** 会让人以为它是可开关的，而这里没有那个开关。
        assertFalse(text.contains("deviceId"), "连字段名都不该出现");
        assertFalse(text.contains("clientIp"));
    }

    @Test
    @DisplayName("两个便捷构造器把设备与 IP 置 null，其余字段照传")
    void convenienceConstructorsNullOutTheTwoPiiFields() {
        // 全仓 27 处既有调用点走的是这两个构造器。它们必须能编译且行为可预期，
        // 否则 P9.3 的接入就变成一次覆盖面很广的重构（设计 §6.2）。
        GrabErrandUseCase.Command three = new GrabErrandUseCase.Command(10001L, 2001L, "req-1");
        GrabErrandUseCase.Command four = new GrabErrandUseCase.Command(10001L, 2001L, "req-1", 60);

        assertNull(three.deviceId());
        assertNull(three.clientIp());
        assertEquals(0, three.creditScore(), "三参构造器把被忽略的信用分置 0");

        assertNull(four.deviceId());
        assertNull(four.clientIp());
        assertEquals(60, four.creditScore());
    }

    @Test
    @DisplayName("equals/hashCode 仍包含设备与 IP——刻意不脱敏")
    void equalsStillCoversThePiiFields() {
        // 与 toString 的处理**相反**，这是设计 §6.2 明确要求的：
        // 这两个字段确实是「这次调用是谁发起的」的一部分。把它们从 equals 里去掉，
        // 会制造两个「相等」但风控结果不同的 Command——设备维度的判定正是靠它区分的。
        GrabErrandUseCase.Command other = new GrabErrandUseCase.Command(
                10001L, 2001L, "req-1", 60, "raw-device-DIFFERENT", CLIENT_IP);

        assertNotEquals(full(), other, "只有设备标识不同，也必须判为两次不同的调用");

        // 哈希只断言「相等则同哈希」这个合法方向。反过来（不等则不同哈希）不是不变式，
        // 写上去是一条迟早会因为一次合法碰撞而变红的假断言。
        GrabErrandUseCase.Command same = new GrabErrandUseCase.Command(
                10001L, 2001L, "req-1", 60, DEVICE_ID, CLIENT_IP);
        assertEquals(full(), same);
        assertEquals(full().hashCode(), same.hashCode());
    }
}
