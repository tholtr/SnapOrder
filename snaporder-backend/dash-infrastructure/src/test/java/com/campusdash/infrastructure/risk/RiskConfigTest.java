package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.RiskMode;
import com.campusdash.domain.risk.model.RiskPolicy;
import com.campusdash.domain.risk.model.RiskRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配层的守卫：配置项 → 领域规则对象的映射。
 *
 * <p>这个类存在的唯一理由是 {@code RiskRules} 的构造函数是 **7 个 int 挨在一起的位置参数**。
 * 把「60 秒窗口阈值」和「设备低阈值」写反了，编译器一声不吭，所有规则从此判在错误的
 * 数值上——而规则的命中与否又不会报错，只会表现为「风控好像没生效」或者「误伤变多了」。
 *
 * <p><b>所以下面这组配置值是两两不等的</b>，并且逐个字段断言。若图省事把它们配成同值
 * （比如都用 5），任何位置错位都测不出来——同值不具区分能力，P9.1 的 F-5 已经踩过一次。
 * 校验的取值域约束在这里也要满足：低阈值 1 ≤ 33 &lt; 高阈值 44。
 */
@DisplayName("风控装配")
class RiskConfigTest {

    private static final int SHORT_THRESHOLD = 11;
    private static final int LONG_THRESHOLD = 22;
    private static final int DEVICE_LOW = 33;
    private static final int DEVICE_HIGH = 44;
    private static final int IP_ACCOUNT = 55;
    private static final int IP_RATE = 66;
    private static final int COOLDOWN_MS = 77;
    private static final String RULE_VERSION = "88";

    /**
     * P9.4 新增的两个组件。它们**不流入这个类断言的两个对象**：{@link RiskRules}
     * 与 {@link RiskPolicy} 都与聚合开关、实例标识无关，这里补上只是为了满足构造器。
     *
     * <p>取值仍然刻意分开（{@code false} 对 {@code ENABLED} 的 {@code true}，
     * 字面量也不同于 {@code "test-secret"} / {@code "88"}）——万一日后有人把它们
     * 接进规则或策略对象，同值的实参没有任何区分能力，位置写错依旧测不出来。
     */
    private static final boolean EVENT_AGGREGATION = false;
    private static final String INSTANCE_ID = "cfg-test-instance";

    /**
     * 策略四项。{@code ENABLED} 与 {@code FAIL_OPEN} 取**相反**的值——它们是
     * {@link RiskPolicy} 里仅有的两个 boolean，位置是第 1 与第 3 个参数，
     * 写反了编译器不报错；取同值的话任何错位都测不出来（P9.1 的 F-5）。
     */
    private static final boolean ENABLED = true;
    private static final boolean FAIL_OPEN = false;
    private static final int DEADLINE_MS = 99;

    private static RiskProperties props(RiskMode mode) {
        return new RiskProperties(
                ENABLED,
                "test-secret",
                mode,
                FAIL_OPEN,
                DEADLINE_MS,
                COOLDOWN_MS,
                RULE_VERSION,
                new RiskProperties.UserWindow(10, SHORT_THRESHOLD, 60, LONG_THRESHOLD),
                new RiskProperties.DeviceWindow(600, DEVICE_HIGH, DEVICE_LOW),
                new RiskProperties.IpAccountWindow(600, IP_ACCOUNT),
                new RiskProperties.IpRateWindow(60, IP_RATE),
                EVENT_AGGREGATION, INSTANCE_ID);
    }

    @Test
    @DisplayName("七个阈值与版本号各自落到正确的字段上，一个不串位")
    void everyThresholdLandsInItsOwnField() {
        RiskRules rules = new RiskConfig().riskRules(props(RiskMode.SHADOW));

        assertEquals(SHORT_THRESHOLD, rules.shortThreshold(), "shortThreshold");
        assertEquals(LONG_THRESHOLD, rules.longThreshold(), "longThreshold");
        assertEquals(DEVICE_LOW, rules.deviceLowThreshold(), "deviceLowThreshold");
        assertEquals(DEVICE_HIGH, rules.deviceHighThreshold(), "deviceHighThreshold");
        assertEquals(IP_ACCOUNT, rules.ipAccountThreshold(), "ipAccountThreshold");
        assertEquals(IP_RATE, rules.ipRateThreshold(), "ipRateThreshold");
        assertEquals(COOLDOWN_MS, rules.limitCooldownMs(), "limitCooldownMs");
        assertEquals(RULE_VERSION, rules.ruleVersion(), "ruleVersion");
    }

    @Test
    @DisplayName("设备低阈值取的是 lowThreshold，不是 threshold")
    void deviceLowAndHighAreNotSwapped() {
        // 单独再钉一遍这一对：它们的名字里都带 threshold，最容易被顺手写反，
        // 而写反之后低档与高档会互换——Review 变成 Block，误伤直接上一个等级。
        RiskRules rules = new RiskConfig().riskRules(props(RiskMode.SHADOW));

        assertEquals(DEVICE_LOW, rules.deviceLowThreshold());
        assertEquals(DEVICE_HIGH, rules.deviceHighThreshold());
    }

    @Test
    @DisplayName("策略四项各自落到正确的字段上，一个不串位")
    void policyFieldsDoNotGetSwapped() {
        // 四项的类型/含义各不相同，位置写错编译器只拦得住 mode 与 deadlineMs
        // （枚举与 int 不可互换）；enabled 与 failOpen 写反是完全静默的。
        // 所以这两个必须取相反值，否则这条断言没有区分能力。
        RiskPolicy policy = new RiskConfig().riskPolicy(props(RiskMode.ENFORCE));

        assertTrue(policy.enabled(), "enabled");
        assertEquals(RiskMode.ENFORCE, policy.mode(), "mode");
        assertFalse(policy.failOpen(), "failOpen");
        assertEquals(DEADLINE_MS, policy.deadlineMs(), "deadlineMs");
    }

    @Test
    @DisplayName("模式原样透传，不做任何解释——SHADOW 与 ENFORCE 各自成为策略里的那一个")
    void modeIsPassedThrough() {
        RiskConfig config = new RiskConfig();

        assertEquals(RiskMode.SHADOW, config.riskPolicy(props(RiskMode.SHADOW)).mode());
        assertEquals(RiskMode.ENFORCE, config.riskPolicy(props(RiskMode.ENFORCE)).mode());
    }

    @Test
    @DisplayName("规则版本原样进规则对象——它是字符串不是数字，前导零不能被吞掉")
    void ruleVersionKeepsItsLiteralForm() {
        // 版本号是配置里的字符串。写成 "007" 就该原样出现在日志里；
        // 若哪天有人把它改成 int，前导零会被吞掉，离线分析按版本分组时
        // 会把 "007" 和 "7" 归成同一批。
        RiskProperties p = new RiskProperties(
                ENABLED, "test-secret", RiskMode.SHADOW, FAIL_OPEN, DEADLINE_MS,
                COOLDOWN_MS, "007",
                new RiskProperties.UserWindow(10, SHORT_THRESHOLD, 60, LONG_THRESHOLD),
                new RiskProperties.DeviceWindow(600, DEVICE_HIGH, DEVICE_LOW),
                new RiskProperties.IpAccountWindow(600, IP_ACCOUNT),
                new RiskProperties.IpRateWindow(60, IP_RATE),
                EVENT_AGGREGATION, INSTANCE_ID);

        assertEquals("007", new RiskConfig().riskRules(p).ruleVersion());
    }
}
