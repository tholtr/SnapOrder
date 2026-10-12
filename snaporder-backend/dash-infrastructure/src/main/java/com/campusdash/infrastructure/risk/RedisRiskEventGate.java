package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.RiskEventGateType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 事件聚合闸门：回答「这条风险事件现在能不能发」。
 *
 * <p>实现只有一条命令——{@code SET key 1 NX EX <窗口秒>}（P9.4 D4）。
 * 抢到键 = 距上一次发送已满一个窗口，可以发；没抢到 = 窗口内已有过一条，抑制。
 * 键由 {@link RiskKeys#eventGateFor(RiskEventGateType, String)} 构造，窗口由
 * {@link RiskEventGateType#windowSeconds()} 给出，两者同源于一个枚举常量。
 *
 * <p><b>为什么不做时间桶</b>：蓝图 §3.9 的键名示例带一段 {@code :{10sBucket}}，
 * 本阶段落地时去掉了。带桶的方案把时间切成 {@code [0,10)}、{@code [10,20)} 这样的格子，
 * 于是 {@code t=9.9s} 与 {@code t=10.1s} 两次事件落进两个桶、**各自都抢到了键**——
 * 同一用户 0.2 秒内上了两条 MQ，而规则写的是「10 秒内最多 1 条」。
 * {@code SET NX EX} 的语义是「距上一次发送满 N 秒才允许下一次」，与规则逐字对应，
 * 没有边界可以泄漏。
 *
 * <p><b>为什么值仍是常量 {@code "1"}</b>：照 {@link RedisRiskLimitAdapter} 的先例——
 * 闸门只回答「键在不在」，把时间写进值里就多出一个「谁负责读」的悬案，
 * 而读到的东西没有任何地方承诺过。想看「上一次是什么时候」，读 TTL 就行。
 *
 * <p><b>不做任何策略判断。</b>「聚合开关关掉时整个跳过闸门」「闸门抛异常时仍然发送」
 * 都写在调用方（{@code RocketMqRiskEventAdapter} 的 D8 路径）里。适配器一旦自己
 * 决定「失败也放行」，这里的异常就失去了意义——而 D8 那条判断需要和
 * {@code SENT_GATE_FAILED} 这个日志值一起改，分成两处改必然漂移。
 * 这与 {@code RiskLimitPort} 的既有分工一致：适配器只管一层数据访问，
 * 失败语义由用例分别转成各自的错误码。
 *
 * <p><b>这是一次会阻塞的网络往返</b>，所以调用它的地方必须已经离开请求线程
 * （P9.4 D6：执行器线程上跑「闸门定档 → tryAcquire → sendAsync」）。
 */
@Component
public class RedisRiskEventGate {

    /**
     * 写入的值。与 {@code RedisRiskLimitAdapter.MARK} 同理由：契约里它没有含义，
     * 给它加上含义就等于埋一个「将来有人读它」的引子。
     */
    private static final String MARK = "1";

    private final StringRedisTemplate redis;

    public RedisRiskEventGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试占用该闸门的窗口。
     *
     * @param type    闸门类型，决定键名段与 TTL 两件事（见 {@link RiskEventGateType}）
     * @param subject 闸门主体。{@code LIMIT/REVIEW/BLOCK} 传用户 id（十进制字符串），
     *                {@code DEGRADED} 传实例标识——由类型唯一决定，传错会抛
     *                {@link IllegalArgumentException}，不会算出一个没人查得到的键
     * @return {@code true} 表示抢到，**可以发送**；{@code false} 表示窗口内已有过一条，抑制
     * @throws IllegalStateException 连接层的意外。调用方据此记 {@code SENT_GATE_FAILED}
     *                               并**照常发送**（D8）——聚合是保护 MQ 的手段，
     *                               不是「要不要记录」的判断
     */
    public boolean tryAcquire(RiskEventGateType type, String subject) {
        // SET 带 PX 一次做完，而不是 SET 之后再 EXPIRE：两条命令之间有窗口，
        // 若进程在中间挂掉就会留下一个永不过期的闸门键——那个用户（或那个实例）
        // 被**永久抑制**，且没有任何报错。单条 SET ... NX EX 是原子的。
        //
        // 也不用「先 GET 再 SET」：那会让两个并发事件都读到「键不在」，
        // 各自都发送——恰好在流量最大的时刻（也正是需要聚合的时刻）失效。
        Boolean acquired = redis.opsForValue().setIfAbsent(
                RiskKeys.eventGateFor(type, subject),
                MARK,
                Duration.ofSeconds(type.windowSeconds()));
        if (acquired == null) {
            // setIfAbsent 只在管道/事务里才可能返回 null，普通调用拿不到。
            // 真拿到说明调用方式变了——此时按「抢到」处理会让聚合静默失效，
            // 按「没抢到」处理会静默丢事件，两个方向都不该由这里猜。
            throw new IllegalStateException(
                    "闸门键写入返回 null，无法判断 " + type + " 事件是否可以发送。"
                            + "按任意一侧处理都会静默改变发送行为：这是调用方式变了，不是 Redis 忙。");
        }
        return acquired;
    }
}
