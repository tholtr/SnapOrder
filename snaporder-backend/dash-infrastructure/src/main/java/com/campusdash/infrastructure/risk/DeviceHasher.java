package com.campusdash.infrastructure.risk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 设备标识与客户端 IP 的 HMAC 哈希器（蓝图 §3.1）。
 *
 * <p><b>这个类不能有任何 Spring 依赖，构造函数直接收密钥。</b>
 * 理由：dash-bench 的 BlacklistKeyTool 必须复用它来计算黑名单键——工具算出的键
 * 与应用查的键必须逐字节相同，复制一份 HMAC 逻辑必然会漂移，而漂移的后果是
 * 黑名单静默失效（查得到、不报错、就是永不命中）。但 dash-bench 不是 Spring 应用，
 * 只要这个类的签名里不出现 Spring 类型，工具就能直接 {@code new DeviceHasher(secret)}。
 * Spring 装配另放在配置类里。
 *
 * <p><b>为什么必须哈希而不是直接存明文</b>：Redis 里存原始 IP/设备号等于在缓存层
 * 留一份个人信息副本；且键长不固定，内存画像不可控。HMAC 用密钥而非裸 SHA256，
 * 是因为 IP 地址空间只有 2^32、设备号也高度可枚举，裸哈希等于可逆——
 * 拿彩虹表就能还原，等于没哈希。
 *
 * <p><b>为什么截断到 32 个十六进制字符（128 位）</b>：完整 HMAC-SHA256 是 64 字符，
 * 键名长一倍但安全性没有实际增益——128 位碰撞概率在任何真实基数下都可忽略，
 * 而键名长度直接进内存画像。
 */
public final class DeviceHasher {

    private static final String ALGORITHM = "HmacSHA256";

    /** 截断后的十六进制字符数。128 位，见类注释。 */
    private static final int HEX_LENGTH = 32;

    private static final HexFormat HEX = HexFormat.of();   // 小写

    private final byte[] key;

    public DeviceHasher(String secret) {
        // 与 RiskProperties 的双重校验：那边管配置来源，这里管「拿到手就一定可用」。
        // 小工具直接读环境变量，不经过 Spring，所以这层不能省——
        // 空密钥会让所有设备/IP 算到同一个值，风控维度直接失效且不报错。
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "设备哈希密钥不能为空。来源为 dash.risk.device-hmac-secret，"
                            + "本地运行用环境变量 DASH_RISK_DEVICE_HMAC_SECRET 提供。");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 把设备标识或客户端 IP 哈希成定长十六进制串。
     *
     * <p>设备与 IP 共用同一个哈希器（D2）：两者的用途完全相同（做 Redis 键的一部分），
     * 分开只会多一套密钥管理。
     *
     * @param raw 原始标识，非空非空白。调用方负责判空后跳过对应维度，
     *            不要把 null 传进来当作「无设备」——那会让「没传设备号」和
     *            「设备号是空串」在 Redis 里落到同一个键上。
     */
    public String hash(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("待哈希的标识不能为空，缺失的维度应由调用方跳过");
        }
        // Mac 不是线程安全的。每次新建而不缓存：本方法在抢单路径上最多调用两次，
        // 实例化开销远小于 10ms 的总预算；缓存就得处理并发，为一个微秒级的优化
        // 引入一类很难复现的 bug 不划算。
        byte[] digest = newMac().doFinal(raw.getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(digest).substring(0, HEX_LENGTH);
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac;
        } catch (NoSuchAlgorithmException e) {
            // HmacSHA256 是 JDK 必须实现的算法（JLS 要求），走不到这里
            throw new IllegalStateException("JDK 不支持 " + ALGORITHM, e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("设备哈希密钥不合法", e);
        }
    }
}
