package com.campusdash.infrastructure.risk;

import com.campusdash.domain.risk.model.Cluster;
import com.campusdash.domain.risk.model.UserWindow;
import com.campusdash.domain.risk.ports.RiskFeaturePort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 风控特征采集端口的 Redis Lua 实现（决策 D1：哈希在这一层完成）。
 *
 * <p><b>原始 {@code deviceId} / {@code clientIp} 只在本类内部出现，且只用于算出哈希。</b>
 * 它们不进键名、不进日志、不进异常信息——端口签名收的是原始值，
 * 是因为解析层（presentation）不该知道哈希密钥；哈希的边界画在这里，
 * 全仓「原始值 → 哈希」的转换只有这一个入口。
 *
 * <p>三个维度合成两次调用（用户窗口一次、设备+IP 一次），对应两个 Lua 脚本。
 * 不拆更细的原因见 {@code RiskFeaturePort.trackCluster} 的说明：拆开会让
 * IP 速率被重复计数，且违背蓝图 §3.8 的 10ms 总 deadline。
 */
@Component
public class RedisRiskFeatureAdapter implements RiskFeaturePort {

    private final StringRedisTemplate redis;
    private final RedisScript<List<Long>> userWindowScript;
    private final RedisScript<List<Long>> clusterScript;
    private final RiskProperties properties;
    private final DeviceHasher hasher;

    public RedisRiskFeatureAdapter(StringRedisTemplate redis,
                                   @Qualifier("riskUserWindowScript") RedisScript<List<Long>> userWindowScript,
                                   @Qualifier("riskClusterScript") RedisScript<List<Long>> clusterScript,
                                   RiskProperties properties,
                                   DeviceHasher hasher) {
        this.redis = redis;
        this.userWindowScript = userWindowScript;
        this.clusterScript = clusterScript;
        this.properties = properties;
        this.hasher = hasher;
    }

    @Override
    public UserWindow trackUserWindow(long userId, String requestId) {
        RiskProperties.UserWindow w = properties.userWindow();
        List<Long> result = redis.execute(userWindowScript,
                List.of(RiskKeys.userWindow(userId, w.shortSeconds()),
                        RiskKeys.userWindow(userId, w.longSeconds())),
                requestId,
                String.valueOf(w.shortCap()),
                String.valueOf(w.longCap()),
                // TTL 取最长窗口：键若在窗口结束前过期，窗口内的成员会一起消失，
                // 重放会被当成新请求，计数偏小。取两个值里的较大者而非硬编码 60，
                // 这样调长 longSeconds 时不会忘掉同步 TTL。
                String.valueOf(Math.max(w.shortSeconds(), w.longSeconds())));
        return new UserWindow(intAt(result, 0), intAt(result, 1), flagAt(result, 2));
    }

    @Override
    public Cluster trackCluster(long userId, String deviceId, String clientIp) {
        // 先归一化再判断：空白串与 null 等价。放到这里而不是只靠 RiskContext，
        // 是因为端口是公开契约，不能假定每个调用方都传过一遍 RiskContext。
        boolean hasDevice = deviceId != null && !deviceId.isBlank();
        boolean hasIp = clientIp != null && !clientIp.isBlank();

        // 两个维度都缺失：不调用脚本。这一条不是优化而是验收要求——
        // 缺维度的请求不该在 Redis 里留下痕迹，否则这些键会随老客户端一直堆积。
        if (!hasDevice && !hasIp) {
            return Cluster.EMPTY;
        }

        RiskProperties.DeviceWindow dw = properties.deviceWindow();
        RiskProperties.IpAccountWindow iw = properties.ipAccountWindow();
        RiskProperties.IpRateWindow rw = properties.ipRateWindow();

        List<Long> result = redis.execute(clusterScript,
                List.of(hasDevice ? RiskKeys.deviceCluster(hasher.hash(deviceId)) : RiskKeys.ABSENT,
                        hasIp ? RiskKeys.ipAccounts(hasher.hash(clientIp)) : RiskKeys.ABSENT,
                        hasIp ? RiskKeys.ipRate(hasher.hash(clientIp)) : RiskKeys.ABSENT),
                String.valueOf(userId),
                String.valueOf(dw.cap()),
                String.valueOf(iw.cap()),
                String.valueOf(rw.cap()),
                // 两个 Set 的 TTL 必须各传各的：Set 没有分值，「窗口」这个语义
                // 完全由 TTL 承载，所以 TTL 就是窗口长度本身。
                // 早期版本这里只传了 dw.seconds()，被脚本同时用在两个 Set 上——
                // 结果是 dash.risk.ip-account-window.seconds 成了一个读了也不生效的
                // 死配置项，改它没有任何效果，且不报错。
                String.valueOf(dw.seconds()),
                String.valueOf(iw.seconds()),
                String.valueOf(rw.seconds()),
                hasDevice ? "1" : "0",
                hasIp ? "1" : "0");

        return new Cluster(intAt(result, 0), intAt(result, 1), intAt(result, 2));
    }

    /**
     * Lua 的返回值经 Spring 转换后是 {@code List<Long>}，但元素类型不保证——
     * 脚本里 {@code return {a, b, c}} 三个都是数字，理论上都是 Long，
     * 真遇到 null 只可能是脚本被改坏或连接异常。
     *
     * <p>返回 0 而不是抛异常：这里的定位是「采集」，采不到就是 0，
     * 决策由 P9.2 的规则层做。抛异常会把一次采集失败放大成一次抢单失败，
     * 而 P9.3 才引入 fail-open 语义，本阶段不宜把降级策略写死在适配器里。
     */
    private static int intAt(List<Long> result, int index) {
        if (result == null || index >= result.size()) {
            return 0;
        }
        Long value = result.get(index);
        return value == null ? 0 : value.intValue();
    }

    /** Lua 返回的 1/0 转布尔。 */
    private static boolean flagAt(List<Long> result, int index) {
        return intAt(result, index) != 0;
    }
}
