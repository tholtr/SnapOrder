package com.campusdash.domain.risk.model;

/**
 * 设备与 IP 三个维度的采集结果。
 *
 * <p>与 {@link UserWindow} 同理，本记录属于模型层而非端口内部的嵌套类型
 * （P9.2 决策 D12）：采集端口产出它，决策规则消费它，而领域模型不得依赖
 * {@code ..ports..}。
 *
 * <p><b>三个维度合成一个记录，与采集脚本合成一个脚本是同一件事</b>——它们服务于
 * 同一次请求，拆开就要么多出往返次数，要么让 IP 请求数被重复计数（见
 * {@code RiskFeaturePort.trackCluster} 的说明）。
 *
 * @param deviceAccounts  该设备在窗口内关联过的账号数，上界为配置的
 *                        {@code device-window.threshold + 1}（达到即代表「已越线」，
 *                        再精确的数字对决策没有意义，而放开增长会让键内存无上界）
 * @param ipAccounts      该 IP 在窗口内关联过的账号数，上界同上
 * @param ipRequests      该 IP 在窗口内发出的请求数，上界为
 *                        {@code ip-rate-window.threshold + 1}。<b>这里不去重</b>，
 *                        每次调用都算一次，与 {@link UserWindow} 的语义相反：
 *                        用户窗口识别「脚本刷同一个人的抢单」需要去重，这里识别
 *                        「单个 IP 的异常请求量」，一旦去重，攻击者只要反复使用
 *                        同一个 {@code requestId} 就能把计数压到 1，规则形同不存在
 */
public record Cluster(int deviceAccounts, int ipAccounts, int ipRequests) {

    /** 两个维度都缺失时的结果，同时也是「不产生任何键」的返回值。 */
    public static final Cluster EMPTY = new Cluster(0, 0, 0);
}
