package com.campusdash.domain.risk.model;

import java.util.List;

/**
 * 一次风控预检的完整结果（蓝图 §3.6）。
 *
 * <p><b>{@code action} 是 {@code riskScore} 的纯函数</b>（决策 D1），这条不变式在
 * 紧凑构造器里强制成立：构造一个 {@code action} 与 {@code riskScore} 对不上的
 * {@code RiskDecision} 会直接抛异常。好处是「黑名单命中却给出 {@code PASS}」
 * 「日志里 {@code action=BLOCK riskScore=0}」这类无法解释的行在结构上不可能出现——
 * 不必靠 code review 去发现。<b>唯一的构造入口应该是
 * {@code RiskRules.evaluate}</b>（以及风控关闭时的 {@link #pass(String)}）。
 *
 * <p><b>记录里没有 {@code decisionLatencyMs}</b>：蓝图 §3.6 把它列在结果字段里，
 * 但 §3.8 同时规定它「从风控用例入口计时」，即它是**调用方测量的值**，不是决策的产物。
 * 同一个 {@code RiskDecision} 在不同环境下耗时不同，放进记录会让它变成一个
 * 与决策无关却参与 {@code equals} 的字段。它落在应用层的日志行里（设计 §5.2）。
 *
 * <p><b>也没有原始 {@code deviceId} / {@code clientIp}</b>：本模块的所有日志
 * **不得包含原始设备号与 IP**（P9.1 决策 D1）。这两个值在这里本来就不存在，
 * 所以「日志泄露原始标识」在类型层面就无从发生，而不是靠每次改代码时记得住。
 *
 * @param riskScore      命中规则分值之和。黑名单规则的分数取 {@link RiskAction#BLOCK_MIN}，
 *                       所以黑名单命中时它**参与累加**而不是覆盖分数（决策 D3）
 * @param action         由 {@code riskScore} 查表得出，见 {@link RiskAction#ofScore(int)}
 * @param matchedRules   命中的规则，**按 {@link RiskRuleId} 的声明顺序**排列。
 *                       空列表表示一条都没命中（或所有证据都因降级而缺失）
 * @param ruleVersion    本次决策使用的规则版本，来自配置。阈值是配置的一部分，
 *                       同一份代码配两套阈值就是两套规则，改阈值必须同步升版本，
 *                       否则离线分析无法把改动前后的指标分开（设计 §3.3）
 * @param degradeReasons 本次决策中有哪些证据没拿到。空表示全部拿到。
 *                       **降级不等于放行**：证据少一个，决策照跑
 */
public record RiskDecision(
        int riskScore,
        RiskAction action,
        List<RiskRuleId> matchedRules,
        String ruleVersion,
        List<DegradeReason> degradeReasons) {

    /**
     * 防御性拷贝不能省。{@code matchedRules} 与 {@code degradeReasons} 擦除后是同一个
     * 类型 {@code List}，位置参数写反了**编译期看不出来**——这正是本项目最防的那类
     * 静默缺陷。把它挡在构造器之外的办法是：这两个列表只在 {@code evaluate} 内部
     * 生成，外部调用方用 {@link #pass(String)} 或完整构造器时，由这里的类型约束和
     * 下面的不变式校验兜底。
     */
    public RiskDecision {
        matchedRules = List.copyOf(matchedRules);
        degradeReasons = List.copyOf(degradeReasons);
        if (action != RiskAction.ofScore(riskScore)) {
            throw new IllegalArgumentException(
                    "风控动作与分数不一致：riskScore=" + riskScore + " 推出 "
                            + RiskAction.ofScore(riskScore) + "，但给的是 " + action + "。"
                            + "动作必须是总分的纯函数（决策 D1），"
                            + "任何绕过分数直接指定动作的写法都会让黑名单与档位表脱钩。");
        }
    }

    /**
     * 风控未启用时的结果：不做任何 I/O，直接 {@code PASS}。
     *
     * <p>{@code degradeReasons} 为空是刻意的——**「关闭」不是「降级」**。降级意味着
     * 「本该拿到却没拿到的证据」，而关闭时根本没有证据需要拿。把关闭记成降级会让
     * 降级指标失真，运维会去排查一个不存在的故障。
     *
     * @param ruleVersion 仍要带上：关闭期间产出的日志也要能和开启后的区分开
     */
    public static RiskDecision pass(String ruleVersion) {
        return new RiskDecision(0, RiskAction.PASS, List.of(), ruleVersion, List.of());
    }

    /** 本次决策是否有证据缺失。日志行里的 {@code degraded} 字段由它渲染。 */
    public boolean degraded() {
        return !degradeReasons.isEmpty();
    }

    /**
     * 这次决策是否值得发一条风险事件（P9.4 决策 D2）。
     *
     * <p><b>这是全项目唯一的触发判据</b>——不在用例里、不在适配器里，就在这个类上。
     * 放在这里的理由是它**是关于一次决策的性质**，读起来就是
     * {@code decision.producesEvent()}；写进用例则会有第二个人在第二个地方再写一遍，
     * 而两份判据漂移的表现是「有一类决策静默不再产生事件」，不报错、不告警。
     *
     * <p>两个触发条件的含义不同，都要发：
     *
     * <ul>
     *   <li>{@code action != PASS}：**挡了**。这是最常被想到的那一类。</li>
     *   <li>{@code degraded}：**没挡，但证据不全**。它同样必须发出去——蓝图的聚合闸门
     *       专门为降级留了一类（{@link RiskEventGateType#DEGRADED}），就是因为它
     *       需要被记下来。反过来，普通的 {@code PASS}（无降级）**不发**：
     *       它是绝大多数请求的结果，发出去只会把队列和离线口径一起淹掉。</li>
     * </ul>
     *
     * <p>注意「风控关闭」与「降级」的区别（见 {@link #pass(String)}）：关闭时
     * {@code action=PASS} 且 {@code degraded=false}，**两个条件都不成立、不发事件**。
     * 这是对的——风控关着的时候没有任何风险判断发生，「关着」这件事由
     * {@code risk_decision} 行的 {@code ruleVersion} 体现，不需要靠事件表达。
     */
    public boolean producesEvent() {
        return action != RiskAction.PASS || degraded();
    }

    /**
     * 附上降级原因，分数与动作**原样保留**。
     *
     * <p>为什么是事后附加而不是 {@code evaluate} 的参数：降级是「哪一步抛了异常」，
     * 只有捕获异常的那一层（用例）知道；规则层拿到的是采集结果，它看不出
     * 「计数 0」究竟是「用户真的没请求过」还是「采集挂了」。
     *
     * <p><b>分数与动作不因降级而改变</b>——这正是「降级不是放行」的实现形态：
     * 黑名单挂了但设备维度已经越线时，决策**仍然是 {@code BLOCK}**，
     * 只是在同一行日志里多出 {@code degradeReasons=BLACKLIST_FAILED}。
     * 写成「有降级就返回 PASS」会漏掉这次拦截。
     */
    public RiskDecision withDegradeReasons(List<DegradeReason> reasons) {
        return new RiskDecision(riskScore, action, matchedRules, ruleVersion, reasons);
    }
}
