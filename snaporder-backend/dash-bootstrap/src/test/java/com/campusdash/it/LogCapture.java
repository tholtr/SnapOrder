package com.campusdash.it;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/**
 * 往某个 logger 上挂一个内存 appender，把这一段时间里它吐出来的行抓下来。
 *
 * <p>为什么要抓日志：{@code risk_decision} / {@code risk_event_publish} 这几行**没有别的观测面**
 * ——它们是 SLF4J 输出，既不进数据库也不进响应体，而它们承载的恰恰是若干条验收判据
 * （决策动作、投递结果、聚合抑制）。logger 与 Spring 应用在同一个 JVM、同一个 SLF4J 绑定里，
 * 所以抓到的是**真实应用写出来的行**，不是测试自己拼的字符串。
 *
 * <p>行格式本身由各自的 {@code *LogTest} 逐字锁住（单测），这里只负责取值，
 * 不重复断言格式。
 *
 * <h2>为什么是 {@code AutoCloseable} 而不是 before/after 两个方法</h2>
 *
 * <p>挂在 logger 上的 appender 不摘掉就会**跨用例累积**：下一个用例的 {@code lines()}
 * 里混着上一个用例的行，断言「恰好一条」时随机失败，而且失败点离原因很远。
 * 写成 try-with-resources 让「挂上—摘掉」成为语法上成对的一件事，
 * 比依赖程序员记得写 {@code @AfterEach} 可靠。
 *
 * <p>{@code close()} 里必须先 {@code detachAppender} 再 {@code stop}：
 * 反过来的话，{@code stop()} 之后 appender 会把「没启动」当作理由静默丢弃后续事件，
 * 而它此时仍挂在 logger 上——下一个用例的捕获会变成空列表。
 */
final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;

    private LogCapture(Logger logger, ListAppender<ILoggingEvent> appender) {
        this.logger = logger;
        this.appender = appender;
    }

    /** 挂到 {@code owner} 这个类的 logger 上。调用方负责 close（try-with-resources）。 */
    static LogCapture of(Class<?> owner) {
        Logger logger = (Logger) LoggerFactory.getLogger(owner);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LogCapture(logger, appender);
    }

    /** 抓到的全部行，不过滤。断言失败时把它打进消息里，比只说「没抓到」有用得多。 */
    List<String> raw() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 只留以 {@code prefix} 打头的行（{@code risk_event_publish} / {@code risk_decision} …）。 */
    List<String> lines(String prefix) {
        return raw().stream()
                .filter(m -> m.startsWith(prefix))
                .toList();
    }

    /**
     * 轮询等到满足条件的行攒够 {@code count} 条，返回这些行；超时则返回已有的那些，
     * 让调用方的断言去报错（带上它当时看到的东西）。
     *
     * <p><b>为什么非等不可</b>：{@code risk_event_publish} 是**异步**写的——它在
     * {@code risk-event-pub} 线程上落盘，而 {@code grab()} 早就返回了。所以「用例跑完立刻断言」
     * 会稳定地输给那几毫秒：实测第 2 条 SUPPRESSED 行比断言晚 1~2ms 出现，
     * 失败信息看起来像「聚合没抑制」，其实是抓晚了。
     *
     * <p>等待的条数由调用方算出来（它知道这次该产生几条），而不是「等到不再新增」——
     * 后者需要一个静默窗口，而静默窗口在「本来就只有一条」时纯属白等。
     *
     * <p>顺带解决可见性：日志行由别的线程写入 {@link ListAppender} 那个普通
     * {@code ArrayList}，轮询会反复重新读它，而不是读一次就下结论。
     */
    List<String> awaitLines(String prefix, Predicate<String> matcher, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<String> hit = lines(prefix).stream().filter(matcher).toList();
            if (hit.size() >= count || System.nanoTime() >= deadline) {
                return hit;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return hit;
            }
        }
    }

    /**
     * 从 {@code key=value} 形式的日志行里抠出某个字段的值；字段不在行里返回 {@code null}。
     *
     * <p>匹配的是 <b>{@code " " + name + "="}</b>（前面带那个空格），不是裸的 {@code name=}：
     * 本仓库的字段名互为后缀——{@code action=} 是 {@code decisionAction=} 的后缀，
     * {@code eventId=} 是 {@code requestId=} 的后缀——裸匹配会安静地取到**错的那个字段**
     * （比如把 {@code decisionAction=LIMIT} 读成 {@code action=LIMIT}，看起来还挺合理）。
     * 带空格的锚点同时也就是字段分隔符，正是想要的语义。
     *
     * <p>值读到下一个空格为止；字段是行尾时读到行尾。值为空串时返回空串（不是 null）。
     */
    static String field(String line, String name) {
        String marker = " " + name + "=";
        int from = line.indexOf(marker);
        if (from < 0) {
            return null;
        }
        int start = from + marker.length();
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
