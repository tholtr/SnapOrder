package com.campusdash.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 把 worker 的定时任务整体挡在测试之外：提供一个**永不触发**的 {@link TaskScheduler}。
 *
 * <p><b>为什么必须有它</b>：五个 {@code @Scheduled} 任务（{@code TimeoutScanJob}、
 * {@code LocalMessageRetryJob}、{@code AutoSettleScanJob}、{@code ReconciliationJob}、
 * {@code CreditCalibrationJob}、{@code CacheConsistencyCheckScheduler}）都写成
 * {@code @Scheduled(fixedDelayString = ...)} 或 {@code @Scheduled(cron = ...)}，
 * **没有一个带 initialDelay**——于是第一次执行发生在上下文启动的瞬间，间隔值只推迟第二次。
 * 把 {@code dash.timeout.scan-interval-ms} 放大到一小时这类「配置层防御」因此完全无效。
 *
 * <p>后果是实测过的，不是推测。一次普通的 {@code mvn -o -pl dash-worker test} 让共享的
 * 集成测试库发生这些变化：
 *
 * <pre>
 *   credit_score   0 → 1     行：user_id=930103, score=55
 *   credit_event   0 → 1     行：GRAB_TIMEOUT_REVERT  delta=-5  ref=ERRAND 224164447676010496
 *   local_message  PENDING 82 → 78
 * </pre>
 *
 * <p>{@code 930103} 落在 {@code RiskGrabLinkIT} 的 id 段（{@code USER_BASE = 930_000L}）里。
 * 也就是说每跑一次单测，worker 都会对别的集成测试刚用过的用户扣一次信用分；攒够几次，
 * {@code RiskGrabLinkIT} / {@code TimeoutTransferIT} 的抢单就会被 {@code CREDIT_TOO_LOW}
 * 拦住——而失败的现场里看不出任何与 worker 有关的东西。这正是「单测污染集成测试库」
 * 这一类最难查的问题：**测试全绿，然后某天另一批测试红了，且不在同一个模块**。
 *
 * <p><b>为什么是换调度器而不是排除那五个 bean</b>：它们是 {@code com.campusdash.worker}
 * 包下的 {@code @Component}，而 {@code WorkerApplication} 会扫这个包。测试想排除它们，
 * 只能往 {@code @ComponentScan.excludeFilters} 上加东西——那是**改生产装配**（D-5 的
 * 排除过滤是另一回事，那条排除在测试与生产里都成立且是设计要的）。换调度器是纯测试侧
 * 的开关，生产 wiring 一个字不动。
 *
 * <p><b>为什么不会丢覆盖</b>：当前 {@code dash-worker} 的测试只有
 * {@code AutoSettleConsumerTest} 与 {@code WorkerApplicationContextTest}，
 * **没有任何一条用例直接调这五个 job**。被挡掉的是空转，不是断言。
 *
 * <p><b>它挡不住什么</b>：{@code @PostConstruct} 不在 {@code @Scheduled} 的范围里——
 * {@code BloomRebuildJob.rebuildOnStartup()} 与四个消费者的 {@code @PostConstruct}
 * 照常执行，分别由测试 yaml 的 {@code dash.cache.bloom-enabled: false} 与
 * {@code dash.mq.enabled: false} 挡住。两个开关各管一段，都需要。
 */
@TestConfiguration
public class NoScheduling {

    /**
     * bean 名必须是 {@code taskScheduler}：Spring Boot 的
     * {@code TaskSchedulingAutoConfiguration} 用 {@code @ConditionalOnMissingBean}
     * 看着这个名字与这个类型，见到它就自动退让，于是上下文里只剩这一个调度器，
     * {@code @EnableScheduling} 注册的 {@code ScheduledAnnotationBeanPostProcessor}
     * 必然挑中它。
     */
    @Bean
    TaskScheduler taskScheduler() {
        return new NeverFiresTaskScheduler();
    }

    /**
     * 接受任务、登记一个「永不完成」的 future、**不执行回调**。
     *
     * <p>返回的 future 是拿一个空任务排到一年后得到的：这样它既能被 {@code cancel}，
     * {@code isDone()} 也如实返回 false——语义上就是「已登记，不会跑」。那条空任务本身
     * 一年内不会被执行，而测试 JVM 活不了那么久。线程设为 daemon，免得 surefire
     * 收尾时等一个非守护线程。
     */
    static final class NeverFiresTaskScheduler implements TaskScheduler {
        private static final long ONE_YEAR_DAYS = 365L;

        private final ScheduledExecutorService carrier =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "no-scheduling-carrier");
                    t.setDaemon(true);
                    return t;
                });

        /** 唯一的产出方式：把**空任务**排到一年后，与传进来的 task 无关。 */
        private ScheduledFuture<?> never() {
            return carrier.schedule(() -> { }, ONE_YEAR_DAYS, TimeUnit.DAYS);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return never();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return never();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, java.time.Duration period) {
            return never();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, java.time.Duration period) {
            return never();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, java.time.Duration delay) {
            return never();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, java.time.Duration delay) {
            return never();
        }
    }
}
