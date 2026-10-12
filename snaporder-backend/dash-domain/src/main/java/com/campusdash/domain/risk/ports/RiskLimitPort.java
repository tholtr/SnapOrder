package com.campusdash.domain.risk.ports;

/**
 * 用户级限频键的读写端口。
 *
 * <p>键为 {@code risk:limit:user:{userId}}，TTL 取配置的 {@code limit-cooldown-ms}。
 * 它**只在 {@code ENFORCE} 模式下被调用**：{@code SHADOW} 的定义是「计算并记录风险结果，
 * 但不拦截」，限频键整体归属 {@code ENFORCE}（决策 D10）。这不只是省两次 I/O——
 * 限频键一旦在 {@code SHADOW} 下被写入，切回 {@code ENFORCE} 时就会凭空出现一批
 * 「之前被限过频」的用户，而这些人从没被拦过。所以判断必须在用例里，
 * 适配器不做策略。
 *
 * <p>两个方法**都只在动作恰为 {@code LIMIT} 时才有意义**：
 *
 * <ul>
 *   <li>读：预检第一步，命中则短路，不再采集、不查黑名单；</li>
 *   <li>写：预检最后一步，且仅当本次决策动作是 {@code LIMIT}。
 *       {@code REVIEW} 与 {@code BLOCK} <b>不写</b>——它们的处置不是「冷一下再放」，
 *       写限频键会让同一个请求在冷却期内被两条规则分别解释一次。</li>
 * </ul>
 *
 * <p>沿用 P9.1 的约定：参数是扁平标量、**不抛领域异常**。两个方向的失败语义相反，
 * 调用方要分别转成不同的降级原因，不能合并：
 *
 * <table border="1">
 *   <caption>读写失败的后果</caption>
 *   <tr><th>方法</th><th>失败原因</th><th>后果</th></tr>
 *   <tr><td>{@link #isLimited}</td>
 *       <td>{@code LIMIT_READ_FAILED}</td>
 *       <td>无法判断是否在冷却期，当作没命中继续——可能放过一个本该被限频的请求</td></tr>
 *   <tr><td>{@link #markLimited}</td>
 *       <td>{@code LIMIT_WRITE_FAILED}</td>
 *       <td>本次 {@code LIMIT} 决策是对的，但冷却期没生效，
 *           同一用户会立刻再来一次</td></tr>
 * </table>
 *
 * <p>端口**不预埋 deadline 参数**（决策 D11）。
 */
public interface RiskLimitPort {

    /**
     * 该用户当前是否处于限频冷却期。
     *
     * <p>实现只判断键是否存在，不读值——键里没有需要读的内容，
     * 「存在」就是全部信息。
     *
     * @return 键存在为 true。Redis 故障时抛运行时异常，由调用方转成
     *         {@code LIMIT_READ_FAILED} 后当作 false 继续
     */
    boolean isLimited(long userId);

    /**
     * 把该用户标记为限频，TTL 由适配器按 {@code limit-cooldown-ms} 设置。
     *
     * <p>幂等：重复写入只是刷新 TTL，不会报错也不会累积状态。这一点是刻意的——
     * 并发下同一个用户的两次请求都可能判定为 {@code LIMIT}，把「只能写一次」
     * 做成约束会引入一个没有收益的失败路径。
     */
    void markLimited(long userId);
}
