package com.campusdash.domain.risk.model;

/**
 * 一次请求的风控上下文——本次请求「是谁、从哪来、在做什么」的不可变事实。
 *
 * <p><b>⚠ 本对象持有原始设备标识与原始 IP，禁止整体入日志。</b>
 * 它不是决策结果，只是采集输入：P9.2 的规则判断需要的是哈希后的键，
 * 原始值只在 presentation 解析出来、交给 infra 适配器哈希之前存在。
 * 需要记录时请调用方自己哈希或脱敏，不要 {@code log.info("{}", context)}。
 *
 * <p>这是 {@code ..domain..model..} 下的类型，按分层约束不得依赖任何
 * {@code ..domain..ports..}——上下文不该知道自己会被怎么采集。
 *
 * @param requestId 客户端请求标识，<b>非空</b>。客户端未传时由服务端生成，
 *                  到这一层必须已经有值：空白值会让所有请求在窗口里
 *                  塌缩成同一个成员，去重把每一次请求都判成重复，计数恒为 1
 * @param deviceId  设备标识，<b>可空</b>。老客户端不传，为空时下游跳过设备维度、
 *                  不产生任何 {@code risk:dev:*} 键
 * @param clientIp  客户端 IP，正常情况下非空。为 {@code null} 表示对端地址无法解析
 *                  （如 IPv6 直连），下游跳过 IP 维度而不是退化成空串——
 *                  退化成空串会让所有这类请求共用一个风控桶
 */
public record RiskContext(
        long userId,
        long errandId,
        String requestId,
        String deviceId,
        String clientIp) {

    public RiskContext {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "requestId 不能为空。它由服务端在客户端未传时补齐，"
                            + "缺了它窗口去重会把所有请求当成同一个，计数恒为 1，"
                            + "规则再也触发不了——且不会报任何错，只是安静地失效。");
        }
        // 统一把空白归一化成 null。下游因此只需判断 null 一种缺失形态，
        // 不必在每处都写 `x == null || x.isBlank()`——两套缺失形态迟早会漏判一处。
        deviceId = blankToNull(deviceId);
        clientIp = blankToNull(clientIp);
    }

    /** 是否采集到了设备维度。为 false 时适配器不应产生任何 {@code risk:dev:*} 键 */
    public boolean hasDevice() {
        return deviceId != null;
    }

    /** 是否采集到了 IP 维度。为 false 时适配器应跳过 IP 相关的两条规则 */
    public boolean hasClientIp() {
        return clientIp != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
