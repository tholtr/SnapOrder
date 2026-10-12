package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.RiskEventGateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸门的三条性质：抢到 / 没抢到 / 失败时抛（P9.4 D4、D8）。
 *
 * <p>中间件不起也能跑：{@link FakeStringRedisTemplate} 只认一条命令，
 * 断言的是**交给 Redis 的那三个实参**，而不是 Redis 的行为。
 *
 * <p><b>TTL 在这里被逐值钉死，这不是「顺手多测一条」</b>：D5 决定了窗口不做配置项，
 * 于是本阶段的聚合用例**无法靠把窗口配成 1 秒来加速**——等一次真实窗口过期就是
 * 十几秒起的等待，四类闸门各等一次会让测试套件变成不可接受的慢。所以断言只能落在
 * 键的过期时间上。代价是「窗口真的生效了吗」这个问题没有被直接验证，
 * 换来的是它能验证**四类闸门各自的 TTL 与它自己的窗口一致**——而这正是
 * 「加第五类闸门时键名与 TTL 要一起看」那条设计约束的落点。
 */
@DisplayName("Redis 事件聚合闸门")
class RedisRiskEventGateTest {

    @Test
    @DisplayName("抢到键时返回 true，写入的是一条 SET key 1 NX PX <窗口>")
    void acquiredReturnsTrue() {
        FakeStringRedisTemplate redis = new FakeStringRedisTemplate();
        redis.nextResult = Boolean.TRUE;

        assertTrue(new RedisRiskEventGate(redis).tryAcquire(RiskEventGateType.LIMIT, "1001"));

        assertEquals("risk:event:gate:limit:{1001}", redis.lastKey);
        // 值恒为常量 "1"：闸门只回答「键在不在」，值里不承载任何信息。
        // 这里逐字断言它，是为了挡住「顺手把时间写进值里方便排障」那类改动——
        // 一旦有人开始读它，读到的就是别人从没承诺过的东西。
        assertEquals("1", redis.lastValue);
        assertEquals(1, redis.calls, "闸门多发了命令：D4 的聚合只该是一条 SET NX");
    }

    @Test
    @DisplayName("键已存在时返回 false——窗口内已经有过一条，抑制")
    void notAcquiredReturnsFalse() {
        FakeStringRedisTemplate redis = new FakeStringRedisTemplate();
        redis.nextResult = Boolean.FALSE;

        assertFalse(new RedisRiskEventGate(redis).tryAcquire(RiskEventGateType.BLOCK, "1001"));

        assertEquals("risk:event:gate:block:{1001}", redis.lastKey);
    }

    @Test
    @DisplayName("setIfAbsent 返回 null 时抛——两个方向都不该由这里猜")
    void nullResultThrows() {
        FakeStringRedisTemplate redis = new FakeStringRedisTemplate();
        redis.nextResult = null;

        // 返回 null 只在管道/事务里才可能出现，普通调用拿不到。真拿到说明调用方式变了：
        // 按「抢到」处理会让聚合静默失效，按「没抢到」处理会静默丢事件。
        // 抛出去之后调用方会记 SENT_GATE_FAILED 并照常发送（D8）——那是一个**有记录**的
        // 降级，与这里随便猜一个方向完全不同。
        assertThrows(IllegalStateException.class,
                () -> new RedisRiskEventGate(redis).tryAcquire(RiskEventGateType.REVIEW, "1001"));
    }

    @Test
    @DisplayName("四类闸门的 TTL 逐值等于各自的窗口：10 / 60 / 60 / 10 秒")
    void ttlMatchesEachGateWindow() {
        // 逐值而不是「大于 0」：这四个数字就是规则本身（「同一用户 10 秒最多一条」），
        // 一条宽松的断言对「10 被改成 30」完全没有约束力。窗口值的**语义**由
        // RiskEventGateTypeTest 钉，这里钉的是「闸门确实用了那个值」——两处都要，
        // 因为中间隔着一次方法调用，谁都可以是错的。
        assertTtl(RiskEventGateType.LIMIT, "1001", Duration.ofSeconds(10));
        assertTtl(RiskEventGateType.REVIEW, "1001", Duration.ofSeconds(60));
        assertTtl(RiskEventGateType.BLOCK, "1001", Duration.ofSeconds(60));
        assertTtl(RiskEventGateType.DEGRADED, "app-1", Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("降级闸门的主体是实例标识，不是用户 id")
    void degradedGateTakesInstanceId() {
        FakeStringRedisTemplate redis = new FakeStringRedisTemplate();

        assertTrue(new RedisRiskEventGate(redis).tryAcquire(RiskEventGateType.DEGRADED, "app-1"));

        // 键段名与主体两处都要对：降级按实例聚合、不按用户，理由写在
        // RiskEventGateType.DEGRADED 上（按用户聚合会让一次 Redis 抖动在每个用户
        // 身上各发一条，把降级事件变成最吵的那一类）。
        assertEquals("risk:event:gate:degraded:{app-1}", redis.lastKey);
    }

    @Test
    @DisplayName("主体与闸门类型不匹配时抛，而不是算出一个没人查得到的键")
    void wrongSubjectKindPropagates() {
        FakeStringRedisTemplate redis = new FakeStringRedisTemplate();

        // 这条是 RiskKeysTest 那两条的**调用点**版本：断言异常确实会从 tryAcquire
        // 传出去、并且**没有发出任何命令**。闸门在这里吞掉异常是最坏的选择——
        // 它会变成「Redis 里什么都没有，也没人知道」，而调用方本该记一条
        // SENT_GATE_FAILED（D8）。
        assertThrows(IllegalArgumentException.class,
                () -> new RedisRiskEventGate(redis).tryAcquire(RiskEventGateType.LIMIT, "app-1"));
        assertEquals(0, redis.calls, "主体非法时不该发出任何 Redis 命令");
    }

    private static void assertTtl(RiskEventGateType type, String subject, Duration expected) {
        FakeStringRedisTemplate redis = new FakeStringRedisTemplate();
        new RedisRiskEventGate(redis).tryAcquire(type, subject);
        assertEquals(expected, redis.lastTtl, type + " 的闸门 TTL 与它自己的窗口不一致");
    }
}
