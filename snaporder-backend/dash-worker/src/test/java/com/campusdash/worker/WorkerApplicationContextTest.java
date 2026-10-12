package com.campusdash.worker;

import com.campusdash.application.risk.RiskPreCheckUseCase;
import com.campusdash.application.usecase.GrabErrandUseCase;
import com.campusdash.application.usecase.TimeoutTransferUseCase;
import com.campusdash.infrastructure.risk.RedisRiskBlacklistAdapter;
import com.campusdash.infrastructure.risk.RedisRiskFeatureAdapter;
import com.campusdash.infrastructure.risk.RedisRiskLimitAdapter;
import com.campusdash.infrastructure.risk.RiskConfig;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worker 进程的装配回归网（P9.4 任务 0c）。
 *
 * <p><b>为什么这个类必须存在</b>：`worker` 曾经**根本起不来**——{@code @ComponentScan}
 * 把 {@code com.campusdash.infrastructure} 整包扫了进来，风控适配器因此也进了 worker，
 * 而它们构造时要的 {@code RiskProperties} 靠 {@code @ConfigurationPropertiesScan} 注册、
 * 那个注解只在在线服务的 {@code CampusDashApplication} 上。P9.1 引入，**P9.1/P9.2/P9.3
 * 三次阶段验收全部通过**：因为全部 27 个 IT 都住在 dash-bootstrap，而本模块当时只有一个
 * 纯反射测试，没有任何会加载 Spring 上下文的东西（完整记录见预存缺陷与坑 D-5）。
 *
 * <p>所以这个类不是「顺手加的覆盖率」，它是 D-5 那类缺陷**唯一**的结构性防线：
 * 同类的错法（扫描范围写宽了、某个 bean 的依赖没人提供）在这里都会表现为上下文起不来。
 *
 * <p><b>为什么这里也要中间件守卫</b>：设计时以为「测装配不需要中间件」，实测证明不成立——
 * {@code RedissonConfig} 无条件装配，它的工厂方法在 bean 创建时就连 Redis，把 redis 指向
 * 死端口后上下文刷新直接失败。所以本类与 dash-bootstrap 的 27 个 IT 同处境：中间件没起就
 * 整类跳过，而不是变红（探测在 {@link WorkerMiddleware}，那里记了完整理由）。
 *
 * <p>代价要说清楚：**中间件没起时这个测试不会跑**。也就是说它并不是万无一失的——
 * 但它在「本机起了 Docker」这个日常开发前提下每次都会跑，而 D-5 当年是在**任何前提下**
 * 都不跑（本模块根本没有加载上下文的测试）。这是从「零覆盖」到「有覆盖」的差别。
 *
 * <p><b>它还必须挂上 {@link NoScheduling}</b>：本类是全仓唯一一个加载 worker 上下文的
 * 测试，而 worker 的五个 {@code @Scheduled} 任务都没有 {@code initialDelay}——启动瞬间
 * 就跑第一次，并且会写共享的集成测试库（实测会改 {@code credit_score} /
 * {@code credit_event} / {@code local_message}）。不挡的话，每跑一次单测就给别的 IT
 * 留下一点脏数据。完整证据与代价写在 {@link NoScheduling} 的类注释里。
 */
@SpringBootTest(classes = {WorkerApplication.class, NoScheduling.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkerApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(WorkerMiddleware.check(),
                "MySQL 3307 / Redis 6380 未启动，跳过 worker 装配测试");
    }

    @Test
    @DisplayName("worker 上下文能起来——D-5 的回归锁")
    void contextLoads() {
        assertTrue(context.containsBean("workerApplication"),
                "上下文没起来，或者起来的是别的类");
    }

    @Test
    @DisplayName("定时任务被整体挡住——上下文起一次不会往共享库里写任何东西")
    void schedulingIsDisabled() {
        // 这条是上面那条副作用的回归锁。判据取「上下文里的 TaskScheduler 就是那个
        // 永不触发的实现」，而不是「五个 job bean 不存在」：job 仍然装配着（它们
        // 是该在的），只是没有任何东西会去调它们。
        //
        // 只有 NoScheduling 参与装配时这个断言才成立。谁把 classes = {...} 里的
        // NoScheduling 去掉，Spring Boot 的 TaskSchedulingAutoConfiguration 就会补上
        // 一个真的 ThreadPoolTaskScheduler，这里立刻红——而不是等到某天
        // RiskGrabLinkIT 因为信用分被扣掉而莫名其妙地失败时才发现。
        Object scheduler = context.getBean("taskScheduler");
        assertTrue(scheduler instanceof NoScheduling.NeverFiresTaskScheduler,
                "上下文里的调度器不是 NoScheduling 提供的那个，实际是 " + scheduler.getClass().getName()
                        + "：定时任务会在启动时真的跑起来，并写进共享的集成测试库");
    }

    @Test
    @DisplayName("worker 不持有风控的任何 bean——这正是 D-5 的修法")
    void doesNotHoldRiskBeans() {
        // 一次把「排除掉的四类」都点出来，而不是只断言其中一个：
        // 只排 GrabErrandUseCase 会让 RiskPreCheckUseCase 因为找不到端口而失败，
        // 只排两个 risk 包会让 GrabErrandUseCase 失败——两个过滤器的存在理由不同，
        // 断言也必须各自点到，否则删掉其中一个时这条用例仍然是绿的。
        assertFalse(context.getBeanNamesForType(GrabErrandUseCase.class).length > 0,
                "GrabErrandUseCase 被扫进了 worker：它是唯一一条把风控拖进来的引用边");
        assertFalse(context.getBeanNamesForType(RiskPreCheckUseCase.class).length > 0,
                "RiskPreCheckUseCase 被扫进了 worker：它依赖三个风控端口，worker 里没有");
        assertFalse(context.getBeanNamesForType(RedisRiskFeatureAdapter.class).length > 0,
                "风控特征适配器被扫进了 worker：它构造时要 RiskProperties，worker 里没有");
        assertFalse(context.getBeanNamesForType(RedisRiskBlacklistAdapter.class).length > 0,
                "风控黑名单适配器被扫进了 worker");
        assertFalse(context.getBeanNamesForType(RedisRiskLimitAdapter.class).length > 0,
                "风控限频适配器被扫进了 worker");
        assertFalse(context.getBeanNamesForType(RiskConfig.class).length > 0,
                "RiskConfig 被扫进了 worker：它的两个 @Bean 都要 RiskProperties");
    }

    @Test
    @DisplayName("排除风控没有把 worker 自己要用的东西一起排掉")
    void keepsWhatWorkerNeeds() {
        // 反向断言。上面那条只证明「风控不在了」，把 @ComponentScan 整个删空也能通过；
        // 这一条把「排多了」也挡在外面——排除过滤器是按包级正则匹配的，
        // 正则写宽一格就会误伤 com.campusdash.application 下的其它用例，
        // 而误伤的表现同样是「启动失败」，只是原因完全不同。
        assertTrue(context.getBeanNamesForType(TimeoutTransferUseCase.class).length > 0,
                "超时流转用例不见了：排除过滤器误伤了 com.campusdash.application");
        assertTrue(context.getBeanNamesForType(SnowflakeIdGenerator.class).length > 0,
                "WorkerBeans 里的雪花 ID 生成器不见了，worker 的装配被排空了");
    }
}
