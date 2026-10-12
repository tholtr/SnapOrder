package com.campusdash.domain.risk.model;

/**
 * 三类精确黑名单的查询结果。
 *
 * <p>三条布尔各自独立，**不合并成一个「是否命中」**：三类命中对应三条不同的规则
 * （{@link RiskRuleId#BLACKLIST_USER} / {@link RiskRuleId#BLACKLIST_DEVICE}
 * / {@link RiskRuleId#BLACKLIST_IP}），虽然分值都等于 {@link RiskAction#BLOCK_MIN}，
 * 但日志里必须能区分是哪个维度命中的——「用户被封」和「IP 被封」的处置动作完全不同，
 * 而分值是看不出这个区别的。
 *
 * <p>缺失的维度恒为 {@code false}，且**不查那个键**：设备/IP 的键名带 HMAC 哈希，
 * 没有原始值时根本构造不出键名；而且缺失维度对应的键本来就不该存在。
 * 查询失败时调用方用 {@link #NONE} 继续，同时把
 * {@link DegradeReason#BLACKLIST_FAILED} 记进本次决策——三条 false 加上一条降级原因，
 * 与「查了、都没命中」在日志里是可区分的。
 *
 * @param user   用户维度（{@code risk:bl:user:{userId}}）是否命中
 * @param device 设备维度（{@code risk:bl:device:{deviceHash}}）是否命中。
 *               请求未带设备标识时恒为 false
 * @param ip     IP 维度（{@code risk:bl:ip:{ipHash}}）是否命中。
 *               取不到客户端 IP 时恒为 false
 */
public record BlacklistHits(boolean user, boolean device, boolean ip) {

    /** 三条全否。既是「查了都没命中」，也是查询失败时继续往下走的输入。 */
    public static final BlacklistHits NONE = new BlacklistHits(false, false, false);
}
