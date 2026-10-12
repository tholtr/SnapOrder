package com.campusdash.domain.risk.model;

/**
 * 风控的运行策略：关不关、以什么身份跑、算不出结论时怎么办、最多跑多久（P9.3 设计 §3.1）。
 *
 * <p><b>它为什么在领域层</b>：与 {@link RiskRules} 同一个理由——配置类
 * {@code RiskProperties} 在 {@code dash-infrastructure}，而消费它的用例在
 * {@code dash-application}，两者只共享 {@code dash-domain}。所以这里是一次纯粹的透传，
 * 不做任何解释，装配在 {@code RiskConfig}。
 *
 * <p><b>{@code enabled} 与 {@code mode} 是两个正交的字段，不是三个枚举值</b>：
 * 「关掉」是零 I/O、判完直接返回；{@code SHADOW} 是**完整地算**、只是不拦截，
 * 它的产出正是真实流量的决策分布。折叠成第三个 {@link RiskMode} 值就得回答
 * 「关闭时算不算 SHADOW」——而这个问题的答案会随着读代码的人而变。
 *
 * <p><b>{@code failOpen} 的作用面很窄</b>（P9.3 决策 D7）：它**只**决定
 * 「时间预算耗尽、且决策结果仍是 {@code PASS}」这一种情形是拒绝还是放行。
 * 它**不是**「任何降级都放行」——蓝图 §3.8 的「降级不等于放行」仍然成立：
 * 黑名单查不到但设备维度已经越线时，决策照样是 {@code BLOCK}，与 failOpen 无关。
 * 把作用面放宽的后果很实在：一次普通的局部降级会开始拒绝请求，而默认配置下
 * 那等于把风控从「少一个证据」变成「少一个证据就拒绝」。
 *
 * @param enabled    总开关。false 时预检不做任何 I/O，只回一个带规则版本的 PASS
 * @param mode       {@link RiskMode#SHADOW} 只算不拦；{@link RiskMode#ENFORCE} 才算数
 * @param failOpen   预算耗尽且决策为 PASS 时是否放行。true（默认）= 放行
 * @param deadlineMs 预检的时间预算（毫秒）。<b>0 是合法值</b>，语义是「一步都不许跑」，
 *                   即强制走降级路径——这是故障降级唯一的纯配置触发方式（决策 D11）。
 *                   负值由配置类在绑定期拒掉；本类不重复校验，
 *                   因为生产上唯一构造它的地方是 {@code RiskConfig}。
 */
public record RiskPolicy(boolean enabled, RiskMode mode, boolean failOpen, int deadlineMs) {
}
