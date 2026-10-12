package com.campusdash.domain.risk.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一套风控规则：阈值 + 唯一的决策函数（P9.2 设计 §3.3）。
 *
 * <p>做成**值对象 + 纯方法**而不是一组静态常量，是为了让边界值测试能直接构造一套
 * 自定义阈值的 {@code RiskRules} 来跑，不必改配置或起 Spring 上下文。
 * 也因此它没有副作用、不碰 I/O：采集由端口负责，这里只吃采集结果。
 *
 * <p><b>决策是分数驱动的</b>（决策 D1）：{@link #evaluate} 只回答「哪些规则命中了」，
 * 分值由 {@link RiskRuleId#score()} 给出，最终动作由总分查
 * {@link RiskAction#ofScore(int)} 得出。没有任何一条规则能绕过分值直接指定动作——
 * 黑名单之所以恒为 {@code BLOCK}，是因为它值 {@link RiskAction#BLOCK_MIN} 分，
 * 而不是因为它走了一条旁路分支（决策 D3）。这样「黑名单命中却给出 {@code PASS}」
 * 在结构上不可能出现。
 *
 * <p><b>它不产出 {@code degradeReasons}</b>：降级是「证据没拿到」，而这里是
 * 「拿到证据之后怎么判」。证据缺失由调用方（{@code RiskPreCheckUseCase}）在
 * 捕获异常时记录，再用 {@link RiskDecision#withDegradeReasons(List)} 附到结果上。
 * 把降级塞进这个签名会逼着规则层去理解 Redis 故障，那是它不该知道的事。
 *
 * <p>{@code limitCooldownMs} 在 {@link #evaluate} 里用不到——适配器的 TTL 直接取
 * {@code RiskProperties}（设计 §4.2）。它留在这里是因为它属于「当前规则版本的参数」：
 * 改冷却时长必须同步升 {@code ruleVersion}，两者放在同一个对象里，
 * 版本号与它标识的那组参数就不会各说各话。
 *
 * @param shortThreshold     10 秒窗口的请求数阈值（超过即命中 {@link RiskRuleId#USER_WINDOW_SHORT}）
 * @param longThreshold      60 秒窗口的请求数阈值
 * @param deviceLowThreshold 设备关联账号数的低阈值（`REVIEW` 档）
 * @param deviceHighThreshold 设备关联账号数的高阈值（`BLOCK` 档）。
 *                           必须严格大于 {@code deviceLowThreshold}，由
 *                            {@code RiskProperties} 在启动时校验
 * @param ipAccountThreshold IP 关联账号数阈值
 * @param ipRateThreshold    60 秒内同一 IP 的请求数阈值
 * @param limitCooldownMs    限频冷却时长（毫秒）
 * @param ruleVersion        本套规则的版本号，原样写进决策结果与日志
 */
public record RiskRules(
        int shortThreshold,
        int longThreshold,
        int deviceLowThreshold,
        int deviceHighThreshold,
        int ipAccountThreshold,
        int ipRateThreshold,
        int limitCooldownMs,
        String ruleVersion) {

    /**
     * 按规则表判定本次请求，产出决策结果。
     *
     * <p><b>命中规则的追加顺序 = {@link RiskRuleId} 的声明顺序。</b>下面几段
     * {@code if} 的书写顺序就是那个顺序——不要重排，重排等于改日志协议：
     * {@code matchedRules} 是日志行的一部分，顺序不稳定会让同一次决策在不同环境下
     * 产出不同文本，离线脚本去重时会把它当成两条不同的记录。
     *
     * <p><b>同一计数取最高、不同计数累加</b>（决策 D2）：
     *
     * <ul>
     *   <li>设备的两档阈值判定的是**同一个** {@code deviceAccounts}，所以写成一个
     *       {@code else if} 链。<b>这就是「取最高」</b>——高档命中时低档必然也命中，
     *       写成两条独立判断再聚合等于对同一件事重复计分。
     *       {@code deviceAccounts = 9} 必须产出**一条**命中、80 分，而不是两条、140 分。</li>
     *   <li>用户窗口的 10 秒与 60 秒是**两个不同的计数**，各自判定后全部累加。
     *       10 秒内到 6 次、60 秒内到 31 次的用户得 30 + 60 = 90 → {@code BLOCK}，
     *       这正是最像脚本的画像。</li>
     * </ul>
     *
     * <p>注意 {@code else if} 链里**高档写在前面**：源码顺序与枚举声明顺序
     * （{@code DEVICE_ACCOUNTS_LOW} 在前）不一致，但因为两支互斥、只会追加一条，
     * 产出的列表仍然是按声明序升序的。这一点由多命中场景的顺序断言守着。
     *
     * @param window    用户窗口采集结果。采集失败时调用方传全 0
     * @param cluster   设备与 IP 维度采集结果。采集失败时调用方传 {@link Cluster#EMPTY}
     * @param blacklist 黑名单查询结果。查询失败时调用方传 {@link BlacklistHits#NONE}
     * @param limitHit  限频键是否存在。只在 {@code ENFORCE} 模式下才可能为 true——
     *                  {@code SHADOW} 连读都不读（决策 D10）
     */
    public RiskDecision evaluate(UserWindow window, Cluster cluster,
                                 BlacklistHits blacklist, boolean limitHit) {
        List<RiskRuleId> matched = new ArrayList<>(RiskRuleId.values().length);

        if (window.count10s() > shortThreshold) {
            matched.add(RiskRuleId.USER_WINDOW_SHORT);
        }
        if (window.count60s() > longThreshold) {
            matched.add(RiskRuleId.USER_WINDOW_LONG);
        }
        // 同一个计数上的两档，取最高。见方法注释：这**就是**取最高，不是漏了一条。
        if (cluster.deviceAccounts() > deviceHighThreshold) {
            matched.add(RiskRuleId.DEVICE_ACCOUNTS_HIGH);
        } else if (cluster.deviceAccounts() > deviceLowThreshold) {
            matched.add(RiskRuleId.DEVICE_ACCOUNTS_LOW);
        }
        // IP 账号聚集只有 20 分，低于 LIMIT 档下界，单独命中时动作是 PASS。
        // 校园网出口 IP 高度共享，「同 IP 10 个账号」在平时就是常态（决策 D4）。
        if (cluster.ipAccounts() > ipAccountThreshold) {
            matched.add(RiskRuleId.IP_ACCOUNTS);
        }
        if (cluster.ipRequests() > ipRateThreshold) {
            matched.add(RiskRuleId.IP_RATE);
        }
        if (blacklist.user()) {
            matched.add(RiskRuleId.BLACKLIST_USER);
        }
        if (blacklist.device()) {
            matched.add(RiskRuleId.BLACKLIST_DEVICE);
        }
        if (blacklist.ip()) {
            matched.add(RiskRuleId.BLACKLIST_IP);
        }
        // 冷却命中走与其它规则完全相同的计分路径（决策 D5），而不是另开一条
        // 「直接返回 LIMIT」的分支——那样日志里会出现 action=LIMIT riskScore=0。
        if (limitHit) {
            matched.add(RiskRuleId.LIMIT_COOLDOWN);
        }

        int score = 0;
        for (RiskRuleId rule : matched) {
            score += rule.score();
        }
        return new RiskDecision(score, RiskAction.ofScore(score), matched, ruleVersion, List.of());
    }
}
