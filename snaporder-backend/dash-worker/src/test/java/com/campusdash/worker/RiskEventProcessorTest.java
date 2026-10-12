package com.campusdash.worker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.model.RiskOutcome;
import com.campusdash.infrastructure.risk.RiskEventConsumeLog;
import com.campusdash.infrastructure.risk.RiskEventInvalidReason;
import com.campusdash.infrastructure.risk.RocketMqRiskEventAdapter;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 处理器的四条路径（P9.4 设计 §6.2、§8.1）。
 *
 * <p>这个类不连任何中间件：四条路径里最有价值的两条——**格式非法**与**处理失败**——
 * 恰恰在生产里最难触发，而它们决定了「broker 要不要重投」。四条一起跑在单测里，
 * 是「把处理器从消费者里拆出来」这个决定的全部收益。
 *
 * <p>断言分两层：日志**整行文本**（与 {@code RiskEventConsumeLogTest} 的格式锁同一个口径，
 * 因为脚本按空格切分这一行），以及**返回值**（它是唯一能影响 broker 行为的东西）。
 *
 * <p>载荷用生产端真正的序列化器 {@link RocketMqRiskEventAdapter#payloadOf} 造：
 * 与 {@code RiskEventPayloadTest} 同一条理由——手抄的字面量会随生产端改动而腐烂，
 * 而它腐烂时这个类照样是绿的。
 */
@DisplayName("风险事件处理器")
class RiskEventProcessorTest {

    private static final long OCCURRED_AT = 1_757_600_000_000L;

    /** 真实时钟与事件时刻差 42ms，于是 {@code lagMs=42} 是可断言的确定值。 */
    private static final long NOW = OCCURRED_AT + 42;

    private final ListAppender<ILoggingEvent> consumeLines = new ListAppender<>();
    private final ListAppender<ILoggingEvent> processorLines = new ListAppender<>();

    /**
     * {@code ListAppender.list} 默认是普通 {@code ArrayList}，这里换成并发容器——
     * 与 {@code RocketMqRiskEventAdapterTest} 同一个理由（那边是执行器线程，
     * 这边今天是单线程，但「加一条断言时不必先想清楚线程」这件事值得那一个 import）。
     */
    @BeforeEach
    void captureLines() {
        assertTrue(consumeLogLogger().isInfoEnabled() && consumeLogLogger().isWarnEnabled(),
                "前提不成立：RiskEventConsumeLog 的 INFO/WARN 被关掉了（多半是加了 logback 配置文件），"
                        + "这个类会安静地捕获到空列表，然后每条断言都以「行数不对」的形式失败。");
        consumeLines.list = new CopyOnWriteArrayList<>();
        processorLines.list = new CopyOnWriteArrayList<>();
        consumeLines.start();
        processorLines.start();
        consumeLogLogger().addAppender(consumeLines);
        processorLogger().addAppender(processorLines);
    }

    @AfterEach
    void detachAppenders() {
        consumeLogLogger().detachAppender(consumeLines);
        processorLogger().detachAppender(processorLines);
        consumeLines.stop();
        processorLines.stop();
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static RiskEvent event(RiskAction action) {
        return new RiskEvent("evt-1", "req-abc", RiskOperationType.GRAB, 3003L, 2001L,
                RiskOutcome.DENIED, action, false, List.of(), OCCURRED_AT);
    }

    private static String body(RiskAction action) {
        return RocketMqRiskEventAdapter.payloadOf(event(action));
    }

    private static RiskEventProcessor processorAt(long now) {
        return new RiskEventProcessor(() -> now);
    }

    private List<String> consumedTexts() {
        return consumeLines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> processorTexts() {
        return processorLines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("正常载荷：恰好一条消费行，逐字与格式锁一致")
    void normalPayloadWritesOneProcessedLine() {
        ConsumeResult result = processorAt(NOW).process(body(RiskAction.LIMIT), 1);

        assertEquals(ConsumeResult.SUCCESS, result);
        // 整行比对而不是 contains：lagMs 由假时钟算出（真时钟会是十几位），
        // 于是「处理器读的是注入的时钟」这件事被这条断言顺带钉住。
        assertEquals(List.of(RiskEventConsumeLog.format(event(RiskAction.LIMIT), 42L, 1)),
                consumedTexts());
    }

    @Test
    @DisplayName("REVIEW：在消费行之外**额外**写一条待审行，两条都在")
    void reviewWritesAnExtraPendingLine() {
        // 「额外」两个字是这条用例的全部内容：待审行不是消费行的替代，两条都要在，
        // 且顺序是消费行在前——手工审核的入口按 eventId 从前者取其余字段，
        // 读日志的人也是先看到完整的一行、再看到「有人该去看一眼」。
        ConsumeResult result = processorAt(NOW).process(body(RiskAction.REVIEW), 1);

        assertEquals(ConsumeResult.SUCCESS, result);
        assertEquals(List.of(
                        RiskEventConsumeLog.format(event(RiskAction.REVIEW), 42L, 1),
                        RiskEventConsumeLog.formatReviewPending(event(RiskAction.REVIEW))),
                consumedTexts());
    }

    @Test
    @DisplayName("同一条 body 喂两次：两行日志，内容相同——重投递只产生重复日志")
    void theSameBodyTwiceWritesTwoIdenticalLines() {
        // 蓝图 §5.3 的验收在代码上的形态。这里能断言「零次存储调用」的唯一方式是
        // 「这个类没有存储可调」——那是下面的 carriesNoStorageDependency，
        // 这条用例断言的是它的可见后果：两次处理之间没有任何状态被带走。
        RiskEventProcessor processor = processorAt(NOW);

        assertEquals(ConsumeResult.SUCCESS, processor.process(body(RiskAction.LIMIT), 1));
        assertEquals(ConsumeResult.SUCCESS, processor.process(body(RiskAction.LIMIT), 1));

        List<String> texts = consumedTexts();
        assertEquals(2, texts.size(), texts.toString());
        assertEquals(texts.get(0), texts.get(1), "重投递的第二次与第一次在日志上应当完全一样");
        assertEquals(processorTexts(), List.of(), "正常路径不该产生第二条日志");
    }

    @Test
    @DisplayName("投递次数原样进日志——第 2 次必须与第 1 次分得开")
    void deliveryAttemptReachesTheLine() {
        processorAt(NOW).process(body(RiskAction.LIMIT), 2);

        assertTrue(consumedTexts().get(0).endsWith(" deliveryAttempt=2"), consumedTexts().get(0));
    }

    // ------------------------------------------------------------------
    // 格式非法（D10）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("格式非法：记一行 invalid、返回 SUCCESS（丢弃，不重投）")
    void malformedPayloadIsAckedWithAnInvalidLine() {
        // 引号不配。
        String broken = body(RiskAction.LIMIT).replace("\"req-abc\"", "\"req-abc");

        ConsumeResult result = processorAt(NOW).process(broken, 1);

        // 返回 SUCCESS 是这条路径里唯一影响 broker 的东西：FAILURE 会让它重投三次，
        // 而一条引号不配的消息重投三次之后仍然是同一条消息（D10）。
        assertEquals(ConsumeResult.SUCCESS, result);
        // error=Invalid 而不是 error=IllegalArgumentException：解析器抛的是
        // IllegalArgumentException 的**子类** RiskEventPayload.Invalid，而这一行取的是
        // 实际异常的类名。也就是说生产上跑出来的这一行，`error=` 是 `Invalid`——
        // 它不含任何原因信息（四种坏法全是它），**归因完全落在 reason= 上**。
        // RiskEventConsumeLogTest 里那条 error=IllegalArgumentException 的用例演示的是
        // 「随便一个 IAE 进来会渲染成什么」，不是生产上的取值。
        assertEquals(List.of("risk_event_invalid error=Invalid"
                + " reason=MALFORMED_JSON deliveryAttempt=1"), consumedTexts());
        assertEquals(processorTexts(), List.of(), "格式非法不是处理失败，不写 error 日志");
    }

    @Test
    @DisplayName("三种坏法各自带上自己的 reason——短码的全部意义就在这里")
    void reasonFollowsTheKindOfDamage() {
        assertEquals(RiskEventInvalidReason.MISSING_FIELD,
                invalidReasonOf(body(RiskAction.LIMIT).replace(",\"errandId\":3003", "")));
        assertEquals(RiskEventInvalidReason.UNKNOWN_ENUM,
                invalidReasonOf(body(RiskAction.LIMIT)
                        .replace("\"decisionAction\":\"LIMIT\"", "\"decisionAction\":\"SLOW_DOWN\"")));
        assertEquals(RiskEventInvalidReason.INCONSISTENT_EVENT,
                invalidReasonOf(body(RiskAction.LIMIT).replace("\"evt-1\"", "\"  \"")));
    }

    @Test
    @DisplayName("不是 Invalid 的 IAE 落到兜底值，而不是把消费端炸掉")
    void unknownIllegalArgumentFallsBack() {
        // 从 process 走不到这一支（解析器的每一个 IAE 都是它自己的 Invalid），
        // 所以它的入口是包级可见的 reasonOf。它存在的意义是：将来某处逃出一个普通的
        // IllegalArgumentException 时，这一行仍然有完整的三个字段——而不是在这里
        // 抛 ClassCastException，把一条日志问题升级成一次消费失败。
        assertEquals(RiskEventInvalidReason.INCONSISTENT_EVENT,
                RiskEventProcessor.reasonOf(new IllegalArgumentException("不是解析器抛的")));
    }

    private RiskEventInvalidReason invalidReasonOf(String broken) {
        // 先清空：这个夹具在一个用例里会被调用多次，而 appender 是跨调用累积的——
        // 不清的话第二次读到的还是第一次那行（第一版就是这么错的：三条断言全落在
        // 第一行的原因上，于是后两条以「原因不对」的形式失败，看起来像解析器的锅）。
        consumeLines.list.clear();
        processorAt(NOW).process(broken, 1);
        String line = consumedTexts().get(0);
        for (RiskEventInvalidReason reason : RiskEventInvalidReason.values()) {
            if (line.contains(" reason=" + reason + " ")) {
                return reason;
            }
        }
        throw new AssertionError("这一行里没有认识的原因短码：" + line);
    }

    // ------------------------------------------------------------------
    // 处理失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("处理失败：返回 FAILURE 交给 broker 重投，且不冒充一条消费行")
    void processingFailureAsksForRedelivery() {
        // 时钟是唯一能让「解析之后」失败的注入点，而它恰好是真实的失败形态之一
        // （坏掉的时钟、坏掉的日志框架、将来某个下游）。
        RiskEventProcessor processor = new RiskEventProcessor(() -> {
            throw new IllegalStateException("时钟坏了");
        });

        ConsumeResult result = processor.process(body(RiskAction.LIMIT), 3);

        assertEquals(ConsumeResult.FAILURE, result);
        // 一条消费行都没写出去：事件没被处理完，日志上就不该出现「处理完成」。
        // 若实现把 emit 放在 try 之外（或提前吞掉时钟异常），这条会红。
        assertEquals(consumedTexts(), List.of());
    }

    @Test
    @DisplayName("失败的那条 error 日志不带 body——它同样不许打印 requestId")
    void failureLogCarriesNoBody() {
        // 补的这条日志不是设计里的五条行之一，所以它更容易被「顺手把 body 打出来方便排查」
        // 改掉。而 body 里有 requestId，原始 body 的长度与转义都不受控（设计 §6.2）。
        RiskEventProcessor processor = new RiskEventProcessor(() -> {
            throw new IllegalStateException("时钟坏了");
        });

        processor.process(body(RiskAction.LIMIT), 3);

        List<String> texts = processorTexts();
        assertEquals(1, texts.size(), texts.toString());
        assertTrue(texts.get(0).contains("evt-1"), "eventId 是应用自己生成的，它是排查的入口：" + texts.get(0));
        assertFalse(texts.get(0).contains("req-abc"), texts.get(0));
        assertFalse(texts.get(0).contains("{\"eventId\""), texts.get(0));
    }

    // ------------------------------------------------------------------
    // D12 的结构性回归锁
    // ------------------------------------------------------------------

    @Test
    @DisplayName("构造器与字段里没有任何存储依赖——D12 用反射钉住")
    void carriesNoStorageDependency() {
        // 断言的是**名字**而不是加载类：这样这条用例本身不会因为某天挪走一个依赖而失败，
        // 而下面的正对照仍然逼着 isStorage 真的能认出存储类型——
        // 一个永远返回 false 的判据会让这条用例以「全绿」的形式失效。
        assertTrue(isStorage("org.springframework.data.redis.core.StringRedisTemplate"), "正对照");
        assertTrue(isStorage("org.springframework.jdbc.core.JdbcTemplate"), "正对照");
        assertFalse(isStorage("java.util.function.LongSupplier"), "负对照");

        Constructor<?>[] constructors = RiskEventProcessor.class.getDeclaredConstructors();
        assertEquals(2, constructors.length,
                "D12 的形状是「生产无参构造器 + 一个只收时钟的测试接缝」，多出来的是新依赖");

        List<String> dependencies = new ArrayList<>();
        for (Constructor<?> constructor : constructors) {
            for (Class<?> type : constructor.getParameterTypes()) {
                dependencies.add(type.getName());
            }
        }
        for (Field field : RiskEventProcessor.class.getDeclaredFields()) {
            if (!field.isSynthetic()) { // 编译器自己加的字段（如 $assertionsDisabled）不是依赖
                dependencies.add(field.getType().getName());
            }
        }

        assertEquals(List.of("java.util.function.LongSupplier", loggerType()),
                dependencies.stream().distinct().sorted().toList(),
                "这个类的全部依赖应当只有时钟与日志器；出现存储类型就是 D12 被破开了");
        assertTrue(dependencies.stream().noneMatch(RiskEventProcessorTest::isStorage),
                "出现了存储类型：" + dependencies);

        // 这条锁挡的是「加一个存储依赖」这个**动作**，挡不住「用一个静态 Map 自己当存储」
        // ——后者不在这条断言能表达的范围里。它能表达的范围与它的失效方式都要写在这里，
        // 否则下一个人会以为这行断言证明了比它实际证明的更多的东西。
    }

    private static boolean isStorage(String typeName) {
        return typeName.startsWith("org.springframework.data.")
                || typeName.startsWith("org.springframework.jdbc.")
                || typeName.endsWith("JdbcTemplate")
                || typeName.endsWith("RedisTemplate");
    }

    private static String loggerType() {
        return org.slf4j.Logger.class.getName();
    }

    private static Logger consumeLogLogger() {
        return (Logger) LoggerFactory.getLogger(RiskEventConsumeLog.class);
    }

    private static Logger processorLogger() {
        return (Logger) LoggerFactory.getLogger(RiskEventProcessor.class);
    }
}
