package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskEventGateType;
import com.campusdash.domain.risk.ports.RiskEventPort;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 风险事件端口在 MQ 开启时的实现（P9.4 设计 §4.4）。
 *
 * <h2>请求线程上只做三件不阻塞的事</h2>
 *
 * <p>{@code publish} 在**抢单主链路**上被调用，所以它绝对不许有网络往返：判据早退 →
 * 闸门定档 → {@code offer}。网络往返（{@code SETNX} 与 {@code sendAsync}）全部落在
 * {@link #executor} 那一个线程上。蓝图 §3.9 要的是「不能阻塞抢单主链路」，
 * 而这条约束的落点就是「{@code publish} 的方法体里没有任何 I/O」。
 *
 * <h2>为什么是单线程</h2>
 *
 * <p>线程上只有「组包 → 闸门 SETNX → sendAsync」三件事，而 {@code sendAsync} 本身不等待
 * 网络。并发在这里没有收益，只会让抑制判断的调用顺序变得不可预测——单线程下同一用户的
 * 连续事件天然有序，日志读起来是一条时间线。
 *
 * <h2>为什么不用 {@code CallerRunsPolicy}</h2>
 *
 * <p>队列满意味着异步侧已经拥塞，{@code CallerRunsPolicy} 会把这份拥塞原样搬到请求线程上
 * ——一次同步的 RPC 往返，恰好抵消掉 D6 的全部收益。所以用 {@link ThreadPoolExecutor.AbortPolicy}
 * 并接住 {@link RejectedExecutionException}：**丢弃是明确的**（记 {@code DROPPED} 行，D9），
 * 不是静默的。
 *
 * <h2>为什么是守护线程、且关停时不等</h2>
 *
 * <p>抢单进程关停时不该为风险事件多等一次投递。队列里没发出去的事件会丢——这是
 * best-effort 的边界，已写进设计 §10 的限制。{@code @PreDestroy} 里只 {@code shutdown()}，
 * **刻意不 {@code awaitTermination}**：那会让关停时间取决于 broker 有多慢。
 *
 * <h2>闸门定档为什么在这里、却是一个纯函数</h2>
 *
 * <p>定档是 {@link RiskEvent#gateType()}，不进这个类。设计 §4.4 把它写在「执行器线程上的
 * 流程」里，但 D9 的 {@code DROPPED} 行产生在**调用方线程**上（队列满时根本不会入队），
 * 那时执行器线程还没定档——按字面写会让 {@code DROPPED} 行的 {@code gateType=} 空着。
 * 定档没有任何 I/O（只是几个比较），所以提前到 {@code offer} 之前不花请求线程的时间，
 * 而四类结果的行从此都说得清「这条事件属于哪一类闸门」。
 *
 * <h2>安全约束（P9.1 决策 D1）</h2>
 *
 * <p>载荷里不得出现原始 {@code deviceId} / {@code clientIp}。{@link RiskEvent} 里本来
 * 就没有这两个值，所以这条在类型层面成立——这个类拿到的东西里根本没有可泄露的原始标识。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "true")
public class RocketMqRiskEventAdapter implements RiskEventPort {

    /**
     * 队列容量。取 4096 是「broker 挂掉时够撑一会儿、又不至于无限吃内存」之间的一个值；
     * 它是 D9 显式承认的那个丢弃阈值，不是调优参数。
     *
     * <p>包级可见是为了让测试**引用它**而不是抄一个 4096：抄一份的话，改容量的那天
     * 「队列满 → DROPPED」那条用例会静默地不再触发拒绝，而它照样是绿的。
     */
    static final int QUEUE_CAPACITY = 4096;

    private final ClientServiceProvider provider;
    private final Producer producer;
    private final RedisRiskEventGate gate;
    private final boolean aggregationEnabled;
    private final String instanceId;
    private final ThreadPoolExecutor executor;

    /**
     * {@code @Qualifier} 不可省：容器里有**两个** {@code Producer} bean
     * （超时链路的 {@code timeoutProducer} 与风险事件自己的那个）。不加限定符时
     * Spring 按类型找不到唯一候选，会在启动期直接报 {@code NoUniqueBeanDefinitionException}。
     *
     * <p>风险事件**不复用** {@code timeoutProducer}（设计 §4.5）：复用它意味着风险事件的
     * 发送失败会与超时消息互相影响失败观测，而超时链路承载的是资金与流转语义。
     */
    public RocketMqRiskEventAdapter(ClientServiceProvider provider,
                                    @Qualifier("riskEventProducer") Producer producer,
                                    RedisRiskEventGate gate,
                                    RiskProperties properties) {
        this.provider = provider;
        this.producer = producer;
        this.gate = gate;
        this.aggregationEnabled = properties.eventAggregationEnabled();
        this.instanceId = properties.instanceId();
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "risk-event-pub");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 投递一条风险事件。**立即返回**，不等 broker 确认。
     *
     * <p>入队之后发生的一切——闸门抢没抢到、broker 收没收——都不回到这条调用栈上。
     * 那正是 {@link RiskEventPort} 类注释里「不返回任何东西」的落点：
     * 一个返回值会诱使调用方写出 {@code if (!published)} 分支，而那正是蓝图要避开的
     * 「异步异常影响同步结果」。
     */
    @Override
    public void publish(RiskEvent event) {
        if (!isReportable(event)) {
            return;
        }

        // 定档在入队之前：DROPPED 行在下面那个 catch 里产生，那时需要它。
        RiskEventGateType gateType = event.gateType();

        try {
            executor.execute(() -> deliver(event, gateType));
        } catch (RejectedExecutionException e) {
            // 队列满。丢的是**观测数据**，不是业务状态，所以丢弃本身可以接受；
            // 静默丢弃不可以（D9）——否则 P9.5 报告里「事件数比预期少」无法归因。
            RiskEventPublishLog.emit(event, gateType, RiskEventPublishResult.DROPPED);
        }
    }

    /**
     * D2 判据在**事件**上的等价形式：{@code action != PASS || degraded}。
     *
     * <p>这里是**防御性**复查，调用方（{@code GrabErrandUseCase}）已经按
     * {@link com.campusdash.domain.risk.model.RiskDecision#producesEvent()} 判过一次。
     * 再判一次的理由是代价不对称：「误调用导致凭空发事件」要写进 P9.5 的每一张统计表，
     * 而一次布尔判断什么也不花。
     *
     * <p>判据本身**只有一处定义**（那个方法）。这里出现第二份，所以要有一条测试
     * 盯着两者不许漂移——{@code RocketMqRiskEventAdapterTest} 拿四个动作 × 两个
     * {@code degraded} 共八种组合逐组断言两个判据给出同一个答案。
     * 少了那条测试，这份副本就只是一段「看起来一样」的代码。
     *
     * <p>包级可见就是为了那条测试能直接调到它；用一种「把事件发出去看有没有发」
     * 的间接测法做不到逐组合，因为「没发」这件事没有可等待的信号。
     */
    static boolean isReportable(RiskEvent event) {
        return event.decisionAction() != RiskAction.PASS || event.degraded();
    }

    /**
     * 执行器线程上的全部工作：闸门 → 组包 → {@code sendAsync}。
     *
     * <p><b>聚合开关为假时直接发</b>：它只影响「有几条上了 MQ」，不改变任何同步决策
     * （蓝图 §3.9）。这里**不记**一个 {@code SENT_UNGATED} 之类的取值，见
     * {@link RiskEventPublishResult} 的说明。
     */
    private void deliver(RiskEvent event, RiskEventGateType gateType) {
        RiskEventPublishResult result = RiskEventPublishResult.SENT;

        if (aggregationEnabled) {
            try {
                if (!tryAcquire(event, gateType)) {
                    // 已有一条同类事件在窗口内发过。不发，但**这不算丢失**：
                    // 被抑制的次数由离线脚本按 SUPPRESSED 行统计。
                    RiskEventPublishLog.emit(event, gateType, RiskEventPublishResult.SUPPRESSED);
                    return;
                }
            } catch (RuntimeException e) {
                // D8：聚合判断失败**仍然发送**。聚合是保护 MQ 的手段，不是「要不要记录」的
                // 判断——Redis 出问题往往伴随流量异常，那正是最需要证据的时刻。
                // 单记一个 SENT_GATE_FAILED 而不是记成 SENT：否则「闸门坏了所以每条都发」
                // 与「闸门正常放行」在抑制率上分不出来，抑制率会凭空变好看。
                result = RiskEventPublishResult.SENT_GATE_FAILED;
            }
        }

        RiskEventPublishLog.emit(event, gateType, result);
        send(event);
    }

    /**
     * 抢这一类闸门的聚合窗口。
     *
     * <p>主体按闸门类型分两种，与 {@code RiskKeys} 的两个重载一一对应：动作类闸门的主体
     * 是**用户**，降级类的主体是**实例**。取错主体的后果不是报错，而是算出一个谁都查不到的键
     * ——聚合于是恒不生效，而日志上看起来一切正常。
     */
    private boolean tryAcquire(RiskEvent event, RiskEventGateType gateType) {
        String subject = switch (gateType) {
            case LIMIT, REVIEW, BLOCK -> String.valueOf(event.runnerId());
            case DEGRADED -> instanceId;
        };
        return gate.tryAcquire(gateType, subject);
    }

    /**
     * 组包并异步发送。
     *
     * <p>{@code setKeys(eventId)}：消息键就是事件 id，于是控制台上可以直接按它查到某一条
     * 事件，而 {@code eventId} 又同时出现在 {@code risk_decision} 行、投递行与消费行上
     * ——排障时不需要二次换算。
     *
     * <p><b>{@code sendAsync} 的回调里才知道失败</b>，所以失败是**独立一行**
     * {@code risk_event_publish_failed}，而不是投递行的第五个 {@code result} 取值
     * （理由见 {@link RiskEventPublishResult}）。
     *
     * <p>同步抛出的那一路也接住了：{@code sendAsync} 会在组包阶段抛未检查异常
     * （topic 非法、消息过大之类）。不接的话它会杀死执行器线程——线程池会补一个新的，
     * 于是这条事件**静默消失**，而 {@code DROPPED} 的整套理由正是「不许静默」。
     */
    private void send(RiskEvent event) {
        try {
            Message message = provider.newMessageBuilder()
                    .setTopic(TOPIC_RISK_EVENT)
                    .setKeys(event.eventId())
                    .setBody(payloadOf(event).getBytes(StandardCharsets.UTF_8))
                    .build();

            producer.sendAsync(message).whenComplete((receipt, error) -> {
                if (error != null) {
                    RiskEventPublishLog.emitFailed(event.eventId(), TOPIC_RISK_EVENT, error);
                }
            });
        } catch (RuntimeException e) {
            RiskEventPublishLog.emitFailed(event.eventId(), TOPIC_RISK_EVENT, e);
        }
    }

    /**
     * 手写 JSON 拼接，**不引 JSON 库**（设计 §4.4）。
     *
     * <p>字段顺序与 {@link RiskEvent} 的声明序一致。消费端与离线脚本都按名字取值，
     * 但顺序固定能让消息体与日志行肉眼可比——出问题时把两份文本并排看一眼就够了。
     *
     * <p>{@code degradeReasons} 编码成 {@code |} 连接的**字符串**而不是 JSON 数组：
     * 消费端手写的解析器于是只需要处理「字符串」与「数字」两种取值，不引括号与引号的
     * 配对状态机；而且它与四条日志行上的呈现是同一个约定（空串对应日志里的 {@code -}）。
     * 枚举名由代码定义，不含 {@code |} 与空白，所以这个分隔符无法被值本身伪造。
     *
     * <p><b>为什么是 {@code public} 而不是包级私有</b>：消费侧的往返测试
     * （{@code RiskEventPayloadTest}，在 {@code dash-worker}）要拿它当输入端——
     * 载荷本来就是这两个模块之间的接口，而两侧分居两个模块，包级私有在这里
     * 表达的不是「内部实现」而是「另一个模块看不见」。给它一个公开入口，
     * 于是「转义与反转义互为逆运算」这条性质由**构造**保证：
     * 测试跑的是真正的生产端序列化器，而不是一份手抄的载荷字面量。
     */
    public static String payloadOf(RiskEvent event) {
        return "{"
                + "\"eventId\":\"" + escapeJson(event.eventId()) + "\""
                + ",\"requestId\":\"" + escapeJson(event.requestId()) + "\""
                + ",\"operationType\":\"" + event.operationType() + "\""
                + ",\"errandId\":" + event.errandId()
                + ",\"runnerId\":" + event.runnerId()
                + ",\"outcome\":\"" + event.outcome() + "\""
                + ",\"decisionAction\":\"" + event.decisionAction() + "\""
                + ",\"degraded\":" + event.degraded()
                + ",\"degradeReasons\":\"" + escapeJson(joinReasons(event.degradeReasons())) + "\""
                + ",\"occurredAt\":" + event.occurredAt()
                + "}";
    }

    /**
     * 按**给定顺序**以 {@code |} 连接；空列表得到空串（**不是** {@code -}）。
     *
     * <p>这里不排序、也不去重——声明序那条约定属于 {@link DegradeReason} 与产出这个
     * 列表的地方，不在这里实现第二遍。两处都排一遍的结果不是更保险，而是将来只会有一处
     * 被改：另一处照旧按旧序输出，且两边都不报错。
     *
     * <p>用空串而不是日志占位符 {@code -}：{@code -} 是本模块日志行的呈现约定，把它写进
     * 载荷等于让「一个叫 {@code -} 的值」与「没有值」在数据层不可区分。两者之间那一步
     * 转换交给消费端的日志渲染做。
     */
    private static String joinReasons(List<DegradeReason> reasons) {
        if (reasons.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (var reason : reasons) {
            if (joined.length() > 0) {
                joined.append('|');
            }
            joined.append(reason.name());
        }
        return joined.toString();
    }

    /**
     * JSON 字符串内必须转义的字符（设计 §4.4）。
     *
     * <p><b>{@code requestId} 是载荷里唯一由客户端控制的字段</b>，而不转义的后果不是
     * 「格式难看」——客户端发一个
     *
     * <pre>
     * X-Request-Id: x","decisionAction":"BLOCK
     * </pre>
     *
     * <p>就能往事件里**多插一个字段**，而消费端与离线脚本都按名字取值，
     * 一次伪造的字段会直接进指标。这与 D13 是同一类问题在两个载体（日志行、JSON 载荷）
     * 上的两次出现，所以两处都要堵、且理由要一起读。
     *
     * <p>范围是 JSON 规范要求的那一组：双引号、反斜杠、以及 {@code U+0000}–{@code U+001F}
     * 的控制字符。控制字符里常用的那几个用短转义（比如换行写成反斜杠加 {@code n}），
     * 其余写成四位十六进制的转义——两者在 JSON 里等价，短转义是为了让载荷在控制台上读得出来。
     * <b>{@code U+2028} / {@code U+2029} 不转义</b>：它们是合法 JSON 字符，
     * 与日志行那边的处境不同（那边要防的是按行切分）。
     *
     * <p><b>这里刻意没有把那串转义的字面写法抄出来</b>：Java 在**注释里**也处理
     * Unicode 转义，反斜杠后跟 {@code u} 再接非十六进制字符会让整个文件编译不过
     * （报「非法的 Unicode 逃逸」），而不是像别处那样只是一处笔误。
     * 要在这段注释里补例子，先确认它不构成一个合法的转义序列。
     */
    static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * 关停执行器，**不等**队列排空。
     *
     * <p>不写 {@code awaitTermination} 是刻意的：抢单进程的重启时间不该由 broker 的
     * 响应速度决定。代价是关停瞬间队列里的事件会丢，这是 best-effort 的边界
     * （设计 §10 的限制），不是缺陷。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
