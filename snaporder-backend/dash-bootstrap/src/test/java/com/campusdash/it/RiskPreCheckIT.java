package com.campusdash.it;

import com.campusdash.application.risk.RiskPreCheckResult;
import com.campusdash.application.risk.RiskPreCheckUseCase;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskContext;
import com.campusdash.domain.risk.model.RiskMode;
import com.campusdash.domain.risk.model.RiskPolicy;
import com.campusdash.domain.risk.model.RiskRuleId;
import com.campusdash.domain.risk.model.RiskRules;
import com.campusdash.domain.risk.ports.RiskBlacklistPort;
import com.campusdash.domain.risk.ports.RiskFeaturePort;
import com.campusdash.domain.risk.ports.RiskLimitPort;
import com.campusdash.infrastructure.risk.DeviceHasher;
import com.campusdash.infrastructure.risk.RiskKeys;
import com.campusdash.infrastructure.risk.RiskProperties;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 风控预检的集成测试——真实 Redis、真实适配器、真实黑名单脚本。
 *
 * <p><b>为什么这些必须有 IT</b>：§7.1 的 fake 端口锁的是「调用面」，而键名对不对、
 * 脚本返回的三个布尔有没有落到正确的维度上、限频键的 TTL 是不是真等于冷却时长，
 * 只有真跑一次才知道。本模块已经吃过一次教训——P9.1 的三个缺陷（编码悬崖、
 * TTL 耦合、查重顺序）全部是手工跑真实 Redis 才发现的，单测一个都没抓到。
 *
 * <p><b>为什么类级配置是 {@code ENFORCE} 而不是 {@code SHADOW}</b>：本类主体验的是
 * 「真的拦下来了」，走的是 Spring 装配出来的那个 {@code RiskPreCheckUseCase} bean。
 * 对照 {@code AuthModeIT}：那边的 mode 决定 Spring 挑哪一套 {@code AuthPort} 实现，
 * 所以必须两个 {@code @Nested} 上下文；这里 mode 只是**同一个类的构造参数**，
 * 为它多起一个 Spring 上下文不划算——「配置 → 模式」这一段由 {@code RiskConfigTest}
 * 单独断言。SHADOW 的场景因此手工构造用例，但端口与规则仍是 Spring 里的真货：
 * 它要证的性质是「哪些东西没被动过」，而那与谁构造的用例无关。
 *
 * <p><b>键一律经 {@code RiskKeys} / {@code DeviceHasher} 算出，不手写键名字符串</b>——
 * 黑名单的三条用例于是同时是「运维用 {@code BlacklistKeyTool} 写进去的键，应用查得到」
 * 的实证（工具与这里走的是同一条键计算路径）。
 *
 * <p><b>Redis 清理两头都做</b>：每个用例开头清掉自己会用到的那几个键（id 段是本类私有的
 * {@code 920_xxx}，其它 IT 用 {@code 910_xxx}），收尾再把本次写过的黑名单键删掉。
 * 开头那次是兜底——黑名单键**没有 TTL**（这是本模块刻意保留的性质，带 TTL 的黑名单
 * 会自己失效），上一轮崩溃留下的键会一直躺在共享测试库里，只有「下次运行开头清掉」
 * 能救它；收尾那次是不给共享库留垃圾。
 *
 * <p>不覆盖降级路径（fake 端口抛异常已在 §7.1 覆盖）与并发（§7.3），不在本类重复。
 */
@SpringBootTest(properties = {
        "dash.risk.mode=ENFORCE",
        // 不依赖外部 broker，与其它 IT 一致
        "dash.mq.enabled=false"})
class RiskPreCheckIT {

    /** 本类私有的 id 段。 */
    private static final long USER_BASE = 920_000L;

    /**
     * 只用于日志行。{@code RiskPreCheckUseCase} 是全仓第一个构造 {@link RiskContext}
     * 的地方（P9.1 的拦截器在 preHandle 阶段还拿不到 errandId），这里给一个常量即可。
     */
    private static final long ERRAND_ID = 9_200_001L;

    /**
     * 生产装配的那个用例。全部 ENFORCE 场景都走它——不是新造一个，
     * 这样「Spring 把端口、阈值、模式装配对了」也一并被覆盖。
     */
    @Autowired RiskPreCheckUseCase productionUseCase;

    @Autowired RiskFeaturePort featurePort;
    @Autowired RiskBlacklistPort blacklistPort;
    @Autowired RiskLimitPort limitPort;
    @Autowired RiskRules rules;
    @Autowired StringRedisTemplate redis;
    @Autowired DeviceHasher hasher;
    @Autowired RiskProperties props;

    /** 本用例写过的黑名单键，收尾时删掉（见 {@link #blacklist(String)}）。 */
    private final List<String> blacklistKeys = new ArrayList<>();

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(),
                "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private RiskContext context(long userId, String deviceId, String clientIp, String requestId) {
        return new RiskContext(userId, ERRAND_ID, requestId, deviceId, clientIp);
    }

    /** 单次调用的场景用同一个 requestId 即可，反正每个用例都有自己的 userId 段。 */
    private RiskContext context(long userId, String deviceId, String clientIp) {
        return context(userId, deviceId, clientIp, "it-p92-req");
    }

    /**
     * 与 {@code BlacklistKeyTool} 的写入路径同形：Redis Set、成员为 {@code "1"}、
     * 存在即拉黑。手工 {@code SET} 一个字符串也能命中（查询侧是类型无关的 {@code EXISTS}），
     * 但那种键形态与应用写的不是一回事，用在这里等于测了一条没人走的路径。
     *
     * <p>写过的键记在 {@link #blacklistKeys} 里，收尾时删掉。**黑名单键没有 TTL**，
     * 不清就会永久留在共享测试库里——而「没有 TTL」是本模块刻意保留的性质
     * （带 TTL 的黑名单会自己失效），不能靠给测试键加过期时间来绕过。
     */
    private void blacklist(String key) {
        redis.opsForSet().add(key, "1");
        blacklistKeys.add(key);
    }

    /**
     * 收尾清场。放在 {@code @AfterEach} 而不是「用例末尾手动删」：断言失败时后面的
     * 语句不会执行，而那正是最可能留下垃圾键的一次运行。
     */
    @AfterEach
    void removeBlacklistKeys() {
        if (!blacklistKeys.isEmpty()) {
            redis.delete(blacklistKeys);
        }
    }

    /** 清掉本用例会用到的那几个键，保证可重复运行。{@code null} 维度跳过。 */
    private void purge(long userId, String deviceId, String clientIp) {
        List<String> keys = new ArrayList<>(List.of(
                RiskKeys.userWindow(userId, props.userWindow().shortSeconds()),
                RiskKeys.userWindow(userId, props.userWindow().longSeconds()),
                RiskKeys.limitKey(userId),
                RiskKeys.blacklistUser(userId)));
        if (deviceId != null) {
            String deviceHash = hasher.hash(deviceId);
            keys.add(RiskKeys.deviceCluster(deviceHash));
            keys.add(RiskKeys.blacklistDevice(deviceHash));
        }
        if (clientIp != null) {
            String ipHash = hasher.hash(clientIp);
            keys.add(RiskKeys.ipAccounts(ipHash));
            keys.add(RiskKeys.ipRate(ipHash));
            keys.add(RiskKeys.blacklistIp(ipHash));
        }
        redis.delete(keys);
    }

    /** 当前库里全部风控键，用于「这次调用新增了哪些键」这类断言。 */
    private Set<String> riskKeys() {
        return new HashSet<>(redis.keys("risk:*"));
    }

    /** {@code before} 之后新增的风控键。**只看新增不看消失**：别的用例的键可能刚好到期。 */
    private Set<String> addedSince(Set<String> before) {
        Set<String> added = riskKeys();
        added.removeAll(before);
        return added;
    }

    /**
     * 连续请求直到用户 10 秒窗口越线，返回**最后一次**的结果。
     *
     * <p>次数由配置推导（{@code shortThreshold + 1} 次）而不是写死 6：阈值是可配的，
     * 写死会让用例在改配置后变成「跑完了但什么都没触发」，而它看起来仍然是绿的。
     */
    private RiskPreCheckResult requestUntilShortWindowFires(RiskPreCheckUseCase useCase,
                                                            long userId, String deviceId, String clientIp) {
        RiskPreCheckResult last = null;
        for (int i = 0; i <= props.userWindow().shortThreshold(); i++) {
            last = useCase.preCheck(context(userId, deviceId, clientIp, "burst-" + i));
        }
        return last;
    }

    // ── 黑名单 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("用户维度黑名单命中：BLOCK，且不写限频键")
    void blacklistedUserIsBlocked() {
        long userId = USER_BASE;
        purge(userId, null, null);
        blacklist(RiskKeys.blacklistUser(userId));

        RiskPreCheckResult result = productionUseCase.preCheck(context(userId, null, null));

        assertEquals(RiskAction.BLOCK, result.effectiveAction());
        assertEquals(List.of(RiskRuleId.BLACKLIST_USER), result.decision().matchedRules());
        assertEquals(ErrorCode.RISK_BLOCKED, result.errorCode());
        // BLOCK 的处置不是「冷一下再放」，写限频键会让同一个请求在冷却期内被两条规则各解释一次
        assertFalse(redis.hasKey(RiskKeys.limitKey(userId)), "BLOCK 不该写限频键");
    }

    @Test
    @DisplayName("设备维度黑名单命中：BLOCK")
    void blacklistedDeviceIsBlocked() {
        long userId = USER_BASE + 1;
        String device = "it-p92-bl-device";
        purge(userId, device, null);
        // 键经 DeviceHasher 哈希后算出——工具写的就是这个键
        blacklist(RiskKeys.blacklistDevice(hasher.hash(device)));

        RiskPreCheckResult result = productionUseCase.preCheck(context(userId, device, null));

        assertEquals(RiskAction.BLOCK, result.effectiveAction());
        // 三个布尔落错维度的话，这里会是 BLACKLIST_USER 或 BLACKLIST_IP
        assertEquals(List.of(RiskRuleId.BLACKLIST_DEVICE), result.decision().matchedRules());
    }

    @Test
    @DisplayName("IP 维度黑名单命中：BLOCK")
    void blacklistedIpIsBlocked() {
        long userId = USER_BASE + 2;
        String ip = "203.0.113.77";
        purge(userId, null, ip);
        blacklist(RiskKeys.blacklistIp(hasher.hash(ip)));

        RiskPreCheckResult result = productionUseCase.preCheck(context(userId, null, ip));

        assertEquals(RiskAction.BLOCK, result.effectiveAction());
        assertEquals(List.of(RiskRuleId.BLACKLIST_IP), result.decision().matchedRules());
    }

    @Test
    @DisplayName("黑名单解除后不再拦截：DEL 键后同一条请求放行")
    void delRemovesTheBlock() {
        long userId = USER_BASE + 3;
        purge(userId, null, null);
        String key = RiskKeys.blacklistUser(userId);
        blacklist(key);

        RiskPreCheckResult blocked = productionUseCase.preCheck(context(userId, null, null, "del-1"));
        assertEquals(RiskAction.BLOCK, blocked.effectiveAction(), "解除之前必须拦截");

        // 运维的解除动作。用 DEL 而不是 SREM：查询侧是类型无关的 EXISTS，
        // 一个被手工 SET 成字符串的键同样算命中（F-18）。
        redis.delete(key);

        RiskPreCheckResult released = productionUseCase.preCheck(context(userId, null, null, "del-2"));
        assertEquals(RiskAction.PASS, released.effectiveAction());
        assertEquals(List.of(), released.decision().matchedRules());
        assertEquals(ErrorCode.OK, released.errorCode());
    }

    @Test
    @DisplayName("设备维度缺失不影响其它维度：用户黑名单照常 BLOCK")
    void missingDeviceDimensionDoesNotAffectTheDecision() {
        long userId = USER_BASE + 4;
        purge(userId, null, null);
        blacklist(RiskKeys.blacklistUser(userId));

        RiskPreCheckResult result = productionUseCase.preCheck(context(userId, null, null));

        assertEquals(RiskAction.BLOCK, result.effectiveAction());
        // 设备规则不在命中列表里，但用户维度照常判定——缺失一个维度不等于整次预检失效
        assertEquals(List.of(RiskRuleId.BLACKLIST_USER), result.decision().matchedRules());
        assertEquals(List.of(), result.decision().degradeReasons(), "维度缺失不是降级：它压根不该被查");
    }

    // ── 限频键（ENFORCE）───────────────────────────────────────────────────

    @Test
    @DisplayName("命中限频键直接短路：LIMIT，且不产生任何特征键")
    void limitKeyShortCircuitsWithoutTouchingFeatures() {
        long userId = USER_BASE + 5;
        String device = "it-p92-skip-device";
        String ip = "203.0.113.78";
        purge(userId, device, ip);
        // 走生产同一条写路径（而不是手搓一个 SET）——手工写出来的键形态可能与应用写的
        // 不是一回事，那正是本模块反复出问题的地方
        limitPort.markLimited(userId);

        Set<String> before = riskKeys();
        RiskPreCheckResult result = productionUseCase.preCheck(context(userId, device, ip));

        assertEquals(Set.of(), addedSince(before),
                "短路路径不该产生任何特征键（用户窗口、设备聚集、IP 都不该被创建）");
        assertEquals(RiskAction.LIMIT, result.effectiveAction());
        assertEquals(List.of(RiskRuleId.LIMIT_COOLDOWN), result.decision().matchedRules());
        assertEquals(ErrorCode.RISK_LIMITED, result.errorCode());
    }

    @Test
    @DisplayName("短路路径仍然续写限频键——冷却期是滑动的（F-16）")
    void shortCircuitRefreshesTheCooldown() {
        long userId = USER_BASE + 6;
        purge(userId, null, null);
        limitPort.markLimited(userId);

        // 把 TTL 压到 3 秒。若短路路径不续写，这次调用之后它只会更短；
        // 续写了的话会被重新拉到冷却时长附近，两个读数在数值上分得很开。
        redis.expire(RiskKeys.limitKey(userId), Duration.ofSeconds(3));

        productionUseCase.preCheck(context(userId, null, null));

        Long ttl = redis.getExpire(RiskKeys.limitKey(userId));
        assertNotNull(ttl);
        assertTrue(ttl > 3L, "短路路径没有续写限频键，TTL 停留在 " + ttl + " 秒");
        assertTrue(ttl <= props.limitCooldownMs() / 1000L, "TTL 超过了配置的冷却时长：" + ttl);
    }

    @Test
    @DisplayName("ENFORCE 下连续请求触发 LIMIT：写入限频键，TTL 等于冷却时长，随后短路")
    void limitIsWrittenWithTheConfiguredTtl() {
        long userId = USER_BASE + 7;
        purge(userId, null, null);

        RiskPreCheckResult burst = requestUntilShortWindowFires(productionUseCase, userId, null, null);
        assertEquals(RiskAction.LIMIT, burst.effectiveAction());

        String limitKey = RiskKeys.limitKey(userId);
        assertTrue(redis.hasKey(limitKey), "LIMIT 生效了却没写限频键，下一次请求不会被限频");

        Long ttl = redis.getExpire(limitKey);
        assertNotNull(ttl);
        // 上界同时守着「TTL 不是某个写死的值」：适配器若忽略配置，这里会读到那个值
        assertTrue(ttl > 0L && ttl <= props.limitCooldownMs() / 1000L,
                "TTL 应落在冷却时长（" + props.limitCooldownMs() + "ms）附近，实际 " + ttl + " 秒");

        // 读侧闭环：冷却期内再来一次，这次是读到了键才短路的。
        // 上一条用例的键是手工写进去的，这一条走的是「写进去 → 再读到」的完整路径。
        RiskPreCheckResult next = productionUseCase.preCheck(context(userId, null, null, "burst-after"));
        assertEquals(List.of(RiskRuleId.LIMIT_COOLDOWN), next.decision().matchedRules());
        assertEquals(RiskAction.LIMIT, next.effectiveAction());
    }

    // ── SHADOW ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SHADOW 下照常采集与决策，但不碰限频键")
    void shadowComputesTheDecisionButTouchesNoLimitKey() {
        long userId = USER_BASE + 8;
        purge(userId, null, null);
        // 模式是同一个类的构造参数，所以这里手工构造；端口、阈值、策略仍是 Spring 里的真货
        RiskPreCheckUseCase shadow = new RiskPreCheckUseCase(
                featurePort, blacklistPort, limitPort, rules,
                new RiskPolicy(props.enabled(), RiskMode.SHADOW,
                        props.failOpen(), props.deadlineMs()));

        Set<String> before = riskKeys();
        RiskPreCheckResult burst = requestUntilShortWindowFires(shadow, userId, null, null);
        Set<String> added = addedSince(before);

        // 决策是真算的：SHADOW 阶段唯一的产出就是真实流量的决策分布，
        // 全返回 PASS 等于把「先观察再切 ENFORCE」的依据丢掉
        assertEquals(RiskAction.LIMIT, burst.decision().action());
        assertEquals(RiskAction.LIMIT_MIN, burst.decision().riskScore());
        // 但业务结果不变
        assertEquals(RiskAction.PASS, burst.effectiveAction());
        assertEquals(ErrorCode.OK, burst.errorCode());

        // 采集照常——不先钉住这一条，「没写限频键」可能只是「什么都没跑」
        assertTrue(redis.hasKey(RiskKeys.userWindow(userId, props.userWindow().shortSeconds())),
                "SHADOW 必须照常采集，否则本用例证明不了任何事");
        // 限频键连读都不发生（读不留下痕迹，那一半由 §7.1 的 journal 断言守着），
        // 这里能观测的是有破坏性的另一半：不写。
        assertTrue(added.stream().noneMatch(key -> key.startsWith("risk:limit:")),
                "SHADOW 下出现了限频键：" + added);
    }

    // ── 累加 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("多规则累加：用户窗口越线 + 设备聚集越线 → BLOCK")
    void multipleRulesAccumulate() {
        long userId = USER_BASE + 9;
        String device = "it-p92-multi-device";
        purge(userId, device, null);

        // 同一设备关联 threshold+1 个账号。对方本人再打一次，设备集合就会越过阈值。
        for (int i = 0; i <= props.deviceWindow().threshold(); i++) {
            featurePort.trackCluster(USER_BASE + 100 + i, device, null);
        }

        RiskPreCheckResult burst = requestUntilShortWindowFires(productionUseCase, userId, device, null);

        assertEquals(RiskAction.BLOCK, burst.effectiveAction());
        // 顺序是 RiskRuleId 的声明序，不是命中顺序：两条分数（30 + 80）累加而不是取最高
        assertEquals(List.of(RiskRuleId.USER_WINDOW_SHORT, RiskRuleId.DEVICE_ACCOUNTS_HIGH),
                burst.decision().matchedRules());
        assertEquals(RiskAction.LIMIT_MIN + RiskAction.BLOCK_MIN, burst.decision().riskScore());
        // 命中了 USER_WINDOW_SHORT，但动作是 BLOCK 不是 LIMIT——第 6 步的条件是
        // 「动作恰为 LIMIT」，不是「命中了限频那条规则」
        assertFalse(redis.hasKey(RiskKeys.limitKey(userId)), "动作是 BLOCK，不该写限频键");

        System.out.printf("[IT-RISK-PRECHECK] 累加场景 score=%d action=%s matched=%s%n",
                burst.decision().riskScore(), burst.decision().action(), burst.decision().matchedRules());
    }
}
