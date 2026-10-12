package com.campusdash.infrastructure.risk;

import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 只认一条命令的 {@code StringRedisTemplate} 替身：{@code SET key value NX PX n}。
 *
 * <p><b>为什么必须自己写</b>：{@link RedisRiskEventGate} 唯一的依赖是
 * {@code StringRedisTemplate}，而 Spring 不提供任何可注入的假实现——{@code opsForValue()}
 * 返回的 {@code DefaultValueOperations} 是**包级私有**类（{@code org.springframework.data.redis.core}），
 * 从本包继承不了。所以只能覆盖 {@code opsForValue()}（它在 {@code RedisTemplate} 上是
 * {@code public} 且非 final）返回下面这个手写的 {@code ValueOperations}。
 *
 * <p><b>为什么其余 30 个方法一律抛 {@code UnsupportedOperationException}</b>：
 * 它们不是「还没轮到实现」，而是**这个替身刻意不支持的**。
 * {@code RedisRiskEventGate} 只该发一条命令；它哪天开始调别的命令，就说明适配器的形状
 * 变了——那时这条测试应该**炸**，而不是静静地从一个返回 null 的桩子上走过去。
 * 这也是仓库「不用 Mockito、手写 fake」的代价：{@code ValueOperations} 有 33 个方法，
 * 其中 3 个是 default、30 个要落地。抄一遍很蠢，但它只有一次，而且没有反射、
 * 没有隐式匹配器、没有「忘了 verify」这类只在 mock 框架里才存在的失败模式。
 *
 * <p><b>只记录，不判断</b>：这个类不解释 {@link #lastTtl} 对不对——那是测试的事。
 * 替身里一旦长出一条业务断言，读到它的人就要先判断「这条断言是替身的还是被测的」。
 */
class FakeStringRedisTemplate extends StringRedisTemplate {

    /** {@code setIfAbsent} 的返回值。取 {@code null} 可以复现「管道/事务里才会出现」的那个返回值。 */
    Boolean nextResult = Boolean.TRUE;

    /** 最后一次 {@code setIfAbsent} 的三个实参。 */
    String lastKey;
    String lastValue;
    Duration lastTtl;

    /** 调用次数。用来断言「只碰了一条命令」，而不只是「碰的那条参数对」。 */
    int calls;

    /** 其余 30 个方法的统一出口。 */
    private static final String NOT_SUPPORTED =
            "这个替身只实现 setIfAbsent(key, value, Duration)——RedisRiskEventGate 也只该用它。 "
                    + "走到这里说明被测代码开始调用别的命令了，那是适配器形状变了，不是替身缺功能。";

    private final ValueOperations<String, String> ops = new OnlySetIfAbsent();

    @Override
    public ValueOperations<String, String> opsForValue() {
        return ops;
    }

    private final class OnlySetIfAbsent implements ValueOperations<String, String> {

        @Override
        public Boolean setIfAbsent(String key, String value, Duration timeout) {
            lastKey = key;
            lastValue = value;
            lastTtl = timeout;
            calls++;
            return nextResult;
        }

        // ── 以下全部刻意不支持，理由见类注释 ────────────────────────────────

        @Override
        public void set(String key, String value) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public String setGet(String key, String value, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public String setGet(String key, String value, Duration timeout) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public void set(String key, String value, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Boolean setIfAbsent(String key, String value) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Boolean setIfPresent(String key, String value) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Boolean setIfPresent(String key, String value, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public void multiSet(Map<? extends String, ? extends String> map) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Boolean multiSetIfAbsent(Map<? extends String, ? extends String> map) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public String get(Object key) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public String getAndDelete(String key) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public String getAndExpire(String key, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public String getAndExpire(String key, Duration timeout) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public String getAndPersist(String key) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public String getAndSet(String key, String value) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public List<String> multiGet(Collection<String> keys) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Long increment(String key) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Long increment(String key, long delta) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Double increment(String key, double delta) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Long decrement(String key) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Long decrement(String key, long delta) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Integer append(String key, String value) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public String get(String key, long start, long end) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public void set(String key, String value, long offset) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Long size(String key) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Boolean setBit(String key, long offset, boolean value) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public Boolean getBit(String key, long offset) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public List<Long> bitField(String key, BitFieldSubCommands subCommands) {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }

        @Override
        public RedisOperations<String, String> getOperations() {
            throw new UnsupportedOperationException(NOT_SUPPORTED);
        }
    }
}
