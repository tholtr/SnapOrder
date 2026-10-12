package com.campusdash.domain.risk.ports;

import com.campusdash.domain.risk.model.RiskEvent;

/**
 * 风险事件的投递端口（P9.4 设计 §3.5）。
 *
 * <p>放在 domain/ports 而不是 application，理由同 {@code CacheEvictDelayPort}：
 * infrastructure 只依赖 domain，端口放 application 会让 infrastructure 反向依赖
 * application，ArchUnit 的「端口必须定义在 domain 层」规则会直接判违反。
 *
 * <p><b>三个刻意的缺失</b>，每一个都是为了避免把异步链路拖进同步主链路：
 *
 * <ol>
 *   <li><b>不返回任何东西、也不抛领域异常</b>。它必须能在任何情况下立即返回——蓝图
 *       §3.9 要求事件投递不得阻塞抢单。返回 {@code boolean} 会诱使调用方写
 *       {@code if (!published)} 分支，而那正是蓝图要避开的「异步异常影响同步结果」。
 *       投递失败由适配器自己记 {@code risk_event_publish_failed} 行。</li>
 *   <li><b>不把 topic 作为参数</b>。常量 {@link #TOPIC_RISK_EVENT} 放在这里，是为了让
 *       实现方与消费方共用同一个字面量，<b>不是给调用方的参数</b>——调用方说的是
 *       「发一条风险事件」，它不该有机会说「发到哪个 topic」。</li>
 *   <li><b>不带 deadline、不参与 {@code RiskDeadline} 预算</b>。它在决策**之后**才被
 *       调用，已经在预算的作用域之外。给它一个 deadline 参数会让人以为它受预算管辖，
 *       而它真正需要的保证是「立刻返回」——那个由适配器的单线程有界队列提供（D6），
 *       不是由超时提供。</li>
 * </ol>
 *
 * <p><b>实现方要保证「立即返回」</b>：投递本身是异步的，{@link #publish} 只负责入队。
 * 队列满时丢弃并记 {@code DROPPED} 行（D9）——丢弃是明确的，不静默。
 */
public interface RiskEventPort {

    /**
     * 风险事件的 topic。消费组是 {@code dash-risk-event-consumer}（见 `docker/init-mq.sh`）。
     *
     * <p>普通类型，不是延迟消息：事件要在决策后尽快被看到，没有「多久之后才该被消费」
     * 的语义。这与 {@code errand-cache-evict}（延迟双删）是两类用途。
     */
    String TOPIC_RISK_EVENT = "errand-risk-event";

    /**
     * 投递一条风险事件。**立即返回**，不等 broker 确认。
     *
     * <p>重复调用同一个事件是安全的：消费端按 D12 不写 Redis、不写表、不做运行时幂等，
     * 重复投递只会产生重复日志，去重口径留给离线脚本（蓝图 §5.3 的第三条验收）。
     * 所以这里不需要「只能发一次」的约束——那会引入一个没有收益的失败路径。
     */
    void publish(RiskEvent event);
}
