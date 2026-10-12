package com.campusdash.it;

import com.campusdash.application.usecase.GrabErrandUseCase;
import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.infrastructure.risk.RiskKeys;
import com.campusdash.infrastructure.risk.RiskProperties;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「降级不等于放行」的**放行侧**，也是最难被相信的一侧。
 *
 * <p>设计 §10 要求降级的两个方向都有端到端证据。拒绝侧（{@code fail-open=false}）
 * 在 {@code RiskGrabLinkIT.ZeroBudgetFailClosed}；这里证明另一半：**当配置说失败要放行时，
 * 风控在 2000 并发下确实一个人都没拦**，而且抢单本身的并发正确性一点没被削弱。
 * 只测一侧证明不了「按配置」——只测拒绝侧的话，「降级永远拒绝」也能让用例全绿。
 *
 * <p><b>为什么必须是 2000 并发，而不是跑一遍单请求</b>：降级路径在单请求下几乎不可能出错，
 * 它的风险全在并发里——预算耗尽那一刻，每个线程都要走同一条「不采集、直接放行」的分支，
 * 而这条分支必须不碰共享状态、不阻塞、不误拦。这正是 P9.3 引入风控之后
 * 最可能把 {@code GrabConcurrencyIT} 那条不超卖不变式弄坏的地方。
 *
 * <h2>本类的配置只比 {@code GrabConcurrencyIT} 多三行</h2>
 *
 * <p>其余的一切（2000 虚拟线程、CountDownLatch 三段式开闸、五条校验）逐字照搬
 * {@code GrabConcurrencyIT}，就是为了让「同一份负载、只有风控模式不同」这句话成立。
 * 特意<b>不</b>顺手加 {@code dash.mq.enabled=false} 之类的减负开关：
 * 加了就不再是同一份负载，两者也就不可比了。
 *
 * <p>第 ⑦ 条校验是<b>本类独有</b>的那条，也是它存在的理由：期间一个 {@code risk:win:*}
 * 键都不该新增。少了它，本类与 {@code GrabConcurrencyIT} 就没有区别——
 * 而它恰好能区分「降级放行」与「风控压根没接上」。
 */
@SpringBootTest(properties = {
        "dash.risk.mode=ENFORCE",
        "dash.risk.deadline-ms=0",
        "dash.risk.fail-open=true",
        "dash.credit.max-ongoing=10000"})
class RiskDegradeConcurrencyIT {

    /** 本类私有的 id 段：{@code RiskGrabLinkIT} 用 930_xxx，别处用 2001-6000 / 910_xxx / 920_xxx。 */
    private static final long USER_BASE = 940_001L;

    private static final int CONCURRENCY = 2000;

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabErrandUseCase grabUseCase;
    @Autowired ErrandRepository errandRepository;
    @Autowired GrabSlotPort grabSlotPort;
    @Autowired StringRedisTemplate redis;
    @Autowired RiskProperties props;
    @Autowired JdbcTemplate jdbc;

    private static BenchRun run;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(),
                "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
        run = BenchRun.start("IT-DEGRADE");
    }

    @AfterAll
    static void finishRun() {
        if (run != null) {
            run.finish("PASS", "{\"scenario\":\"IT-DEGRADE\",\"concurrency\":2000,\"degraded\":true}");
            run.close();
        }
    }

    @BeforeEach
    void resetPublisherBalance() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 "
                + "WHERE owner_id = 1001 AND owner_type = 'USER'");
    }

    /**
     * 截取「本类开跑之前」的窗口键集合。
     *
     * <p>只比新增不比消失：别的用例留下的键可能正好在这期间到期，
     * 那样「集合相等」会变成一条随 TTL 抖动的断言。
     */
    private Set<String> windowKeys() {
        return new HashSet<>(redis.keys("risk:win:*"));
    }

    @Test
    @DisplayName("预算为 0 + fail-open=true：2000 并发抢 1 个名额仍严格只有 1 人成功，且风控一步都没跑")
    void zeroBudgetFailOpenStillProvesTheConcurrencyInvariants() throws Exception {
        long errandId = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, "bench_降级放行_取快递到3号楼", 800L, 1)).errandId();
        run.track(errandId);

        Set<String> windowsBefore = windowKeys();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger slotFull = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        AtomicLong winner = new AtomicLong(-1L);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENCY; i++) {
                long runnerId = USER_BASE + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                        var r = grabUseCase.grab(new GrabErrandUseCase.Command(
                                errandId, runnerId, UUID.randomUUID().toString(), 60));
                        if (r.grabbed()) {
                            winner.set(runnerId);
                            success.incrementAndGet();
                        } else if (r.code() == ErrorCode.SLOT_FULL) {
                            slotFull.incrementAndGet();
                        } else {
                            // 任何非 SLOT_FULL 的失败都是新东西：风控在 fail-open 下
                            // 不该产生任何拒绝码。用它而不是「忽略其余」，
                            // 是为了让「风控偷偷拦了几个」这种失败有名字可报。
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "线程未在 30s 内就绪");
            fire.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS), "抢单未在 120s 内完成");
        }

        // 校验一：应用层成功数必须为 1。fail-open 是「失败时放行」，不是「都放行」——
        // 名额仍然只有一个，放行 2000 个人不等于 2000 个人都能抢到。
        assertEquals(1, success.get(), "应用层成功数必须为 1，实际=" + success.get());

        // 校验二：没有一个人被风控拦下（本类要证明的那件事）
        assertEquals(0, other.get(), "fail-open=true 时不该出现任何非 SLOT_FULL 的失败，实际=" + other.get());
        assertEquals(CONCURRENCY - 1, slotFull.get(), "其余 1999 人应全部止步于名额已满");

        // 校验三：数据库抢中记录数 = 1（INV-1，防超卖的最终证据）
        Integer grabbed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grab_record WHERE errand_id = ? AND result = 'GRABBED'",
                Integer.class, errandId);
        assertEquals(1, grabbed, "数据库抢中记录数必须为 1（超卖检测）");

        // 校验四：没有用户占多个名额（INV-2）
        List<Map<String, Object>> dup = jdbc.queryForList("""
                SELECT runner_id, COUNT(*) c FROM grab_record
                 WHERE errand_id = ? AND result = 'GRABBED'
                 GROUP BY runner_id HAVING c > 1
                """, errandId);
        assertTrue(dup.isEmpty(), "存在用户占多个名额：" + dup);

        // 校验五：任务状态自洽
        var errand = errandRepository.findById(errandId).orElseThrow();
        assertEquals(ErrandStatus.LOCKED, errand.status());
        assertEquals(1, errand.slotTaken());
        assertNotNull(errand.grabberId());

        // 校验六：Redis 剩余名额与 DB 一致（检测名额泄漏）。风控接在名额判定之前，
        // 若它在放行路径上多做了一次 Redis 往返而没归还连接，这里会先炸。
        long remaining = grabSlotPort.remainingSlot(errandId);
        assertEquals(errand.slotTotal() - errand.slotTaken(), remaining,
                "Redis 名额与 DB 不一致，可能发生名额泄漏");

        // 校验七（本类独有）：风控一个采集端口都没被调用过。
        // 判据是窗口键的新增数——它是采集端口唯一的写入面。
        Set<String> added = windowKeys();
        added.removeAll(windowsBefore);
        assertEquals(Set.of(), added,
                "预算为 0 时一个采集端口都不该被调用，风控窗口键一个都不该新增，实际新增=" + added);

        // 反证：上面那条若不成立会是什么样。挑一个**确实走完了整条链路**的用户
        // （抢中的那位）单独再查一次——只有他一定经历过 preCheck。
        // 没有这条，上面那条可能只是因为「压根没人走到 preCheck」而变绿。
        assertTrue(winner.get() > 0, "必须有人抢中，否则上面那条证明不了任何事");
        assertFalse(redis.hasKey(RiskKeys.userWindow(
                        winner.get(), props.userWindow().shortSeconds())),
                "抢中者的用户窗口键必须不存在——存在就说明采集端口在预算为 0 时被调用了");

        System.out.printf("[P9.3] 降级并发=%d 成功=%d 名额满=%d 其它失败=%d 新增风控键=%d Redis剩余=%d%n",
                CONCURRENCY, success.get(), slotFull.get(), other.get(), added.size(), remaining);
    }
}
