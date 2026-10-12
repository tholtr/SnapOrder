package com.campusdash.worker;

import com.campusdash.domain.risk.ports.RiskEventPort;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 风险事件消费者（P9.4 设计 §6.3）：接入点，不含任何判断。
 *
 * <p>形状与 worker 里那四个消费者逐项对齐（{@code CacheEvictConsumer}、
 * {@code AutoSettleConsumer}、{@code TimeoutTransferConsumer}、{@code FundEventConsumer}）：
 * {@code @PostConstruct start()} 建消费者、{@code @PreDestroy stop()} 关它、
 * 订阅表达式用 {@code FilterExpression("*", TAG)}。
 *
 * <h2>与那四个的唯一区别：监听器里没有逻辑</h2>
 *
 * <p>它们的监听器里都留着「解 body → 取字段 → 调用例 → 接异常 → 返回 {@code SUCCESS}
 * 还是 {@code FAILURE}」这一整段，而那段是这个仓库最难测、也最容易写错的一段：
 * 四条路径里有两条只有在真实 broker 上才会出现。本类把整段交出去——只解一个 UTF-8
 * 字符串，连 {@code DeliveryAttempt} 一起交给 {@link RiskEventProcessor}，
 * 把它的返回值原样还给 broker。于是「消费失败要不要重投」这件事的**全部内容**
 * 都在一个不连中间件的类里（D12 与它的四条路径单测）。
 *
 * <h2>重试窗口不在这里设</h2>
 *
 * <p>按 D11，它声明在**消费组**上（`docker/init-mq.sh` 的 {@code -r 3}），
 * 所以本类既不调 {@code setRetryPolicy}、也不自己数重试次数。代价是一个跨文件的
 * 字面量耦合：<b>组名 {@code dash-risk-event-consumer} 同时出现在这里与那个脚本里</b>
 * （还有 {@code RiskEventPort.TOPIC_RISK_EVENT} 注释里）。改了这里而没改脚本，
 * 消费照样会跑，只是重试窗口静默退回客户端默认的 16 次——没有报错，没有日志。
 * 反过来说，这也是它必须走 {@code @Value} 默认值而不是一个私有常量的原因：
 * 三个地方写的必须是同一个字符串。
 *
 * <p>{@code setConsumptionThreadCount} **不设**（设计 §6.3）：风险消费是「解析 + 写日志」，
 * 没有 I/O 等待，加线程只会让 {@code risk_event_processed} 行的相对顺序更乱。
 * {@code TimeoutTransferConsumer} 需要 32 个线程是因为它每条消息都要跨进程调用。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class RiskEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RiskEventConsumer.class);

    private final ClientServiceProvider provider;
    private final ClientConfiguration configuration;
    private final RiskEventProcessor processor;
    private final String group;

    private PushConsumer consumer;

    /**
     * 组名走 {@code @Value} 的**字面**默认值，与 worker 里那四个消费者同一种写法
     * （设计 §7.1 已逐个核对过那四个都带 {@code :} 默认值）：worker 的 yaml 不写这个 key，
     * 缺 key 时用默认值而不是启动失败。
     *
     * <p>刻意没有把它提成一个常量再拼进注解里：那样 grep 这个组名时会先撞上常量定义，
     * 而这三处要对着看的地方（本注解、`docker/init-mq.sh` 的 {@code -r 3}、
     * {@code RiskEventPort.TOPIC_RISK_EVENT} 的注释）本来就是靠同一个字面量认出来的。
     */
    public RiskEventConsumer(ClientServiceProvider provider,
                             ClientConfiguration configuration,
                             RiskEventProcessor processor,
                             @Value("${dash.mq.group.risk-event:dash-risk-event-consumer}") String group) {
        this.provider = provider;
        this.configuration = configuration;
        this.processor = processor;
        this.group = group;
    }

    /**
     * topic 取自 {@link RiskEventPort#TOPIC_RISK_EVENT} 而不是一个配置项：生产端与消费端
     * 共用同一个字面量，而它不该有第二个来源——一个能配错的 topic 就是一条静默断掉的链路
     * （发到 A、订阅 B，两侧都不报错）。
     *
     * <p>body 用 {@code StandardCharsets.UTF_8.decode(...)}：与生产端
     * {@code payloadOf(event).getBytes(UTF_8)} 配对。两处都写 UTF-8 是刻意的——
     * 只在一处指定等于默认值，而默认值不是契约。
     */
    @PostConstruct
    public void start() throws Exception {
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(group)
                .setSubscriptionExpressions(Map.of(RiskEventPort.TOPIC_RISK_EVENT,
                        new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(view -> processor.process(
                        StandardCharsets.UTF_8.decode(view.getBody()).toString(),
                        view.getDeliveryAttempt()))
                .build();
        log.info("风险事件消费者已启动 topic={} group={}", RiskEventPort.TOPIC_RISK_EVENT, group);
    }

    @PreDestroy
    public void stop() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }
}
