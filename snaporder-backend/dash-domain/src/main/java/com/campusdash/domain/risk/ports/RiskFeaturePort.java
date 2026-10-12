package com.campusdash.domain.risk.ports;

import com.campusdash.domain.risk.model.Cluster;
import com.campusdash.domain.risk.model.UserWindow;

/**
 * 风控特征采集端口。
 *
 * <p><b>它只负责「采集」，不负责「判断」。</b>所有方法返回原始计数，
 * 没有任何一个是布尔或决策结果。这条边界是刻意的（决策 D3）：
 * 阈值将来一定会调，调阈值不该动采集层；反过来，采集层一旦内嵌了
 * 「超过多少就算异常」，阈值就散落在两个地方，改一处漏一处。
 * 判断属于 P9.2 的规则层（{@code RiskRules}）。
 *
 * <p>两个返回记录 {@link UserWindow} 与 {@link Cluster} 定义在
 * {@code ..domain.risk.model..} 而不是本接口内部（P9.2 决策 D12）：
 * 决策规则要消费它们，而模型层不得依赖 {@code ..ports..}（ArchUnit 规则三）。
 * 端口依赖模型是允许的方向，反过来不行。
 *
 * <p>方法签名沿用仓库既有端口的风格（对齐 {@code GrabSlotPort.tryAcquire(errandId, runnerId, requestId)}）：
 * 参数是扁平标量，不收 {@link com.campusdash.domain.risk.model.RiskContext} 整体——
 * 传整个上下文会让实现方顺手读走一些它本不该关心的字段，
 * 也让单测必须造一个完整的上下文才能验证一个维度。
 *
 * <p>不抛领域异常。Redis 不可用时由适配器决定降级语义：本阶段（P9.1）异常直接向上抛，
 * P9.3 才接入 deadline 与 {@code fail-open}。
 */
public interface RiskFeaturePort {

    /**
     * 用户维度的滑动窗口计数，一次往返取回两个窗口。
     *
     * <p>同一 {@code requestId} 命中任一窗口的已有成员即视为重复请求，
     * 此时两个窗口都不再加计数。这是重试幂等的依据：客户端重试不该被算成刷单。
     *
     * @param requestId 非空，由调用方保证（见 {@code RiskContext} 的构造约束）
     * @return 两个窗口的计数与本次是否重复
     */
    UserWindow trackUserWindow(long userId, String requestId);

    /**
     * 设备与 IP 三个维度，<b>一次往返取回</b>（实现期修正，见下）。
     *
     * <p>三个维度服务于同一次请求，因此合成一个方法。这直接对应
     * {@code risk_cluster.lua} 为什么是一个脚本：拆成三次调用就是三次往返，
     * 与蓝图 §3.8 的 10ms 总 deadline 冲突。
     *
     * <p><b>为什么初稿的四个独立方法不能这么实现</b>：初稿把设备账号、IP 账号、
     * IP 速率写成三个方法，但三者由同一个脚本算出。若适配器把每个方法都实现成
     * 「调用一次脚本、只取其中一项」，就会产生两类错误：
     *
     * <ul>
     *   <li>IP 速率那次调用会重复执行 {@code ZADD}——脚本每次调用都记一次请求，
     *       于是「一次请求」被记成三次，计数虚高到三倍，且不报错；</li>
     *   <li>三次往返，恰好是合并脚本要避免的那件事。</li>
     * </ul>
     *
     * <p>两个 {@code String} 参数都可空，缺失的维度在其结果字段上返回 0，
     * 且<b>不产生对应的 Redis 键</b>——缺失维度的请求不该在 Redis 里留下痕迹，
     * 否则这些残留键会随老客户端一直堆积。两者都缺失时适配器直接返回
     * {@link Cluster#EMPTY}，连脚本都不调用。
     *
     * @param deviceId 可空。为 {@code null} 或空白时 {@code deviceAccounts} 为 0，
     *                 不产生 {@code risk:dev:*} 键
     * @param clientIp 可空。为 {@code null} 或空白时 {@code ipAccounts} 与
     *                 {@code ipRequests} 均为 0，不产生 {@code risk:ip:*} 键
     */
    Cluster trackCluster(long userId, String deviceId, String clientIp);
}
