package com.campusdash.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Redis 与 Lua 脚本装配。
 *
 * 用 StringRedisTemplate 而不是默认的 RedisTemplate：默认模板用 JDK 序列化，
 * 写进去的 key/value 带二进制前缀，用 redis-cli 根本看不懂，排障非常痛苦。
 * 抢单场景的值就是数字和 ID，字符串序列化最直观。
 *
 * 脚本用 RedisScript 包装后由 Spring 自动走 EVALSHA，
 * 只在 Redis 返回 NOSCRIPT 时才回退到 EVAL 重新加载，省掉每次传输脚本体的开销。
 */
@Configuration
public class RedisLuaConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public RedisScript<Long> grabScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/grab.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> rollbackSlotScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/rollback_slot.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 风控的两个脚本返回的是 Lua 数组（{@code return {a, b, c}}），不是标量，
     * 因此结果类型是 {@code List} 而不是上面两个的 {@code Long}。
     *
     * <p>Spring 把 Lua 数组转成 {@code List<Long>}，元素为脚本里 return 的那几个数字。
     * 元素个数由脚本决定，调用方按下标取值——下标含义写在各自脚本的头部注释里，
     * 改脚本增删返回值时必须同步改调用方，这里没有类型系统帮忙兜底。
     */
    @Bean
    public RedisScript<List<Long>> riskUserWindowScript() {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/risk_user_window.lua"));
        script.setResultType(listOfLong());
        return script;
    }

    @Bean
    public RedisScript<List<Long>> riskClusterScript() {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/risk_cluster.lua"));
        script.setResultType(listOfLong());
        return script;
    }

    /**
     * 三类精确黑名单查询（P9.2）。返回 {@code {用户命中, 设备命中, IP 命中}}，
     * 元素为 1/0，下标含义写在 {@code lua/risk_blacklist.lua} 头部。
     *
     * <p>与上面两个特征脚本的区别：它只读不写（全程 {@code EXISTS}），
     * 所以不存在「缺少哪个维度就不该建键」的顾虑，占位键在这里是纯防御。
     */
    @Bean
    public RedisScript<List<Long>> riskBlacklistScript() {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/risk_blacklist.lua"));
        script.setResultType(listOfLong());
        return script;
    }

    /**
     * {@code setResultType} 收的是 {@code Class}，泛型在这里被擦除，
     * 无法写成 {@code List<Long>.class}。用一个局部变量把「这里要的是
     * List<Long> 而不是 List<SomethingElse>」这件事显式表达出来，
     * 避免将来有人照着抄成别的泛型。
     */
    @SuppressWarnings("unchecked")
    private static Class<List<Long>> listOfLong() {
        return (Class<List<Long>>) (Class<?>) List.class;
    }
}
