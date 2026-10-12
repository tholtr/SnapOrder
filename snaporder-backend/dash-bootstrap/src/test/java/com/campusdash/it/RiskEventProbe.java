package com.campusdash.it;

import com.campusdash.domain.risk.ports.RiskEventPort;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * 挂在风险事件 topic 上的**观察者消费者**：把 broker 上真经过的消息收下来，供断言直接查。
 *
 * <p>为什么不能只看那两行日志：日志是**进程自己说它做了什么**，而本用例要证明的是
 * 「消息真的过了一遍 broker」。两者不是一回事——生产者可以在本地把日志写完而消息压根没发出去
 * （{@code local_message} 那次事故就是这么来的）。日志负责把两个进程串起来（同一个
 * {@code eventId} 出现在发布行和消费行里），broker 上的这份载荷负责证明确有消息在流动。
 *
 * <h2>自己的消费组 {@value #GROUP}</h2>
 *
 * <p>必须与 worker 的 {@code dash-risk-event-consumer} 分开：同组会**抢消息**，
 * 组里两个消费者各拿一半，于是「worker 收到了吗」变成一条概率性断言。
 * 这个组名与另两个一样写在 {@code docker/init-mq.sh} 里（新建消费组需要 broker 侧先存在，
 * 没写脚本的话本地能跑、干净机器上超时）。
 *
 * <h2>队列只增不减</h2>
 *
 * <p>收到的消息全部留在表里，{@link #await} 只读不取。因为用例既要「等到某条」，
 * 也要「数一共几条」——取走就数不了了。测试消息量在个位数，不清理也不会长大。
 */
final class RiskEventProbe implements AutoCloseable {

    /**
     * 观察者消费组。**必须与 worker 的消费组不同**（见类注释），
     * 也必须与 {@code docker/init-mq.sh} 里写的一致。
     */
    static final String GROUP = "dash-risk-event-it";

    private static final long POLL_MILLIS = 100;

    private final PushConsumer consumer;
    private final List<Received> all;

    private RiskEventProbe(PushConsumer consumer, List<Received> all) {
        this.consumer = consumer;
        this.all = all;
    }

    /** 一条收到的消息。{@code keys} 取第一个 key（本仓库只用 eventId 一个）。 */
    record Received(String body, String keys, int deliveryAttempt) {
    }

    static RiskEventProbe start(String endpoint) throws Exception {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration config = ClientConfiguration.newBuilder()
                .setEndpoints(endpoint)
                .setRequestTimeout(Duration.ofSeconds(10))
                .build();

        // 监听器要写、实例化又在 build() 之后，所以先有 sink 再有 probe。
        // 写成一个字段再让监听器捕获 this 也能编译，但那样 close() 之前就有一份
        // 「半成品实例」在跑，这个 sink 的写法把顺序摆明。
        List<Received> sink = Collections.synchronizedList(new ArrayList<>());
        PushConsumer consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(config)
                .setConsumerGroup(GROUP)
                .setSubscriptionExpressions(Collections.singletonMap(
                        RiskEventPort.TOPIC_RISK_EVENT, new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(view -> {
                    // 一律 ACK：探针不参与重投语义（那是 RiskEventProcessor 的事），
                    // 返回 FAILURE 会让 broker 重投，把「数了几条」搅成「重试了几次」。
                    sink.add(new Received(
                            StandardCharsets.UTF_8.decode(view.getBody()).toString(),
                            view.getKeys().isEmpty() ? null : view.getKeys().iterator().next(),
                            view.getDeliveryAttempt()));
                    return ConsumeResult.SUCCESS;
                })
                .build();
        return new RiskEventProbe(consumer, sink);
    }

    /** 当前收到的全部消息的快照（拷贝，调用方随便遍历）。 */
    List<Received> snapshot() {
        synchronized (all) {
            return List.copyOf(all);
        }
    }

    /** 满足条件的那些消息。 */
    List<Received> matching(Predicate<Received> matcher) {
        return snapshot().stream().filter(matcher).toList();
    }

    /** 等第一条满足条件的消息，超时返回 {@code null}。不取走任何东西。 */
    Received await(Predicate<Received> matcher, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (Received r : snapshot()) {
                if (matcher.test(r)) {
                    return r;
                }
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            if (!sleep()) {
                return null;
            }
        }
    }

    /**
     * 「第一条到了之后，再静默 {@code quiet} 那么久，期间没有第二条」——数「只发了一条」用的。
     *
     * <p>不能只数一次就断言：断言跑在第一秒，而第二条消息**可能第三秒才到**，
     * 那样「恰好 1 条」会是假绿。所以必须等一个静默窗口——窗口取多大是权衡：
     * 实测一条事件从发布到消费 100ms 上下，取 3 秒已是它的 30 倍。
     *
     * @return 第一条等到了且静默期内没有新增返回 {@code true}
     */
    boolean awaitQuiet(Predicate<Received> matcher, Duration quiet, Duration timeout) {
        if (await(matcher, timeout) == null) {
            return false;
        }
        int baseline = matching(matcher).size();
        long deadline = System.nanoTime() + quiet.toNanos();
        while (System.nanoTime() < deadline) {
            if (matching(matcher).size() > baseline) {
                return false;
            }
            if (!sleep()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sleep() {
        try {
            Thread.sleep(POLL_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── 载荷取值 ────────────────────────────────────────────────────────────
    //
    // 只够解析本仓库自己拼出来的载荷（RocketMqRiskEventAdapter.payloadOf），
    // 不引入 JSON 库：那会为了读四个字段把一个依赖加进 bootstrap 的测试类路径，
    // 而适配器那头的转义规则已经有它自己的单测逐字锁着（RiskEventPublishLogTest 一族）。
    // 本用例发出去的值全是 [A-Za-z0-9._:-]，不会被转义，所以这里「找引号」的取法是准的。

    /** 取一个字符串字段，例如 {@code requestId}；字段不在载荷里返回 {@code null}。 */
    static String payloadString(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int from = body.indexOf(marker);
        if (from < 0) {
            return null;
        }
        int start = from + marker.length();
        int end = body.indexOf('"', start);
        return end < 0 ? null : body.substring(start, end);
    }

    /**
     * 取数值/布尔字段的**字面量**（{@code "errandId":123} → {@code "123"}），不做类型转换。
     *
     * <p>返回字符串而不是 long/boolean：本类里没有任何一处需要拿它做运算，
     * 而 {@code assertTrue("true".equals(...))} 在失败时能把错的那个值打出来，
     * 比 {@code assertTrue(payloadBool(...))} 只说一句 false 有用。
     */
    static String payloadRaw(String body, String name) {
        String marker = "\"" + name + "\":";
        int from = body.indexOf(marker);
        if (from < 0) {
            return null;
        }
        int start = from + marker.length();
        int end = body.indexOf(',', start);
        if (end < 0) {
            end = body.indexOf('}', start);
        }
        return end < 0 ? null : body.substring(start, end);
    }

    @Override
    public void close() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }
}
