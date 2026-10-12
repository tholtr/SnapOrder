package com.campusdash.infrastructure.config;

import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.ports.RiskEventPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 风险事件端口的空实现：MQ 关闭时**直接丢弃**事件。
 *
 * <p>与 {@link NoopDelayMessageAdapter} / {@link NoopFundEventAdapter} 的关键差别是
 * <b>它什么都不做，连一行日志都不记</b>——这不是偷懒，是这两条链路对「丢了什么」的
 * 容忍度不同：
 *
 * <pre>
 *   延迟消息 / 资金事件   丢的是「本该发生的事」，靠 local_message 兜底扫描补回来，
 *                        丢没丢是可以用表里的 PENDING 状态核对的
 *   风险事件            丢的是一条**观测数据**。决策已经做完并已经写进 risk_decision 行，
 *                        事件只是它的一个副本；没有兜底通道，也没有东西可以核对
 * </pre>
 *
 * <p>所以这里既没有可登记的兜底状态，也没有可报的异常。<b>唯一的替代品是噪音</b>：
 * 测试 yaml 的 {@code dash.mq.enabled=false} 意味着几乎全部集成测试都在用这个实现，
 * 每次决策都记一行 debug 会把真正有用的日志埋掉。而「MQ 关了所以事件没出去」这件事
 * 本身有配置可查，不需要靠日志声明。
 *
 * <p>于是这个类的价值只有两个：让应用在没有 broker 时能启动，以及让
 * {@code RiskEventPort} 这个注入点在任何 profile 下都非空。**它是 P9.5 的对照实验里
 * 「聚合关闭」那一侧的基准**——那一侧看到的事件数应当恰好为零，多一条就说明有别的
 * 通路在写 topic。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "false", matchIfMissing = true)
public class NoopRiskEventAdapter implements RiskEventPort {

    /**
     * 刻意留空。{@link RiskEvent} 不读、不校验、不记——连 {@code eventId()} 都不碰。
     *
     * <p>参数命名不做下划线占位（如 {@code ignored}）：调用方看的是接口，看不到这里；
     * 而实现体里出现一个从未被读的名字，比一个诚实的空实现更容易被误读成「漏写了」。
     * 说明写在 javadoc 里，不写在参数名上。
     */
    @Override
    public void publish(RiskEvent event) {
    }
}
