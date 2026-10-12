package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.RiskPolicy;
import com.campusdash.domain.risk.model.RiskRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 风控的 Spring 装配。
 *
 * <p>{@link DeviceHasher} 本身刻意不带任何 Spring 注解（见该类注释）：
 * dash-bench 的 BlacklistKeyTool 要直接 {@code new DeviceHasher(secret)} 复用它，
 * 一旦类上出现 Spring 类型，工具就得拖进整个 Spring 上下文。
 * 所以装配放在这里——「类保持纯净、装配集中一处」。
 *
 * <p>密钥来自 {@link RiskProperties}，而它已在绑定期校验过非空，
 * 因此这里不再重复判空；{@code DeviceHasher} 自己还有一层校验兜底
 * （工具不走 Spring，那一层不能省）。
 *
 * <p><b>为什么配置要以领域类型交出去</b>：{@link RiskProperties} 在
 * {@code dash-infrastructure}，而消费它的用例在 {@code dash-application}——
 * application 看不见 infrastructure，两个模块之间只有 {@code dash-domain}
 * 是公共词汇。所以阈值、模式、开关都必须先转成 {@link RiskRules} / {@link RiskPolicy}
 * 这些领域类型，用例才拿得到。
 */
@Configuration
public class RiskConfig {

    @Bean
    public DeviceHasher deviceHasher(RiskProperties properties) {
        return new DeviceHasher(properties.deviceHmacSecret());
    }

    /**
     * 把配置阈值转成领域规则对象。
     *
     * <p><b>下面这一串是 7 个 int 挨着的位置参数，顺序写错编译器不会报错</b>——
     * 「用户窗口阈值」和「IP 速率阈值」互换了照样能跑，只是所有规则都判在错误的
     * 数值上。<b>所以 {@code RiskConfigTest} 用的是一组两两不等的值</b>，
     * 并且逐个字段断言；若图省事配成同值，这类错误就没有任何区分能力（P9.1 的 F-5）。
     *
     * <p>{@code limitCooldownMs} 在 {@code RiskRules.evaluate} 里用不到（TTL 由
     * {@link RedisRiskLimitAdapter} 直接取配置），它在这里只是「当前规则版本的
     * 参数快照」的一部分。
     */
    @Bean
    public RiskRules riskRules(RiskProperties properties) {
        return new RiskRules(
                properties.userWindow().shortThreshold(),
                properties.userWindow().longThreshold(),
                properties.deviceWindow().lowThreshold(),
                properties.deviceWindow().threshold(),
                properties.ipAccountWindow().threshold(),
                properties.ipRateWindow().threshold(),
                properties.limitCooldownMs(),
                properties.ruleVersion());
    }

    /**
     * 运行策略。四个字段全部来自配置，一次透传成领域值对象。
     *
     * <p>它取代了 P9.2 的 {@code RiskMode} 单值 bean（P9.3 决策 D10）：读 {@code enabled}
     * 的不只是用例，还有 presentation 的 {@code RiskContextInterceptor}——而它只依赖
     * application、看不见 {@code RiskProperties}，原先只能自己再 {@code @Value} 读一遍。
     * 同一个 key 于是有两处默认值，只能靠注释维系一致（F-17）。收成一个 bean 之后，
     * 「关掉风控」只剩一个真值来源。
     *
     * <p>{@code RiskConfigTest} 对这四项用的是**两两不等**的值并逐个字段断言，
     * 理由同 {@link #riskRules}：位置参数写错编译器不报错，同值又不具区分能力。
     */
    @Bean
    public RiskPolicy riskPolicy(RiskProperties properties) {
        return new RiskPolicy(properties.enabled(), properties.mode(),
                properties.failOpen(), properties.deadlineMs());
    }
}
