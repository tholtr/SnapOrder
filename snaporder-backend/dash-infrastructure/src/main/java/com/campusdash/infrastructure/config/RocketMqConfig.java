package com.campusdash.infrastructure.config;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import com.campusdash.domain.wallet.ports.FundEventPort;
import com.campusdash.domain.wallet.ports.WalletRepository;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * RocketMQ 5.x 客户端装配（gRPC proxy 模式）。
 *
 * 用 dash.mq.enabled 开关控制：关闭时由 NoopDelayMessageAdapter 兜底，
 * 应用与集成测试在没有 MQ 的环境下仍能跑通，超时流转退化为纯靠 worker 扫描。
 * 这不只是为了方便——它本身就证明了"主通道 + 兜底"的价值。
 */
@Configuration
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class RocketMqConfig {

    @Bean
    public ClientServiceProvider rocketMqProvider() {
        return ClientServiceProvider.loadService();
    }

    @Bean
    public ClientConfiguration rocketMqClientConfiguration(
            @Value("${dash.mq.endpoints:127.0.0.1:8081}") String endpoints) {
        return ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .setRequestTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Producer 是线程安全的重量级对象，全局单例复用。
     * 每次发消息都新建 Producer 会导致连接爆炸——这是 MQ 接入最常见的性能错误。
     *
     * 关键：不在 setTopics 里预绑定 topic。
     *
     * 预绑定会让 Producer 在启动时就去 broker 拉取 topic 路由，topic 不存在就直接
     * 抛异常导致整个应用启动失败（实测踩过：重建 broker 容器后 topic 丢失，
     * 应用与所有集成测试全部起不来）。
     * 超时流转有兜底扫描通道，MQ 是可降级的——绝不能让它把整个应用拖死。
     * 不预绑定时路由改为首次发送时惰性获取，发送失败会被 dispatch 捕获，
     * 消息留在 local_message 里由 worker 重发。
     */
    @Bean(destroyMethod = "close")
    public Producer timeoutProducer(ClientServiceProvider provider,
                                    ClientConfiguration configuration) throws ClientException {
        return provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .build();
    }

    /**
     * 风险事件的 Producer（P9.4 的 F-22）。
     *
     * <p><b>为什么不复用 {@code timeoutProducer}</b>：一个 Producer 拿到的远不只是一条连接。
     * 5.2.0 里每个 {@code ProducerImpl} 都持有自己的 {@code ClientManagerImpl}（字段在
     * {@code ClientImpl} 上），而那个对象持有自己的 {@code rpcClientTable}
     * （{@code Endpoints → RpcClient}，每个 {@code RpcClient} 一个 gRPC
     * {@code ManagedChannel}），外加自己的 {@code scheduler} 与 {@code asyncWorker}。
     * 复用就等于让两类消息共用同一条通道和同一组回调线程。
     *
     * <p>而这两类消息的**要紧程度不同**：超时流转与延迟双删是正确性消息，慢一拍就是
     * 一次该流转的任务没流转；风险事件是**允许丢的遥测**——适配器自己就带着
     * 队列满即丢、闸门抑制、发送失败只记一行日志三条衰减路径。让可丢的流量去挤
     * 不可丢的通道，正是「主通道 + 兜底」最不该有的反向依赖。
     *
     * <p><b>代价写在明处</b>：每个进程多一条 gRPC 通道与两个线程池。所以这里是
     * 「按用途分开」，不是「每个适配器一个」——{@code RocketMqCacheEvictAdapter} 与
     * {@code RocketMqDelayMessageAdapter} 共用 {@code timeoutProducer} 是对的，
     * 它们的消息属于同一类。同理，本 bean 与 {@code fundEventPort} 里那个 Producer
     * 分开是**硬需求**（事务消息要 TransactionChecker，只能在构建时注册），
     * 而这里分开是取舍。
     *
     * <p><b>与 worker 的关系（如实记一笔）</b>：本类在 {@code infrastructure.config}，
     * 不在 {@code WorkerApplication} 排除的那两个 {@code risk} 包里，所以 worker 进程
     * 也会建出这个 bean，而它一个风险事件都不会发（{@code RocketMqRiskEventAdapter}
     * 本身被排除了）。后果是一个用不到的 Producer 与它那一条连接——不是缺陷，
     * 但也不是零成本，见 {@code 实现期发现.md}。
     *
     * <p>同样不在 {@code setTopics} 里预绑定 topic：理由与 {@code timeoutProducer}
     * 逐条相同，而风险事件的 topic 在开发环境里更可能还没建——那正是最不该让
     * 整个应用起不来的场合。
     */
    @Bean(destroyMethod = "close")
    public Producer riskEventProducer(ClientServiceProvider provider,
                                      ClientConfiguration configuration) throws ClientException {
        return provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .build();
    }

    /**
     * 资金事件适配器（事务消息）。
     *
     * 事务消息必须用带 TransactionChecker 的专用 Producer：
     * 5.x API 里 checker 只能在构建时注册（ProducerBuilder.setTransactionChecker），
     * 复用不带 checker 的 timeoutProducer 会在 beginTransaction 时直接抛
     * "Transaction checker should not be null"（线上联调实测踩过）。
     */
    @Bean
    public FundEventPort fundEventPort(ClientServiceProvider provider,
                                       ClientConfiguration configuration,
                                       WalletRepository walletRepository) throws ClientException {
        RocketMqFundEventAdapter adapter =
                new RocketMqFundEventAdapter(null, provider, walletRepository);
        Producer txProducer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTransactionChecker(adapter.checker())
                .build();
        adapter.attachProducer(txProducer);
        return adapter;
    }
}
