package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.ports.RiskLimitPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户级限频端口的 Redis 实现。
 *
 * <p>键是 {@code risk:limit:user:{userId}}，值是常量 {@code "1"}——
 * <b>值里不承载任何信息</b>，端口只问「键在不在」（蓝图 §3.6）。
 * 想读「这个用户什么时候被限的」看 TTL 就行，不必把时间写进值里再自己算。
 *
 * <p><b>不做任何策略判断。</b>「只在 ENFORCE 模式调用」「只在动作恰为 LIMIT 时写入」
 * 都写在用例里（设计 §5.1）。适配器一旦自己判断模式，就会多出一个
 * 「用例以为没写、适配器写了」的失败路径，而这类问题只在切模式时才现形。
 *
 * <p><b>不 catch 异常。</b>两个方向的失败语义相反，必须由用例分别转成
 * {@code LIMIT_READ_FAILED} 与 {@code LIMIT_WRITE_FAILED}；在这里吞掉就只能返回一个
 * 笼统的失败，运维无法判断是「读不到」还是「写不进」——而这两个方向的补救动作完全不同。
 */
@Component
public class RedisRiskLimitAdapter implements RiskLimitPort {

    /**
     * 写入的值。取常量而不是时间戳或计数器：端口契约里它没有含义，
     * 给它加上含义（哪怕只是「方便排障」）就等于埋一个「将来有人读它」的引子，
     * 而读到的是别人从没承诺过的东西。
     */
    private static final String MARK = "1";

    private final StringRedisTemplate redis;
    private final RiskProperties properties;

    public RedisRiskLimitAdapter(StringRedisTemplate redis, RiskProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public boolean isLimited(long userId) {
        // 用 hasKey 而不是读值再判断：EXISTS 与值的类型无关，
        // 键就算被误建成别的类型也照样答「在」，限频不会因此失效。
        Boolean exists = redis.hasKey(RiskKeys.limitKey(userId));
        if (exists == null) {
            // hasKey 只在管道/事务里才可能返回 null，普通调用拿不到。
            // 真拿到说明调用方式变了——此时按 false 处理会静默放行一个可能正在
            // 冷却期的用户，不如抛出去让用例记成 LIMIT_READ_FAILED。
            throw new IllegalStateException(
                    "限频键查询返回 null，无法判断用户 " + userId + " 是否在冷却期");
        }
        return exists;
    }

    @Override
    public void markLimited(long userId) {
        // SET 带 PX，而不是 SET 之后再 EXPIRE：两条命令之间有窗口，
        // 若进程在中间挂掉就会留下一个永不过期的限频键——该用户被永久限频，
        // 且没有任何报错。单条 SET ... PX 是原子的。
        //
        // 不用 SET NX：重复写入只是刷新 TTL，不会累积状态。并发下同一用户的两个
        // 请求都可能判定为 LIMIT，把「只能写一次」做成约束会引入一个
        // 没有收益的失败路径。
        redis.opsForValue().set(RiskKeys.limitKey(userId), MARK,
                Duration.ofMillis(properties.limitCooldownMs()));
    }
}
