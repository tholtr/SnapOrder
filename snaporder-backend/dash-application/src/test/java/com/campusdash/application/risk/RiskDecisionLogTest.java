package com.campusdash.application.risk;

import com.campusdash.shared.LogField;
import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskDecision;
import com.campusdash.domain.risk.model.RiskRuleId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这一行是给离线分析脚本读的，字段名和顺序等于协议。
 * 本类的作用是把协议锁死——改格式必须同时改这里，改不动就说明那是破坏性变更。
 *
 * <p>与 {@code RiskFeatureLogTest} 的分工：那边锁 {@code risk_feature} 行，
 * 这边锁 {@code risk_decision} 行。两行共用 {@link LogField} 的净化逻辑，
 * 但**分开测**——共用一个实现不等于共用一套断言，净化逻辑本身该由两条日志各自验证一遍
 * （万一有人把它从一条日志的调用路径上摘掉，只有对应的那条会红）。
 *
 * <p>{@code eventId} 有真实值与 {@code null} 两种形态（见 {@code RiskDecisionLog} 的
 * 类注释）。绝大多数用例只关心别的字段，所以传 {@link #NO_EVENT}；这一位本身的两种
 * 渲染由 {@code eventIdIsRenderedWhenPresent} 与 {@code noEventRendersAsDash} 分别锁。
 *
 * <p>这里验的是**调用路径**（这一行确实过了净化），净化器自己的边界
 * （快路径、{@code U+2028}/{@code U+2029}、纯空白值）由 {@code dash-shared} 的
 * {@code LogFieldTest} 验。两者不重复，别把其中一边删掉。
 */
@DisplayName("风控决策日志行")
class RiskDecisionLogTest {

    private static final String RULE_VERSION = "1";
    private static final long LATENCY_MS = 3L;

    /**
     * 「这次决策没有产生风险事件」，渲染成 {@code -}。
     *
     * <p>具名而不是在调用点写裸 {@code null}：一个夹在参数中间的 {@code null}
     * 读起来像是漏填，而这个位置恰恰有一个明确的语义。
     */
    private static final String NO_EVENT = null;

    /**
     * 分数与动作必须自洽——{@code RiskDecision} 的紧凑构造器会拒绝不自洽的组合。
     * 用 {@link RiskAction#ofScore} 推动作而不是手写枚举，是为了让本测试只关心日志格式，
     * 不必跟着档位表一起改（档位表由 {@code RiskActionTest} 锁）。
     */
    private static RiskDecision decision(int score, List<RiskRuleId> rules, List<DegradeReason> reasons) {
        return new RiskDecision(score, RiskAction.ofScore(score), rules, RULE_VERSION, reasons);
    }

    @Test
    @DisplayName("整行逐字匹配设计 §5.2 的格式")
    void matchesSpecExactly() {
        // 用真实的累加场景：10s 窗口 30 分 + 60s 窗口 60 分 = 90 → BLOCK
        RiskDecision decision = decision(90,
                List.of(RiskRuleId.USER_WINDOW_SHORT, RiskRuleId.USER_WINDOW_LONG),
                List.of());

        String line = RiskDecisionLog.format(2001L, 3003L, "req-abc", decision, NO_EVENT, LATENCY_MS);

        assertEquals("risk_decision userId=2001 errandId=3003 requestId=req-abc"
                + " action=BLOCK riskScore=90"
                + " matchedRules=USER_WINDOW_SHORT|USER_WINDOW_LONG ruleVersion=1"
                + " degraded=false degradeReasons=- eventId=- decisionLatencyMs=3", line);
    }

    @Test
    @DisplayName("十一个字段都在，且顺序固定")
    void fieldOrderIsStable() {
        // 顺序不是审美问题：分析脚本若按位置切分，换序会静默产出错指标。
        String[] expected = {"userId=", "errandId=", "requestId=", "action=", "riskScore=",
                "matchedRules=", "ruleVersion=", "degraded=", "degradeReasons=",
                "eventId=", "decisionLatencyMs="};

        String line = RiskDecisionLog.format(1L, 2L, "r",
                decision(0, List.of(), List.of()), NO_EVENT, 0L);

        int cursor = -1;
        for (String field : expected) {
            int at = line.indexOf(field);
            assertTrue(at > cursor, "字段 " + field + " 缺失或顺序不对：" + line);
            cursor = at;
        }
    }

    @Test
    @DisplayName("整行只有一行——没有换行")
    void alwaysSingleLine() {
        String line = RiskDecisionLog.format(1L, 2L, "r", decision(0, List.of(), List.of()), NO_EVENT, 0L);

        assertFalse(line.contains("\n"));
    }

    @Test
    @DisplayName("PASS 且无命中规则时，两个集合字段渲染为 -")
    void emptyCollectionsRenderAsDash() {
        String line = RiskDecisionLog.format(1L, 2L, "r", decision(0, List.of(), List.of()), NO_EVENT, 0L);

        assertTrue(line.contains("matchedRules=-"), line);
        assertTrue(line.contains("degradeReasons=-"), line);
        // 分数为 0 也不许被美化：0 是「评估过了，没命中」，不是「没评估」
        assertTrue(line.contains("riskScore=0"), line);
    }

    @Test
    @DisplayName("规则按枚举声明序渲染，与传入顺序无关")
    void rulesAreRenderedInDeclarationOrder() {
        // 故意倒着传：日志行的稳定性不能依赖上游的产出顺序（P9.3/P9.4 会引入别的构造路径）。
        RiskDecision scrambled = decision(90,
                List.of(RiskRuleId.USER_WINDOW_LONG, RiskRuleId.USER_WINDOW_SHORT),
                List.of());

        String line = RiskDecisionLog.format(1L, 2L, "r", scrambled, NO_EVENT, LATENCY_MS);

        assertTrue(line.contains("matchedRules=USER_WINDOW_SHORT|USER_WINDOW_LONG"), line);
    }

    @Test
    @DisplayName("降级原因按声明序、以 | 连接，且 degraded 跟着翻成 true")
    void degradeReasonsAreRenderedInDeclarationOrder() {
        RiskDecision degraded = decision(0, List.of(),
                List.of(DegradeReason.LIMIT_READ_FAILED, DegradeReason.BLACKLIST_FAILED));

        String line = RiskDecisionLog.format(1L, 2L, "r", degraded, NO_EVENT, LATENCY_MS);

        assertTrue(line.contains(
                "degradeReasons=BLACKLIST_FAILED|LIMIT_READ_FAILED"), line);
        assertTrue(line.contains("degraded=true"), line);
    }

    @Test
    @DisplayName("degraded 是降级原因的派生值，不是独立的输入")
    void degradedIsDerivedNotIndependent() {
        // 「降级了」与「有降级原因」在任何一份日志里都必须同真同假。
        // 若哪天有人把 degraded 做成独立字段，就会出现 degraded=true 而原因列是 - 的行，
        // 离线脚本无从判断到底哪一步失败了。
        String clean = RiskDecisionLog.format(1L, 2L, "r", decision(0, List.of(), List.of()), NO_EVENT, 0L);
        String broken = RiskDecisionLog.format(1L, 2L, "r",
                decision(0, List.of(), List.of(DegradeReason.CLUSTER_FAILED)), NO_EVENT, 0L);

        assertTrue(clean.contains("degraded=false degradeReasons=-"), clean);
        assertTrue(broken.contains("degraded=true degradeReasons=CLUSTER_FAILED"), broken);
    }

    @Test
    @DisplayName("requestId 里的控制字符被替换，无法伪造出一行新日志")
    void controlCharactersCannotForgeRequestId() {
        // 消费方是离线分析脚本，一条伪造的 risk_decision 就能污染指标。
        String forged = "abc\nrisk_decision userId=999 action=PASS";

        String line = RiskDecisionLog.format(1L, 2L, forged, decision(0, List.of(), List.of()), NO_EVENT, 0L);

        assertFalse(line.contains("\n"), "换行必须被消除：" + line);
        assertEquals(1, line.lines().count());
        // 内容仍在，只是不再具备「另起一行」的能力
        assertTrue(line.contains("userId=999"), line);
    }

    @Test
    @DisplayName("ruleVersion 同样被净化——它来自配置，也是外部输入")
    void controlCharactersCannotForgeRuleVersion() {
        // 配置里塞一个带换行的版本号同样能把一行劈成两行。
        // 这不是假想：ruleVersion 是给人手改的字符串，而 yaml 的多行标量语法
        // （| 和 >）能让换行在配置里无声地进来。
        RiskDecision decision = new RiskDecision(0, RiskAction.PASS, List.of(), "1\nrisk_decision userId=999", List.of());

        String line = RiskDecisionLog.format(1L, 2L, "r", decision, NO_EVENT, 0L);

        assertFalse(line.contains("\n"), "换行必须被消除：" + line);
        assertEquals(1, line.lines().count());
        // P9.4 D13 之后值里的空格也变下划线，所以这里逐字钉的是
        // ruleVersion=1_risk_decision_userId=999 —— 那一整串是**一个 token**。
        assertTrue(line.contains("ruleVersion=1_risk_decision_userId=999"), line);
        assertFalse(List.of(line.split(" ")).contains("userId=999"), line);
    }

    @Test
    @DisplayName("空白被中性化——带空格的 requestId 伪造不出一个字段（P9.4 D13）")
    void whitespaceCannotForgeFields() {
        // 这一行是空格分隔的 key=value，而 requestId 来自客户端的 X-Request-Id 头，
        // 它**可以带空格**（Tomcat 拒绝裸 CR/LF，但不拒绝空格——挡控制字符那套论证
        // 在这里不成立）。不中性化的话，下面这个值会渲染出第二个 action=，
        // 而脚本按空格切分取到的将是客户端写进去的那个。
        String line = RiskDecisionLog.format(1L, 2L, "x action=BLOCK",
                decision(0, List.of(), List.of()), NO_EVENT, 0L);

        assertTrue(line.contains("requestId=x_action=BLOCK"), line);
        // 关键断言：伪造的那段还在（内容不丢），但不再是独立的 token，切分取不到它
        assertFalse(List.of(line.split(" ")).contains("action=BLOCK"), line);
    }

    @Test
    @DisplayName("U+2028 / U+2029 同样被中性化——它们不是 ISO 控制字符，但会被当成换行")
    void unicodeLineSeparatorsAreNeutralizedToo() {
        // 这条钉的是净化范围里 Character.isWhitespace 那一档（而不是字面的「ASCII 空格」）：
        // isISOControl 放行 U+2028，而 Python 的 str.splitlines() 把它当换行，
        // 于是一个客户端可控的值就能凭空造出一整行——正是本节要堵的东西。
        String line = RiskDecisionLog.format(1L, 2L, "a\u2028b\u2029c",
                decision(0, List.of(), List.of()), NO_EVENT, 0L);

        assertFalse(line.contains("\u2028"), "U+2028 必须被消除：" + line);
        assertFalse(line.contains("\u2029"), "U+2029 必须被消除：" + line);
        assertTrue(line.contains("requestId=a_b_c"), line);
    }

    @Test
    @DisplayName("制表符、回车等也一并替换")
    void otherControlCharactersReplaced() {
        String line = RiskDecisionLog.format(1L, 2L, "a\r\n\tb", decision(0, List.of(), List.of()), NO_EVENT, 0L);

        assertFalse(line.contains("\r"));
        assertFalse(line.contains("\t"));
        assertTrue(line.contains("requestId=a___b"), line);
    }

    @Test
    @DisplayName("requestId 为 null 或空时占位，不打印 null")
    void emptyRequestIdPlaceholder() {
        // 打印字面量 "null" 会让分析脚本把它当成一个真实的 requestId 值去聚合。
        RiskDecision decision = decision(0, List.of(), List.of());

        assertTrue(RiskDecisionLog.format(1L, 2L, null, decision, NO_EVENT, 0L).contains("requestId=-"));
        assertTrue(RiskDecisionLog.format(1L, 2L, "", decision, NO_EVENT, 0L).contains("requestId=-"));
    }

    @Test
    @DisplayName("本行不含任何特征计数——短路路径根本没采集")
    void neverCarriesFeatureCounts() {
        // 「未采集」与「采集到 0」是两回事：命中限频键时短路返回，此时若打
        // count10s=0，离线分析会把一个正在被限频的用户读成「安安静静没请求过」。
        // 这条断言把「这行没有计数」从一个约定变成事实。
        String line = RiskDecisionLog.format(1L, 2L, "r",
                decision(30, List.of(RiskRuleId.LIMIT_COOLDOWN), List.of()), NO_EVENT, LATENCY_MS);

        for (String absent : List.of("count10s=", "count60s=", "deviceAccounts=",
                "ipAccounts=", "ipRequests=")) {
            assertFalse(line.contains(absent), "不该出现 " + absent + "：" + line);
        }
    }

    @Test
    @DisplayName("产生了事件时，eventId 是分配到的那个 id——四处日志靠它对上")
    void eventIdIsRenderedWhenPresent() {
        // 这是本行与 risk_event_publish 行、事件载荷、risk_event_processed 行之间**唯一**
        // 的凭据：四处的 eventId 相同，一条决策的轨迹才拼得起来，对不上没有第二条线索能补救。
        // 所以这里逐字断言，而不是「包含某个非空值」——渲染到别的字段名上、
        // 或者挪到别的字段后面，脚本就取不到它，而这两件事都不会报错。
        String line = RiskDecisionLog.format(1L, 2L, "r",
                decision(80, List.of(RiskRuleId.BLACKLIST_USER), List.of()),
                "0f8c1e2a-7b3d-4c5e-9a1b-2c3d4e5f6a7b", LATENCY_MS);

        assertTrue(line.contains(" eventId=0f8c1e2a-7b3d-4c5e-9a1b-2c3d4e5f6a7b"
                + " decisionLatencyMs=3"), line);
    }

    @Test
    @DisplayName("没有事件时 eventId 渲染成 -，不是字面量 null")
    void noEventRendersAsDash() {
        // producesEvent() 为假（普通 PASS、或风控关闭）时没有任何事件可引用。
        // 渲染成 - 是**如实反映**：离线脚本见到 eventId=- 就知道这一行本来就不该有事件，
        // 于是「事件数比决策数少」在它那里是可解释的，不必去查是不是投递丢了。
        // 打印字面量 null 则会让脚本拿它当一个真实的 id 去关联。
        String line = RiskDecisionLog.format(1L, 2L, "r",
                decision(0, List.of(), List.of()), NO_EVENT, LATENCY_MS);

        assertTrue(line.contains("eventId=- decisionLatencyMs=3"), line);
        assertFalse(line.contains("eventId=null"), line);
    }

    @Test
    @DisplayName("耗时 0 照打 0，不渲染成占位符")
    void zeroLatencyIsNotAPlaceholder() {
        // enabled=false 的快路径不碰任何 I/O，耗时确实可能取到 0。
        // 若把 0 渲染成 -，就分不出「快得测不出来」和「这个字段没填」。
        String line = RiskDecisionLog.format(1L, 2L, "r", decision(0, List.of(), List.of()), NO_EVENT, 0L);

        assertTrue(line.endsWith("decisionLatencyMs=0"), line);
    }

    @Test
    @DisplayName("本行不含原始设备号与 IP——它们在类型层面就进不来")
    void neverCarriesRawDeviceOrIp() {
        // RiskDecision 里没有 deviceId/clientIp 字段，所以 format 根本无从打印它们。
        // 这条断言是给「以后有人想往风险决策里加原始维度」的人看的：
        // 加了之后这里会红，提醒他 P9.1 的 D1 禁止把原始值写进日志。
        String line = RiskDecisionLog.format(1L, 2L, "r",
                decision(80, List.of(RiskRuleId.BLACKLIST_DEVICE, RiskRuleId.BLACKLIST_IP), List.of()),
                NO_EVENT, LATENCY_MS);

        assertFalse(line.contains("deviceId="), line);
        assertFalse(line.contains("clientIp="), line);
        // 但「命中了设备/手机号黑名单」这件事必须看得见，否则拦截原因无法复盘
        assertTrue(line.contains("matchedRules=BLACKLIST_DEVICE|BLACKLIST_IP"), line);
    }
}
