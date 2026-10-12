package com.campusdash.bench;

import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.model.RiskOutcome;
import com.campusdash.domain.risk.ports.RiskEventPort;
import com.campusdash.infrastructure.risk.RocketMqRiskEventAdapter;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 风险事件重复投递工具（P9.5 设计 §5.6、决策 D5）。
 *
 * <p>把**同一条**风险事件向 {@code errand-risk-event} 发两次，body 逐字节相同。要验的是消费端
 * 的重复口径：两行 {@code risk_event_processed}（{@code eventId} 相同、{@code deliveryAttempt}
 * 都是 1），以及离线按 {@code requestId + operationType} 去重后
 * {@code risk_event_duplicate_total = 1} 而**业务表零写入**（P9.4 D12：消费端不落状态）。
 *
 * <h2>为什么不给生产代码加开关</h2>
 *
 * <p>「让去重生效」最直接的做法是在生产代码里加一个「重复投递」的测试开关，或者把
 * 投递拆成「发两次」的半成品。D5 否掉了这条路：那会让 P9.5 的所有数字不再是**发布物**
 * 的数字。灌消息从外部做，生产代码一行不动。
 *
 * <h2>为什么载荷必须来自生产端的序列化器</h2>
 *
 * <p>{@link #Sample#payload()} 走 {@link RocketMqRiskEventAdapter#payloadOf}——工具不自己拼
 * JSON。手拼一份「看起来一样」的载荷是这类工具最常见的坏法：它今天能过，是因为字段刚好没变；
 * 等生产端加一个字段或改一处转义，工具照旧送出旧的形态，而**消费端与离线脚本都按名字取值**，
 * 于是「重复投递的判据」验的是另一条链路，报告里却写着验过了。这与 F-26 是同一条规矩。
 *
 * <p>顺带因此白得一条安全性质：{@code requestId} 是载荷里唯一的外部输入，生产端的转义
 * （D13）在这里自动生效——用 {@code --request-id} 灌一个带引号的值，工具送出的仍是合法 JSON。
 *
 * <h2>两次发送的形态</h2>
 *
 * <p>同一个 {@code byte[]} body、{@link #SEND_COUNT} 次**独立**的同步 {@code send}，各自拿到
 * 一个 msgId。三点值得说清：
 *
 * <ul>
 *   <li><b>同一个字节数组</b>而不是「同一份参数再拼一次」：这是「逐字节相同」唯一不依赖
 *       实现细节的写法；重新拼一次的话，payload 里任何一处取当前时间的字段（{@code occurredAt}）
 *       都会让两次的 body 不同，而判据仍然全绿——那是最难发现的一类失效。</li>
 *   <li><b>同步 {@code send}</b> 而不是生产端用的 {@code sendAsync}：工具必须在退出之前知道
 *       两条都发出去了，而且失败要**当场**变成非零退出码。生产端用异步是「不许阻塞抢单」
 *       的要求，这里没有那个约束。</li>
 *   <li><b>不加 tag</b>：生产端也不加，而消费端订阅的是 {@code FilterExpression("*", TAG)}，
 *       所以加不加都会被收到——这里与生产端保持一致，免得工具成了「能收到」的那一半证据。</li>
 * </ul>
 *
 * <h2>它不做什么</h2>
 *
 * <p>不消费、不校验、不查库、不等消费行：判据在 worker 的日志与离线脚本里（§5.6 的两条），
 * 工具只负责把消息灌进去，并**把它灌了什么逐字打印出来**——报告要能照着复现。
 * 样例的 {@code errandId} / {@code runnerId} 因此在库里**不需要存在**（消费端不落状态）。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... RiskEventDuplicateTool                       # 默认样例，发两次
 *   java -cp ... RiskEventDuplicateTool --action BLOCK         # 换一档动作
 *   java -cp ... RiskEventDuplicateTool --endpoint 10.0.0.5:8081
 * </pre>
 */
public final class RiskEventDuplicateTool {

    /** 与主配置 {@code dash.mq} 的默认端点一致（RocketMQ 5.x 的 gRPC proxy）。 */
    static final String DEFAULT_ENDPOINT = "127.0.0.1:8081";

    /**
     * 固定发两次。设计 §5.6 与工具名说的都是「两次」，所以这里不做成参数：
     * 一个能发 N 次的工具，报告里那句「重复投递」就得多带一个 N，而 N 只有 2 有意义
     * （再多次也不增加任何判据）。
     */
    static final int SEND_COUNT = 2;

    private static final String FLAG_EVENT_ID = "--event-id";
    private static final String FLAG_REQUEST_ID = "--request-id";
    private static final String FLAG_OPERATION_TYPE = "--operation-type";
    private static final String FLAG_ERRAND_ID = "--errand-id";
    private static final String FLAG_RUNNER_ID = "--runner-id";
    private static final String FLAG_ACTION = "--action";
    private static final String FLAG_ENDPOINT = "--endpoint";

    /**
     * 默认样例的两个业务 id。取得离真实数据远，是因为它们**不需要在库里存在**，
     * 而在报告里一眼要能看出「这条是工具灌的」，不必回去查库确认。
     */
    private static final long DEFAULT_ERRAND_ID = 995_001L;
    private static final long DEFAULT_RUNNER_ID = 995_001L;

    /** 默认动作取 {@code LIMIT}：越线是攻击流量的常态，也是最常被重复投递的那一类事件。 */
    private static final RiskAction DEFAULT_ACTION = RiskAction.LIMIT;

    /**
     * 退出码沿用 {@link BlacklistKeyTool} 的约定：2 = 用法错，3 = 运行期失败。
     *
     * <p>3 的含义要说得准一点：它表示「两条消息没有都拿到 broker 的回执」，**不表示**
     * 消息一定没出去（失败可能出在发送上，也可能出在关客户端时）。它更不表示消费侧怎么样
     * ——那两条判据在 worker 日志与离线脚本里，本工具看不到。所以失败时把载荷也打出来，
     * 让运维能按 {@code eventId} 去日志里核对究竟走没走到。
     */
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAILED = 3;

    private RiskEventDuplicateTool() {
    }

    public static void main(String[] args) {
        Sample sample;
        try {
            sample = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(EXIT_USAGE);
            return;
        }

        List<String> msgIds;
        try {
            msgIds = sendTwice(sample);
        } catch (ClientException | IOException | RuntimeException e) {
            // broker 没起、端点不通、topic 不合法都会走到这里。**不要吞掉异常**再打印一份
            // 看起来很正常的报告——那是本模块最怕的「静默」：运维会以为消息灌进去了。
            System.err.println("发送失败（端点 " + sample.endpoint() + "）：" + e);
            // 失败路径上仍然把载荷打出来：运维要能拿 eventId 去 worker 日志里核对
            // 「到底是没发出去，还是发出去了但回执没拿到」——这两件事的处置完全不同。
            System.err.println("本次本想发的载荷（**不保证**已发出，请按 eventId 到 worker 日志核对）：");
            System.err.println("  " + sample.payload());
            System.exit(EXIT_FAILED);
            return;
        }

        System.out.print(buildReport(sample, msgIds));
        warnIfMsgIdsRepeat(msgIds);
    }

    /**
     * 一次运行的完整输入：一条事件 + 端点。
     *
     * <p>{@code event} 在解析期就**定死**（连 {@code occurredAt} 一起），所以
     * {@link #payload()} 是个纯函数——两次调用给出同一个字符串。这一条是本工具的前提，
     * 不是实现细节：见 {@link #payload()}。
     */
    record Sample(RiskEvent event, String endpoint) {

        /**
         * 与生产端**同一个**序列化器。工具不自己拼 JSON，理由见类注释（F-26 那条规矩）。
         *
         * <p>每次调用都重新序列化而不是缓存成一个字段：这条路径上没有 I/O，而「两次发送
         * 用的是同一份 body」这件事由 {@link #sendTwice} 里那一个 {@code byte[]} 保证，
         * 不由这里保证。缓存反而会让人以为「不缓存就会变」。
         */
        String payload() {
            return RocketMqRiskEventAdapter.payloadOf(event);
        }

        /** topic 取自端口常量而不是字面量：改了 topic 而没改这里的话，消息会灌进一个没人订阅的地方。 */
        String topic() {
            return RiskEventPort.TOPIC_RISK_EVENT;
        }
    }

    /**
     * 解析命令行。抽成纯函数是为了让「哪个参数被认、哪个被拒」能被单测钉住——
     * {@link #main} 里只剩发送与输出。
     *
     * <p><b>不认识的参数一律报错，绝不忽略。</b>静默忽略是本工具最危险的失效方式：
     * 敲错一个字母（{@code --actoin}）会得到一条用默认值造的 {@code LIMIT} 事件，
     * 而报告里写着「灌了一条 {@code BLOCK}」——消息确实发出去了、消费行也真的出现了、
     * 判据全绿，只有被验的那件事不对。
     *
     * <p>写法支持 {@code --名字 值} 与 {@code --名字=值} 两种（手敲时两种都自然），
     * 但不接受位置参数：本工具的参数全是可省的，位置参数会让「少给一个值」变成
     * 「后面那个值被前一个参数吃掉」。
     */
    static Sample parse(String[] args) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException(
                        "不认识的位置参数「" + arg + "」，本工具只接受 --名字 值 形式的参数。\n\n" + usage());
            }
            String name;
            String value;
            int equals = arg.indexOf('=');
            if (equals >= 0) {
                name = arg.substring(0, equals);
                value = arg.substring(equals + 1);
            } else {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(
                            "参数「" + arg + "」后面缺少值。\n\n" + usage());
                }
                name = arg;
                value = args[++i];
                if (value.startsWith("--")) {
                    // 少了这一条，`--action --endpoint 10.0.0.5:8081` 会把 --endpoint 当成动作名，
                    // 报出来的错指向「动作不合法」，而真正的问题是 --action 少给了值。
                    throw new IllegalArgumentException(
                            "参数「" + name + "」的值看起来是另一个参数（" + value + "），请补上它的值。\n\n"
                                    + usage());
                }
            }
            if (!KNOWN_FLAGS.contains(name)) {
                throw new IllegalArgumentException(
                        "不认识的参数「" + name + "」。参数写错时本工具**不会**用默认值继续跑，"
                                + "否则报告会写着灌了一种事件、实际灌的是另一种。\n\n" + usage());
            }
            if (raw.put(name, value) != null) {
                throw new IllegalArgumentException("参数「" + name + "」给了两次，请只给一次。\n\n" + usage());
            }
        }

        long now = System.currentTimeMillis();
        RiskAction action = raw.containsKey(FLAG_ACTION)
                ? parseAction(raw.get(FLAG_ACTION))
                : DEFAULT_ACTION;

        RiskEvent event = new RiskEvent(
                raw.getOrDefault(FLAG_EVENT_ID, "dup-evt-" + now),
                raw.getOrDefault(FLAG_REQUEST_ID, "dup-req-" + now),
                parseOperationType(raw.getOrDefault(FLAG_OPERATION_TYPE, RiskOperationType.GRAB.name())),
                parseLong(FLAG_ERRAND_ID, raw.getOrDefault(FLAG_ERRAND_ID, String.valueOf(DEFAULT_ERRAND_ID))),
                parseLong(FLAG_RUNNER_ID, raw.getOrDefault(FLAG_RUNNER_ID, String.valueOf(DEFAULT_RUNNER_ID))),
                outcomeOf(action),
                action,
                // 降级恒为 false、原因恒为空：工具只造「一条正常的风险事件」这一种形态。
                // 造降级事件会顺带改掉闸门定档（action != PASS 优先，但 PASS+degraded 落到
                // DEGRADED），于是「重复投递」这条判据里就混进了另一个变量。
                false,
                List.of(),
                now);

        return new Sample(event, raw.getOrDefault(FLAG_ENDPOINT, DEFAULT_ENDPOINT));
    }

    /**
     * 同一个 {@code byte[]}，两次独立的 send。
     *
     * <p>返回两次的 msgId：它们是「两条独立消息」的辅助证据（相同就说明 broker 侧不是两条，
     * 见 {@link #warnIfNotTwoIndependentMessages}）。判据本身不靠它——那在消费行的
     * {@code deliveryAttempt} 上。
     */
    private static List<String> sendTwice(Sample sample) throws ClientException, IOException {
        byte[] body = sample.payload().getBytes(StandardCharsets.UTF_8);

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration config = ClientConfiguration.newBuilder()
                .setEndpoints(sample.endpoint())
                .setRequestTimeout(Duration.ofSeconds(10))
                .build();

        List<String> msgIds = new ArrayList<>(SEND_COUNT);
        try (Producer producer = provider.newProducerBuilder()
                .setClientConfiguration(config)
                .setTopics(sample.topic())
                .build()) {
            for (int i = 0; i < SEND_COUNT; i++) {
                // Message 每次新建、body 复用同一个数组：builder 里除了 body 之外都是
                // 与生产端对齐的字段（topic、keys、无 tag）。
                Message message = provider.newMessageBuilder()
                        .setTopic(sample.topic())
                        .setKeys(sample.event().eventId())
                        .setBody(body)
                        .build();
                // getMessageId() 是客户端的 MessageId 类型，String.valueOf 而不是 toString：
                // 报告里这一格要么是 id、要么是一句说得清的占位，不会是 "null"。
                msgIds.add(String.valueOf(producer.send(message).getMessageId()));
            }
        }
        return msgIds;
    }

    /**
     * msgId 有重复是**可疑**而不是致命：真正能证明「两条独立投递」的是两行
     * {@code deliveryAttempt=1} 的消费行，msgId 只是旁证。所以这里只告警，不改退出码
     * ——但必须说出来，否则报告里那句「两条独立消息」就没有依据。
     *
     * <p>按「有没有重复」判而不是比较前两条：{@link #SEND_COUNT} 是个常量，
     * 将来若被改成别的值，比较前两条的写法会**安静地**只看前两条，
     * 于是「三条里有两条第 1、3 条相同」这种情况没人报。
     */
    private static void warnIfMsgIdsRepeat(List<String> msgIds) {
        if (Set.copyOf(msgIds).size() == msgIds.size()) {
            return;
        }
        System.err.println("[WARN] 有两次 send 拿到了相同的 msgId（" + msgIds
                + "）——broker 侧可能没把它们当两条消息。报告里不要写「两条独立消息」，"
                + "先看消费行是不是真的有两行。");
    }

    /**
     * 报告。与 {@link BlacklistKeyTool#buildReport} 同一条规矩：**打印它实际做了什么**，
     * 而不是它打算做什么。载荷逐字给出（报告要能照抄），端点也照实打——写死默认端点的话，
     * 一份照着复制的命令在生产上会灌进本机 broker，不报错，只是消息没了。
     */
    static String buildReport(Sample sample, List<String> msgIds) {
        StringBuilder out = new StringBuilder();
        String payload = sample.payload();

        out.append("端点     : ").append(sample.endpoint()).append('\n');
        out.append("topic    : ").append(sample.topic()).append('\n');
        out.append("载荷     : ").append(payload).append('\n');
        out.append("body 字节数 : ").append(payload.getBytes(StandardCharsets.UTF_8).length)
                .append("（两次发送用的是同一个 byte[]）\n");
        out.append('\n');

        out.append("发送结果（独立的 send，各一个 msgId）：\n");
        for (int i = 0; i < msgIds.size(); i++) {
            out.append("  第 ").append(i + 1).append(" 次 : msgId=").append(msgIds.get(i)).append('\n');
        }
        out.append('\n');

        out.append("后续核验（本工具只负责发，两条判据在别处，见 P9.5 设计 §5.6）：\n");
        out.append("  1) worker 日志里应出现**两行** risk_event_processed，eventId 相同、deliveryAttempt 都是 1：\n");
        out.append("       grep risk_event_processed <worker日志> | grep 'eventId=")
                .append(sample.event().eventId()).append("'\n");
        out.append("     只出现一行的话，说明 broker 侧只收下了一条，判据不成立。\n");
        out.append("  2) 离线脚本按 requestId + operationType 去重后 risk_event_duplicate_total 应为 1，\n");
        out.append("     且业务表零写入（消费端不落状态，P9.4 D12）——两次运行之间比 local_message / credit_* 的计数：\n");
        out.append("       python3 bench/scripts/analyze_risk_events.py <应用日志> <worker日志>\n");
        out.append("     本次请求：requestId=").append(sample.event().requestId())
                .append(" operationType=").append(sample.event().operationType())
                .append(" action=").append(sample.event().decisionAction())
                .append(" occurredAt=").append(sample.event().occurredAt());
        return out + System.lineSeparator();
    }

    /** 全部合法参数名。写成常量而不是散在 {@code if} 里，是为了让「不认识就报错」这条只有一份判断。 */
    private static final Set<String> KNOWN_FLAGS = Set.of(
            FLAG_EVENT_ID, FLAG_REQUEST_ID, FLAG_OPERATION_TYPE,
            FLAG_ERRAND_ID, FLAG_RUNNER_ID, FLAG_ACTION, FLAG_ENDPOINT);

    private static String usage() {
        return """
                用法：RiskEventDuplicateTool [--参数 值 ...]

                把**同一条**风险事件向 errand-risk-event 发两次：body 逐字节相同、eventId 相同、
                两次是独立的 send。用来验消费端的重复投递口径。

                参数（都可省，省了就用默认样例）：
                  %s <字符串>    默认 dup-evt-<毫秒时间戳>
                  %s <字符串>  默认 dup-req-<毫秒时间戳>
                  %s <枚举> %s（本轮唯一取值）
                  %s <整数>    默认 %d
                  %s <整数>    默认 %d
                  %s <枚举>     %s，默认 %s
                  %s <host:port> RocketMQ gRPC proxy，默认 %s

                outcome 由 --action 推出（PASS → PASSED，其余 → DENIED），degraded 恒为 false：
                工具只造「一条正常的、不降级的风险事件」这一种形态。

                样例的 errandId / runnerId 在库里不需要存在——消费端不落状态（P9.4 D12）。
                工具不消费、不校验、不查库，只把消息灌进去并打印它灌了什么。""".formatted(
                FLAG_EVENT_ID, FLAG_REQUEST_ID,
                FLAG_OPERATION_TYPE, names(RiskOperationType.values()),
                FLAG_ERRAND_ID, DEFAULT_ERRAND_ID,
                FLAG_RUNNER_ID, DEFAULT_RUNNER_ID,
                FLAG_ACTION, names(RiskAction.values()), DEFAULT_ACTION,
                FLAG_ENDPOINT, DEFAULT_ENDPOINT);
    }

    private static RiskAction parseAction(String raw) {
        for (RiskAction action : RiskAction.values()) {
            if (action.name().equalsIgnoreCase(raw.trim())) {
                return action;
            }
        }
        throw new IllegalArgumentException(
                "参数「" + FLAG_ACTION + "」只能是 " + names(RiskAction.values()) + "，收到：" + raw);
    }

    private static RiskOperationType parseOperationType(String raw) {
        for (RiskOperationType type : RiskOperationType.values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "参数「" + FLAG_OPERATION_TYPE + "」只能是 " + names(RiskOperationType.values())
                        + "，收到：" + raw);
    }

    /**
     * {@code outcome} 由 {@code action} 推出，与 {@code RiskPreCheckResult#toEvent} 同一条规则。
     *
     * <p>不另设一个 {@code --outcome}：那样两次投递之间可能出现
     * {@code decisionAction=BLOCK outcome=PASSED} 这种自相矛盾的载荷，而它照样被消费、
     * 照样进指标（那个方法上记着同一件事）。
     */
    private static RiskOutcome outcomeOf(RiskAction action) {
        return action == RiskAction.PASS ? RiskOutcome.PASSED : RiskOutcome.DENIED;
    }

    private static long parseLong(String flag, String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数「" + flag + "」必须是整数，收到：" + raw);
        }
    }

    /** 可选值列表由枚举现算，不写在文案里：多一个枚举值时提示不会悄悄过期。 */
    private static String names(Enum<?>[] values) {
        StringBuilder joined = new StringBuilder();
        for (Enum<?> value : values) {
            if (joined.length() > 0) {
                joined.append(" / ");
            }
            joined.append(value.name().toUpperCase(Locale.ROOT));
        }
        return joined.toString();
    }
}
