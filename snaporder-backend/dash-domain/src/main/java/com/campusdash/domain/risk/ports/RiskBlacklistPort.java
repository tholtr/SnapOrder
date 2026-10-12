package com.campusdash.domain.risk.ports;

import com.campusdash.domain.risk.model.BlacklistHits;

/**
 * 精确黑名单查询端口（蓝图 §3.5）。
 *
 * <p><b>三类黑名单一次往返查完</b>（P9.2 决策 D9），所以只有一个方法。拆成三个方法
 * 会让同一次预检产生三次 Redis 往返，与蓝图 §3.8 的 10ms 总 deadline 冲突；
 * 而且三类查询之间没有任何依赖，分开的唯一后果就是更慢。
 *
 * <p>签名沿用 P9.1 已确立的三条约定：
 *
 * <ul>
 *   <li><b>参数是扁平标量</b>，不收 {@code RiskContext} 整体——传整个上下文会让实现方
 *       顺手读走一些它本不该关心的字段，也让单测必须造一个完整的上下文；</li>
 *   <li><b>收原始 {@code deviceId} / {@code clientIp}，哈希在适配器内完成。</b>
 *       解析层不该知道哈希密钥，全仓「原始值 → 哈希」只有一个入口；</li>
 *   <li><b>不抛领域异常</b>——Redis 故障由调用方 catch 后转成
 *       {@link com.campusdash.domain.risk.model.DegradeReason#BLACKLIST_FAILED}。</li>
 * </ul>
 *
 * <p>两个 {@code String} 参数**可空**，缺失的维度**不查那个键**：设备与 IP 的键名
 * 带 HMAC 哈希，拿不到原始值根本构造不出键名；而且缺失维度对应的键本来就不该存在。
 * 结果里对应字段恒为 {@code false}。
 *
 * <p>端口**不预埋 deadline 参数**（决策 D11）：总预算的分配是 P9.3 的事，
 * 现在加一个没人传的参数，只会让 P9.3 之前的所有调用点都要多写一个占位值。
 */
public interface RiskBlacklistPort {

    /**
     * 查三类黑名单是否命中。
     *
     * <p>实现用 {@code EXISTS} 而不是 {@code GET}：EXISTS 与值的类型无关，
     * 所以「键被建成了错误类型」这条降级路径在这里不存在——那是特征采集侧
     * （{@code SADD}/{@code ZADD} 会报错）才有的问题。也因此
     * {@code RiskPreCheckIT} 无法靠造错类型键来诱发黑名单降级，降级只能由
     * fake 端口抛异常来覆盖。
     *
     * <p>查询失败时**抛运行时异常**，由调用方转成降级原因后继续往下走
     * （传 {@link BlacklistHits#NONE}）。不留一条「返回 NONE 表示失败」的静默路径：
     * 那样「查了没命中」和「没查成」在调用方看来完全一样，
     * 而这两件事对运维的意义完全不同。
     *
     * @param deviceId 可空。为 {@code null} 或空白时设备维度不查、结果恒为 false
     * @param clientIp 可空。为 {@code null} 或空白时 IP 维度不查、结果恒为 false
     */
    BlacklistHits query(long userId, String deviceId, String clientIp);
}
