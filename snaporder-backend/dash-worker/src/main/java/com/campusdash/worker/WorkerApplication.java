package com.campusdash.worker;

import com.campusdash.application.usecase.GrabErrandUseCase;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * worker 独立进程。
 *
 * 为什么不和在线服务跑在一个进程里：抢单是低延迟在线请求，
 * 超时流转、消息重发、后续的结算对账都是后台批量任务。放一起会互相拖累——
 * 对账 job 一跑，GC 抖动直接打到抢单的 P99 上。
 *
 * 拆开之后：资源隔离（worker 可单独调堆与 GC）、部署隔离（worker 重启不影响抢单）、
 * 扩缩容独立。业务逻辑复用 dash-application，不重复实现。
 *
 * <p><b>为什么要排除风控</b>（预存缺陷与坑 D-5）：{@code com.campusdash.infrastructure}
 * 这个包很宽，装了各层的适配器；P9.1 起风控适配器也落在里面，而它们构造时要
 * {@code RiskProperties}——那是个 {@code @ConfigurationProperties}，靠
 * {@code @ConfigurationPropertiesScan} 注册，而那个注解只在在线服务的
 * {@code CampusDashApplication} 上。于是 worker 里没人提供这个 bean，**进程直接起不来**
 * （失败输出见 D-5）。这个缺陷在 P9.1/P9.2/P9.3 三次验收里都没被看见，因为 IT 全在
 * dash-bootstrap、而 worker 没有任何会加载上下文的测试——本类所在的模块因此也补上了
 * {@code WorkerApplicationContextTest}。
 *
 * <p><b>为什么是「排除」而不是「给 worker 也注册 RiskProperties」</b>：worker 不跑
 * 在线抢单链路，风控适配器它一个都用不到。让 worker 满足用不到的东西的校验，代价是
 * worker 必须持有 {@code dash.risk.device-hmac-secret}（设备哈希密钥，它永远用不到），
 * 且今后 {@code dash.risk} 每加一个无默认值的配置项都要在 worker yaml 里同步一份——
 * 正是 K-1 那类「两份 yaml 必须人工保持一致」的陷阱。方向应该是让 worker 不再持有
 * 它用不到的东西。
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.campusdash.worker",
        "com.campusdash.application",
        "com.campusdash.infrastructure"
}, excludeFilters = {
        // 风控的适配器（infrastructure.risk，含 RiskConfig）与用例（application.risk）。
        // 按包级正则而不是逐个点名类：风控之后每一期都会加新的 @Service / @Component，
        // 逐个列举意味着每加一个就要回来改这里，而漏改的表现又是「进程起不来」。
        @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.campusdash\\.(application|infrastructure)\\.risk\\..*"),
        // GrabErrandUseCase 是唯一一条把风控拖进 worker 的引用边，而它所在的
        // com.campusdash.application.usecase 不能按包排除（同包里全是 worker 要用的
        // 超时流转、结算、对账用例）。注意这条不能省：只排上面两个包的话，
        // RiskPreCheckUseCase 排掉了，但 GrabErrandUseCase 会因为找不到它而装配失败，
        // 只是报错换个地方出现。反过来说，只排这一条也不行——RiskPreCheckUseCase 自己
        // 还要三个风控端口（RiskFeaturePort / RiskBlacklistPort / RiskLimitPort）。
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = GrabErrandUseCase.class)
})
@EnableScheduling
@EnableTransactionManagement
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
