package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.BlacklistHits;
import com.campusdash.domain.risk.ports.RiskBlacklistPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 黑名单查询端口的 Redis 实现（决策 D9：三类一次往返）。
 *
 * <p><b>原始 {@code deviceId} / {@code clientIp} 只在本类内部出现，且只用于算出哈希。</b>
 * 它们不进键名、不进日志、不进异常信息——端口签名收原始值，是因为解析层
 * （presentation）不该知道哈希密钥；哈希的边界画在这里（P9.1 决策 D1）。
 *
 * <p>与 {@link RedisRiskFeatureAdapter} 的两处差别，都是「只读」带来的：
 *
 * <ul>
 *   <li><b>不做「两个维度都缺失就跳过」的短路。</b>用户维度恒存在，三个维度里至少
 *       有一个要查，跳过脚本省不下任何东西。</li>
 *   <li><b>占位键是纯防御。</b>{@code EXISTS} 不创建键，占位键漏进去也只是返回 0；
 *       而特征采集那边漏进去就是真的 {@code SADD}/{@code ZADD} 建出一个键。</li>
 * </ul>
 *
 * <p><b>不 catch 异常。</b>Redis 故障时让异常向上抛，由用例转成
 * {@code DegradeReason.BLACKLIST_FAILED}。适配器自己吞掉并返回全 false 才是最坏的选择：
 * 那和「查了、都没命中」在调用方看来完全一样，线上表现为黑名单静默失效。
 */
@Component
public class RedisRiskBlacklistAdapter implements RiskBlacklistPort {

    /** 脚本返回值的下标，与 {@code lua/risk_blacklist.lua} 头部的「返回」一致。 */
    private static final int IDX_USER = 0;
    private static final int IDX_DEVICE = 1;
    private static final int IDX_IP = 2;

    private final StringRedisTemplate redis;
    private final RedisScript<List<Long>> blacklistScript;
    private final DeviceHasher hasher;

    public RedisRiskBlacklistAdapter(StringRedisTemplate redis,
                                     @Qualifier("riskBlacklistScript") RedisScript<List<Long>> blacklistScript,
                                     DeviceHasher hasher) {
        this.redis = redis;
        this.blacklistScript = blacklistScript;
        this.hasher = hasher;
    }

    @Override
    public BlacklistHits query(long userId, String deviceId, String clientIp) {
        // 先归一化再判断：空白串与 null 等价。放到这里而不是只靠 RiskContext，
        // 是因为端口是公开契约，不能假定每个调用方都传过一遍 RiskContext。
        boolean hasDevice = deviceId != null && !deviceId.isBlank();
        boolean hasIp = clientIp != null && !clientIp.isBlank();

        List<Long> result = redis.execute(blacklistScript,
                List.of(RiskKeys.blacklistUser(userId),
                        hasDevice ? RiskKeys.blacklistDevice(hasher.hash(deviceId)) : RiskKeys.ABSENT,
                        hasIp ? RiskKeys.blacklistIp(hasher.hash(clientIp)) : RiskKeys.ABSENT),
                hasDevice ? "1" : "0",
                hasIp ? "1" : "0");

        return new BlacklistHits(
                flagAt(result, IDX_USER),
                flagAt(result, IDX_DEVICE),
                flagAt(result, IDX_IP));
    }

    /**
     * 取第 {@code index} 个返回值并转成布尔。
     *
     * <p><b>拿不到就抛，不返回 false——这一点与
     * {@link RedisRiskFeatureAdapter} 的 {@code intAt} 刻意相反。</b>
     * 那边的定位是「采集」，采不到就是 0，而 0 是个中性值：计数为 0 不会让任何人被误判。
     * 这里是**安全判定**：false 的含义是「这个人不在黑名单里」，直接导致放行。
     * 用一个故障换来一次放行，而且放行得悄无声息，比抛异常糟糕得多。
     *
     * <p>正常路径下走不到这里：脚本用 {@code hits = {0, 0, 0}} 初始化后再逐项改写，
     * Lua 返回数组只会在遇到 nil 时截断，所以三个元素恒在。真走到这里说明脚本被改坏了
     * 或者返回值被替换过——抛出去，用例会把它记成 {@code BLACKLIST_FAILED}，
     * 日志里看得见，而不是表现为「黑名单没命中」。
     */
    private static boolean flagAt(List<Long> result, int index) {
        if (result == null || index >= result.size()) {
            throw new IllegalStateException(
                    "黑名单脚本返回值不完整：期望 3 个元素，实际拿到 "
                            + (result == null ? "null" : result.size()) + " 个。"
                            + "这里不能按「未命中」处理——那会把一次脚本故障变成一次静默放行。");
        }
        Long value = result.get(index);
        if (value == null) {
            throw new IllegalStateException("黑名单脚本第 " + index + " 个返回值为 null");
        }
        return value != 0L;
    }
}
