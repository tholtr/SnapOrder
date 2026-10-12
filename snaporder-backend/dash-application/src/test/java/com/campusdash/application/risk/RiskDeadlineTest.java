package com.campusdash.application.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算值对象的边界：**从哪一刻起算耗尽**。
 *
 * <p>这个判断是全链路里唯一决定「还发不发下一条命令」的东西，错一纳秒的后果就是
 * 多一次或少一次 Redis 往返。所以边界值（恰好用满、还差一纳秒）必须钉住，
 * 只测「明显超了」与「明显没超」等于没测边界。
 */
@DisplayName("预检时间预算")
class RiskDeadlineTest {

    /** 假时钟：由测试自己推进，不读真实时间。起点取一个非零值，免得「起点是 0」掩盖减法写错。 */
    private final AtomicLong nanos = new AtomicLong(1_000_000L);

    @Test
    @DisplayName("预算为 0 时生来就是耗尽的——「一步都不许跑」")
    void zeroBudgetIsExpiredAtBirth() {
        // deadline-ms=0 是故障降级的纯配置触发方式（D11），它必须真的「一步都不跑」。
        // 这就是边界取 >= 而不是 > 的原因：换成 > 的话 0 预算会退化成「第一步照跑」，
        // 而依赖它的那条端到端用例（ENFORCE + fail-open=false + deadline-ms=0 时
        // 每个请求都必须被拒）会变成一条时绿时红的用例。
        assertTrue(RiskDeadline.after(0, nanos::get).expired());
    }

    @Test
    @DisplayName("还差一纳秒不算耗尽，恰好用满就算")
    void boundaryIsExactlyTheBudget() {
        RiskDeadline deadline = RiskDeadline.after(10, nanos::get);

        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(9) + 999_999L);   // 用掉 9.999999 ms
        assertFalse(deadline.expired(), "还差一纳秒就不算耗尽");

        nanos.addAndGet(1L);                                            // 恰好 10 ms
        assertTrue(deadline.expired(), "预算用完即算耗尽，不必等到超过");
    }

    @Test
    @DisplayName("起点取自构造那一刻，之后只认注入的时钟")
    void onlyTheInjectedClockMatters() throws InterruptedException {
        RiskDeadline deadline = RiskDeadline.after(20, nanos::get);

        // 真实时间流逝 30ms > 预算 20ms。若这里读的是 System.nanoTime，下面那条断言必红。
        // 这个 sleep 只会让「读错时钟」这一失败模式更确定地暴露，不会让通过的路径变脆——
        // 通过的路径压根不依赖真实时间。
        Thread.sleep(30);
        assertFalse(deadline.expired(), "时钟是注入的，真实时间的流逝与它无关");
    }
}
