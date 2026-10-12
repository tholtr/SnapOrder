package com.campusdash.worker;

import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.model.RiskOutcome;
import com.campusdash.infrastructure.risk.RiskEventInvalidReason;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险事件载荷的解析（P9.4 设计 §6.1）。
 *
 * <p><b>不复制第六份 {@code extractLong}</b>：仓库里五个消费者各有一份私有静态
 * {@code extractLong}，实现还各自漂移了（{@code CacheEvictConsumer} 不处理负数、
 * {@code FundEventPushConsumer} 空值返回 0、{@code AutoSettleConsumer} 跳过前导空白与引号）。
 * 本阶段的载荷还要取**字符串**（{@code eventId} / {@code requestId} / 三个枚举名）与
 * **布尔值**，再抄一份只会把漂移面积扩大。那五份**一个字不动**——它们的行为已经和
 * 各自消费者的语义绑在一起，统一它们是一次独立且需要单独验证的改动（设计 §8.4）。
 *
 * <p><b>产出的是领域类型 {@link RiskEvent} 而不是一个中间 record</b>：这条载荷的十个字段
 * 与 {@code RiskEvent} 的十个字段**恰好一一对应**（消费行要打印的就是这十个），
 * 而 {@code RiskEvent} 的紧凑构造器已经写着「怎样的一份载荷是自洽的」。再插一个只在
 * 进程内流转的 record，只会让「解析成功」与「能写这一行」变成两件事——中间那段
 * 没有任何一处在读它。实现期定档（2026-09-11 拍板）。
 *
 * <p><b>解析失败一律抛 {@link IllegalArgumentException} 的子类 {@link Invalid}</b>，
 * 并带上 {@link RiskEventInvalidReason}：处理器按 D10 把「格式非法」判成
 * {@code SUCCESS}（丢弃、不重投），而这条判据在代码上就是那个异常类型。用子类携带原因，
 * 是为了让日志行上的 {@code reason=} 说得清是哪一种坏法——见
 * {@code RiskEventConsumeLogTest} 与 {@link RiskEventInvalidReason}。
 *
 * <p><b>手写解析、不引 JSON 库</b>（设计 §4.4 的同一个取舍）：生产端是手拼的，
 * 消费端手解；载荷的形状是**一个扁平的、值只有字符串/整数/布尔三种的对象**，
 * 没有嵌套、没有数组、没有 null。下面这个读者只实现这个子集，遇到子集之外的东西
 * 一律判 {@code MALFORMED_JSON}——而不是「尽力解析」。这一点是刻意的：一个宽容的解析器
 * 会把生产端的数据结构变化**吸收掉**，于是在 P9.5 的统计里表现为「字段偶尔对不上」，
 * 而不是一条能定位的 {@code risk_event_invalid}。
 *
 * <p><b>键名重复时取最后一个</b>：不认为是错误。生产端不会写出重复键，而为一个
 * 不可能出现的输入增加一种失败模式，会让「非法」的含义变模糊。
 *
 * <p><b>未知的多余字段被忽略</b>：将来生产端加字段时，旧 worker 仍然能消费——
 * 这与 {@code UNKNOWN_ENUM}（已知字段装着一个不认识的值）是两回事。
 */
public final class RiskEventPayload {

    private RiskEventPayload() {
    }

    /**
     * 把消息体解析成一条事件。
     *
     * @throws Invalid 载荷缺失、形态不对、结构损坏或不符合 {@link RiskEvent} 的不变式
     */
    public static RiskEvent parse(String json) {
        if (json == null) {
            throw new Invalid(RiskEventInvalidReason.MISSING_FIELD, "消息体为 null");
        }

        Map<String, Object> fields = new Reader(json).readObject();

        String eventId = text(fields, "eventId");
        String requestId = text(fields, "requestId");
        RiskOperationType operationType = enumOf(RiskOperationType.class, fields, "operationType");
        long errandId = integer(fields, "errandId");
        long runnerId = integer(fields, "runnerId");
        RiskOutcome outcome = enumOf(RiskOutcome.class, fields, "outcome");
        RiskAction decisionAction = enumOf(RiskAction.class, fields, "decisionAction");
        boolean degraded = flag(fields, "degraded");
        List<DegradeReason> degradeReasons = reasons(fields);
        long occurredAt = integer(fields, "occurredAt");

        try {
            return new RiskEvent(eventId, requestId, operationType, errandId, runnerId,
                    outcome, decisionAction, degraded, degradeReasons, occurredAt);
        } catch (IllegalArgumentException e) {
            // 载荷的形态对了，但内容自相矛盾（空白 id、degraded 与原因不一致）。
            // 包一层是为了给它一个明确的原因值：处理器只知道「这是个 IAE」，
            // 分不出「解析器判的」与「领域模型判的」——而那正是排查时想知道的第一件事。
            throw new Invalid(RiskEventInvalidReason.INCONSISTENT_EVENT, e.getMessage());
        }
    }

    private static Object field(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            throw new Invalid(RiskEventInvalidReason.MISSING_FIELD, "字段 " + name + " 缺失");
        }
        return value;
    }

    private static String text(Map<String, Object> fields, String name) {
        Object value = field(fields, name);
        if (!(value instanceof String text)) {
            throw new Invalid(RiskEventInvalidReason.MALFORMED_JSON,
                    "字段 " + name + " 不是字符串");
        }
        return text;
    }

    private static long integer(Map<String, Object> fields, String name) {
        Object value = field(fields, name);
        if (!(value instanceof Long number)) {
            throw new Invalid(RiskEventInvalidReason.BAD_NUMBER, "字段 " + name + " 不是整数");
        }
        return number;
    }

    private static boolean flag(Map<String, Object> fields, String name) {
        Object value = field(fields, name);
        if (!(value instanceof Boolean flag)) {
            throw new Invalid(RiskEventInvalidReason.MALFORMED_JSON,
                    "字段 " + name + " 不是布尔值");
        }
        return flag;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, Map<String, Object> fields,
                                                String name) {
        String raw = text(fields, name);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new Invalid(RiskEventInvalidReason.UNKNOWN_ENUM,
                    "字段 " + name + " 的值 " + raw + " 不认识");
        }
    }

    /**
     * 降级原因：{@code |} 连接的枚举名，**空串表示没有原因**。
     *
     * <p>空串而不是日志里的占位符 {@code -}：那是日志行的呈现约定，把它写进载荷等于让
     * 「一个叫 {@code -} 的原因」与「没有原因」在数据层不可区分。两者之间那一步转换
     * 由消费侧的日志渲染做（{@code RiskEventConsumeLog}）。
     *
     * <p>顺序**照抄**给到的顺序，不排序、不去重：声明序那条约定属于产出这个列表的地方。
     */
    private static List<DegradeReason> reasons(Map<String, Object> fields) {
        String raw = text(fields, "degradeReasons");
        if (raw.isEmpty()) {
            return List.of();
        }
        List<DegradeReason> reasons = new ArrayList<>();
        for (String name : raw.split("\\|", -1)) {
            try {
                reasons.add(DegradeReason.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new Invalid(RiskEventInvalidReason.UNKNOWN_ENUM,
                        "降级原因 " + name + " 不认识");
            }
        }
        return reasons;
    }

    /**
     * 载荷形态不合法。**它同时是「这条消息该不该重投」的判据**：处理器捕获
     * {@code IllegalArgumentException} 就返回 {@code SUCCESS}（D10）——
     * 重投一条字段缺失的消息，三次之后它仍然是同一条消息。
     */
    static final class Invalid extends IllegalArgumentException {

        private final RiskEventInvalidReason reason;

        Invalid(RiskEventInvalidReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        RiskEventInvalidReason reason() {
            return reason;
        }
    }

    /**
     * 扁平 JSON 对象的最小读者。
     *
     * <p>只认四种记法：字符串（带转义）、整数、{@code true}/{@code false}、以及
     * 对象与逗号。别的一律 {@code MALFORMED_JSON}——包括 {@code null}、数组、嵌套对象。
     * 这不是「还没实现」，是刻意划的下界：见类注释「一个宽容的解析器会把生产端的
     * 数据结构变化吸收掉」。
     *
     * <p>字段与值之间允许空白（生产端不写，但手灌消息排障时会带上），
     * 字符串内部不做空白处理。
     */
    private static final class Reader {

        private final String json;
        private int at;

        Reader(String json) {
            this.json = json;
        }

        Map<String, Object> readObject() {
            Map<String, Object> fields = new LinkedHashMap<>();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (peek() != '}') {
                while (true) {
                    skipWhitespace();
                    String key = readString();
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    fields.put(key, readValue());
                    skipWhitespace();
                    char separator = peek();
                    if (separator == ',') {
                        at++;
                        continue;
                    }
                    if (separator != '}') {
                        throw malformed("字段 " + key + " 之后应当是 , 或 }");
                    }
                    break;
                }
            }
            at++;

            skipWhitespace();
            if (at != json.length()) {
                // 尾巴上有东西。最可能的是「两条消息拼在一起」，那说明上游的发送侧出了问题，
                // 而不是这一条载荷的内容有问题——所以必须判非法，不能取第一个对象了事。
                throw malformed("对象之后还有 " + (json.length() - at) + " 个字符");
            }
            return fields;
        }

        private Object readValue() {
            char c = peek();
            if (c == '"') {
                return readString();
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return readNumber();
            }
            if (json.startsWith("true", at)) {
                at += 4;
                return Boolean.TRUE;
            }
            if (json.startsWith("false", at)) {
                at += 5;
                return Boolean.FALSE;
            }
            throw malformed("预期外的值记法");
        }

        private String readString() {
            expect('"');
            StringBuilder text = new StringBuilder();
            while (true) {
                if (at >= json.length()) {
                    throw malformed("字符串没有结束的引号");
                }
                char c = json.charAt(at++);
                if (c == '"') {
                    return text.toString();
                }
                if (c != '\\') {
                    text.append(c);
                    continue;
                }
                if (at >= json.length()) {
                    throw malformed("转义符之后没有字符");
                }
                char escaped = json.charAt(at++);
                switch (escaped) {
                    case '"' -> text.append('"');
                    case '\\' -> text.append('\\');
                    case '/' -> text.append('/');
                    case 'b' -> text.append('\b');
                    case 'f' -> text.append('\f');
                    case 'n' -> text.append('\n');
                    case 'r' -> text.append('\r');
                    case 't' -> text.append('\t');
                    case 'u' -> text.append(readUnicodeEscape());
                    default -> throw malformed("不认识的转义");
                }
            }
        }

        /**
         * 四位十六进制转义。
         *
         * <p>与 {@code RocketMqRiskEventAdapter.escapeJson} 的短转义表**配对**：
         * 那边把常用控制字符写成短转义、其余写成四位形式，这边两种都收。
         * 少收一种的表现是「生产端写得出来、消费端读不回去」——而它只会表现为
         * 一条 {@code risk_event_invalid}，不会指出缺的是哪个分支。
         */
        private char readUnicodeEscape() {
            if (at + 4 > json.length()) {
                throw malformed("四位十六进制转义不足四位");
            }
            String hex = json.substring(at, at + 4);
            try {
                char value = (char) Integer.parseInt(hex, 16);
                at += 4;
                return value;
            } catch (NumberFormatException e) {
                throw malformed("四位十六进制转义不是十六进制");
            }
        }

        private long readNumber() {
            int start = at;
            if (peek() == '-') {
                at++;
            }
            while (at < json.length() && json.charAt(at) >= '0' && json.charAt(at) <= '9') {
                at++;
            }
            String raw = json.substring(start, at);
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                // 走到这里的是「-」或超出 long 的位数。两者都是**这个值**的问题，
                // 不是骨架的问题，所以原因给 BAD_NUMBER 而不是 MALFORMED_JSON。
                throw new Invalid(RiskEventInvalidReason.BAD_NUMBER, "数字 " + raw + " 解析不了");
            }
        }

        private void expect(char expected) {
            if (peek() != expected) {
                throw malformed("应当是 " + expected);
            }
            at++;
        }

        private char peek() {
            if (at >= json.length()) {
                throw malformed("内容提前结束");
            }
            return json.charAt(at);
        }

        private void skipWhitespace() {
            while (at < json.length()) {
                char c = json.charAt(at);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                at++;
            }
        }

        private Invalid malformed(String detail) {
            return new Invalid(RiskEventInvalidReason.MALFORMED_JSON, detail + "（位置 " + at + "）");
        }
    }
}
