package com.campusdash.domain.risk.model;

/**
 * 用户滑动窗口的采集结果。
 *
 * <p>本记录属于模型层而非端口内部的嵌套类型（P9.2 决策 D12）。原因是它有两个消费方：
 * 采集端口（{@code RiskFeaturePort.trackUserWindow}）产出它，而决策规则
 * （{@link RiskRules#evaluate}）消费它。领域模型不得依赖 {@code ..ports..}
 * （ArchUnit 规则三），所以只要决策函数要吃它，它就不能住在端口里。
 *
 * @param count10s          10 秒窗口内的请求数
 * @param count60s          60 秒窗口内的请求数
 * @param duplicateRequest  本次请求的 {@code requestId} 是否已在窗口内出现过。
 *                          只有精确重放才会为 true——窗口内的正常连续请求各有
 *                          不同的 {@code requestId}，不会互相判重
 */
public record UserWindow(int count10s, int count60s, boolean duplicateRequest) {
}
