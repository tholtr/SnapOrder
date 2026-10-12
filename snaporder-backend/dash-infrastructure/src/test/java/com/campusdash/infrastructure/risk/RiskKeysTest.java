package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.RiskEventGateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 键名的**逐字**契约。这里断言的是字符串字面量本身，而不是「A 等于 B」那种同义反复——
 * 因为这些键名同时被应用、运维文档和 {@code redis-cli} 使用，改一个字符不会有任何报错，
 * 只会让运维手动写入的键永远查不到。
 *
 * <p>黑名单三个键的哈希校验与格式由 {@code dash-bench} 的
 * {@code BlacklistKeyToolTest} 覆盖（它验的是「工具算出的键与应用查的键逐字节相同」），
 * 这里不重复，只补 P9.2 新增的部分和那处**刻意的不对称**。
 */
@DisplayName("风控 Redis 键")
class RiskKeysTest {

    @Test
    @DisplayName("限频键带花括号，与蓝图 §3.6 逐字一致")
    void limitKeyMatchesTheBlueprintLiteral() {
        // 花括号在这里是真字符（Redis Cluster 的 hash tag），不是占位符记号。
        // 保留它的理由是**文档一致性**：蓝图 §3.6 与实施计划 §6.2 都逐字写着
        // risk:limit:user:{userId}，运维会照着文档敲 redis-cli 去手动解除限频。
        // 键名与文档不一致时，敲出来的键应用永远读不到——限频看起来配好了却毫无作用。
        assertEquals("risk:limit:user:{1001}", RiskKeys.limitKey(1001L));
    }

    @Test
    @DisplayName("黑名单用户键**不带**花括号——这处不对称是刻意的")
    void blacklistUserKeyHasNoBraces() {
        // P9.1 交付的 BlacklistKeyTool 与运维操作已经把这个写法固化下来了，
        // 改它等于让已经写进 Redis 的黑名单全部失联。
        // 有人想「统一两个用户级键的写法」时，这条会先红。
        assertEquals("risk:bl:user:1001", RiskKeys.blacklistUser(1001L));
    }

    @Test
    @DisplayName("两个用户级键互不相同，不会互相覆盖")
    void userScopedKeysAreDistinct() {
        // 限频键与黑名单键都以 userId 结尾、都带 risk: 前缀，肉眼看容易混。
        // 真混了后果很重：一个用户被 LIMIT 十秒，会顺手把他拉进永久黑名单。
        assertNotEquals(RiskKeys.limitKey(1001L), RiskKeys.blacklistUser(1001L));
    }

    @Test
    @DisplayName("限频键按用户区分，不会把所有人限在同一个键上")
    void limitKeyVariesByUser() {
        assertNotEquals(RiskKeys.limitKey(1001L), RiskKeys.limitKey(1002L));
    }

    // ── P9.4 D4：四类事件聚合闸门键 ────────────────────────────────────────────

    @Test
    @DisplayName("三个用户级闸门键逐字匹配 D4——花括号是真字符，且**不带时间桶**")
    void userGateKeysMatchD4() {
        // 逐字断言而不是「A 等于 B」：这四个键名同时被应用、运维文档和 redis-cli 使用，
        // 而且 D4 恰好是**改过形状**的那一条——蓝图 §3.9 的示例多一段 :{10sBucket}。
        //
        // 花括号在这里是真字符（Redis Cluster 的 hash tag），与 limitKey 同型：
        // D4 与实施计划逐字写着这个形状，运维会照着 redis-cli 敲它去查聚合状态。
        // 少了花括号、或多了第二段，敲出来的键应用永远读不到——聚合看起来配好了却恒不生效。
        assertEquals("risk:event:gate:limit:{1001}",
                RiskKeys.eventGate(RiskEventGateType.LIMIT, 1001L));
        assertEquals("risk:event:gate:review:{1001}",
                RiskKeys.eventGate(RiskEventGateType.REVIEW, 1001L));
        assertEquals("risk:event:gate:block:{1001}",
                RiskKeys.eventGate(RiskEventGateType.BLOCK, 1001L));
    }

    @Test
    @DisplayName("降级闸门键的主体是实例标识，不是用户 id——另一个重载")
    void degradedGateKeyTakesInstanceId() {
        assertEquals("risk:event:gate:degraded:{app-1}",
                RiskKeys.eventGate(RiskEventGateType.DEGRADED, "app-1"));
    }

    @Test
    @DisplayName("四类闸门键互不相同——同一个用户四类都被放行时要各记一条")
    void gateKeysAreDistinctPerType() {
        // RiskEventGateType 的注释写过：「一个用户同一秒内可以四类都被放行，各发一条」。
        // 键撞了就会变成「四类共用一条配额」，抑制率直接算错。
        //
        // 用 HashSet 而不是 Set.of：后者遇到重复元素直接抛 IAE，失败现场只留下一句
        // 「duplicate element」而看不出是**哪两类**撞了。
        assertEquals(4, new HashSet<>(List.of(
                        RiskKeys.eventGate(RiskEventGateType.LIMIT, 1001L),
                        RiskKeys.eventGate(RiskEventGateType.REVIEW, 1001L),
                        RiskKeys.eventGate(RiskEventGateType.BLOCK, 1001L),
                        RiskKeys.eventGate(RiskEventGateType.DEGRADED, "1001"))).size(),
                "四类闸门算出了重复的键");
    }

    @Test
    @DisplayName("闸门键与限频键不共用前缀——聚合不该被冷却期影响")
    void gateKeyIsDistinctFromLimitKey() {
        // 都以 risk: 开头、都带一对花括号、都跟着一个用户 id，肉眼看容易混。
        // 真混了后果很重：一次 LIMIT 冷却期会把该用户十秒内的情报全部抑制掉。
        assertNotEquals(RiskKeys.limitKey(1001L),
                RiskKeys.eventGate(RiskEventGateType.LIMIT, 1001L));
    }

    @Test
    @DisplayName("把用户 id 传给降级闸门（或反过来）直接抛，而不是算出一个没人查的键")
    void wrongSubjectKindIsRejected() {
        // 两个重载的存在意义就在这里：主体是 long 还是 String 由闸门类型唯一决定。
        // 不做这个校验的话，`eventGate(DEGRADED, 1001L)` 会算出一个语法完全合法的键
        // ——只是降级事件永远不会写进它，聚合于是恒不生效，且没有任何报错。
        assertThrows(IllegalArgumentException.class,
                () -> RiskKeys.eventGate(RiskEventGateType.DEGRADED, 1001L));
        assertThrows(IllegalArgumentException.class,
                () -> RiskKeys.eventGate(RiskEventGateType.LIMIT, "app-1"));
    }

    @Test
    @DisplayName("实例名里的花括号被拒——hash tag 会提前闭合")
    void instanceIdWithBracesIsRejected() {
        // 这是 D4 那条「花括号是真字符」的代价。risk:event:gate:degraded:{a}b} 的
        // hash tag 是 a 而不是 a加上右花括号，两个不同的实例名可能算进同一个 slot。
        // 这种错只在上了 Cluster 之后才现形，单机下永远测不出来，所以必须在这里挡住。
        assertThrows(IllegalArgumentException.class,
                () -> RiskKeys.eventGate(RiskEventGateType.DEGRADED, "a}b"));
        assertThrows(IllegalArgumentException.class,
                () -> RiskKeys.eventGate(RiskEventGateType.DEGRADED, "a{b"));
        assertThrows(IllegalArgumentException.class,
                () -> RiskKeys.eventGate(RiskEventGateType.DEGRADED, "app 1"));
        assertThrows(IllegalArgumentException.class,
                () -> RiskKeys.eventGate(RiskEventGateType.DEGRADED, null));
    }

    @Test
    @DisplayName("包内分派入口与两个公开重载算出逐字节相同的键")
    void dispatchMatchesTheTypedOverloads() {
        // RedisRiskEventGate 只拿到一个 String 主体，靠 eventGateFor 分派。
        // 两个入口一旦漂移，症状是「闸门写进 A 键、运维查 B 键」——不报错。
        assertEquals(RiskKeys.eventGate(RiskEventGateType.BLOCK, 1001L),
                RiskKeys.eventGateFor(RiskEventGateType.BLOCK, "1001"));
        assertEquals(RiskKeys.eventGate(RiskEventGateType.DEGRADED, "app-1"),
                RiskKeys.eventGateFor(RiskEventGateType.DEGRADED, "app-1"));
    }

    @Test
    @DisplayName("包内分派入口把用户 id 归一化，前导零不会造出第二个键")
    void dispatchNormalizesUserId() {
        // 主体是 String 就带来「01001 与 1001 是不是同一个用户」这个问题。
        // 按 long 解析再渲染，答案只能是「是」——否则同一个用户会有两个闸门配额。
        assertEquals(RiskKeys.eventGate(RiskEventGateType.LIMIT, 1001L),
                RiskKeys.eventGateFor(RiskEventGateType.LIMIT, "01001"));
    }

    @Test
    @DisplayName("包内分派入口对非数字用户主体抛 IllegalArgumentException")
    void dispatchRejectsNonNumericUserSubject() {
        // NumberFormatException 是 IllegalArgumentException 的子类，与另两个入口的约定一致。
        // 不吞：调用方（RocketMqRiskEventAdapter 的 D8 路径）会把它记成 SENT_GATE_FAILED
        // 并照常发送——「闸门坏了」永远不等于「不记录」。
        assertThrows(IllegalArgumentException.class,
                () -> RiskKeys.eventGateFor(RiskEventGateType.LIMIT, "app-1"));
    }

    @Test
    @DisplayName("闸门键段名来自枚举名——枚举改名会改键名，两者不漂移")
    void gateKeySegmentComesFromTheEnumName() {
        // 不是同义反复：RiskEventGateType 的注释把「键名段与窗口同源」当成设计约束，
        // 这条是那个约束在 RiskKeys 一侧的实现锁。谁把段名写成字面量（"limit" 而不是
        // type.name()），加第五类闸门时就会漏掉一处——而这里会因为它对不上而红。
        for (RiskEventGateType type : new RiskEventGateType[]{
                RiskEventGateType.LIMIT, RiskEventGateType.REVIEW, RiskEventGateType.BLOCK}) {
            assertTrue(RiskKeys.eventGate(type, 1001L)
                            .startsWith("risk:event:gate:" + type.name().toLowerCase(Locale.ROOT) + ":{"),
                    type + " 的键名段与枚举名不一致：" + RiskKeys.eventGate(type, 1001L));
        }
    }
}
