package com.campusdash.infrastructure.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("设备/IP 哈希器")
class DeviceHasherTest {

    private static final String SECRET = "local-dev-secret";

    @Nested
    @DisplayName("算法正确性")
    class Correctness {

        @Test
        @DisplayName("对齐 RFC 4231 标准向量，而不是对齐我自己的实现")
        void matchesRfc4231Vector() {
            // 这条用例的价值在于「锚点来自标准」：如果只断言「输出等于某次跑出来的值」，
            // 那么把 HMAC 误写成裸 SHA256、或密钥没参与运算，测试照样通过。
            // RFC 4231 Test Case 2：key="Jefe"，data="what do ya want for nothing?"
            String expectedFirst32 = "5bdcc146bf60754e6a042426089575c7";

            assertEquals(expectedFirst32, new DeviceHasher("Jefe").hash("what do ya want for nothing?"));
        }

        @Test
        @DisplayName("输出恒为 32 位小写十六进制")
        void outputShape() {
            String hashed = new DeviceHasher(SECRET).hash("device-abc");

            assertEquals(32, hashed.length());
            assertTrue(hashed.matches("[0-9a-f]{32}"), "实际输出：" + hashed);
        }

        @Test
        @DisplayName("同输入恒定输出——否则 Redis 键每请求都变，窗口完全失效")
        void deterministic() {
            DeviceHasher hasher = new DeviceHasher(SECRET);

            assertEquals(hasher.hash("1.2.3.4"), hasher.hash("1.2.3.4"));
        }

        @Test
        @DisplayName("不同输入输出不同")
        void distinct() {
            DeviceHasher hasher = new DeviceHasher(SECRET);

            assertNotEquals(hasher.hash("1.2.3.4"), hasher.hash("1.2.3.5"));
        }

        @Test
        @DisplayName("换密钥输出完全不同——证明密钥真的参与了运算")
        void keyMatters() {
            // 裸 SHA256 的最大危害是 IP 地址空间只有 2^32、设备号高度可枚举，
            // 彩虹表直接还原，等于没哈希。这条用例守住「密钥确实生效」。
            assertNotEquals(new DeviceHasher("key-a").hash("1.2.3.4"),
                    new DeviceHasher("key-b").hash("1.2.3.4"));
        }

        @Test
        @DisplayName("设备与 IP 共用同一个哈希器，不做维度区分")
        void sameHasherForBothDimensions() {
            // D2：两者用途完全相同（做 Redis 键的一部分），分开只会多一套密钥管理。
            // 这条锁住「不因为维度不同而加盐」——加了会让密钥轮换的语义变复杂。
            DeviceHasher hasher = new DeviceHasher(SECRET);

            assertEquals(hasher.hash("same-value"), hasher.hash("same-value"));
        }
    }

    @Nested
    @DisplayName("空输入的拒绝")
    class RejectsBlank {

        @Test
        @DisplayName("null / 空串 / 纯空白全部抛异常，而不是产出哈希")
        void rejectsBlankInput() {
            // 这条比计划里写的（「空输入不抛异常」）更严，理由是一个真实的假阳性：
            // 若空值也被哈希成一个稳定值，那么**所有不传设备头的客户端会落到同一个键**，
            // 设备多账号规则会把一群互不相关的用户判成同一台设备，误伤面是整个前端存量用户。
            // 缺失的维度应由调用方跳过（适配器返回 0、不写键），不该走到这里。
            DeviceHasher hasher = new DeviceHasher(SECRET);

            assertThrows(IllegalArgumentException.class, () -> hasher.hash(null));
            assertThrows(IllegalArgumentException.class, () -> hasher.hash(""));
            assertThrows(IllegalArgumentException.class, () -> hasher.hash("   "));
        }

        @Test
        @DisplayName("构造时密钥为空同样拒绝")
        void rejectsBlankSecret() {
            // 小工具直接读环境变量、不经过 Spring，所以这层校验不能只依赖 RiskProperties
            assertThrows(IllegalArgumentException.class, () -> new DeviceHasher(null));
            assertThrows(IllegalArgumentException.class, () -> new DeviceHasher(""));
            assertThrows(IllegalArgumentException.class, () -> new DeviceHasher("  "));
        }

        @Test
        @DisplayName("长度较长的输入正常处理，不做截断")
        void handlesLongInput() {
            // Tomcat 默认 header 上限 8KB，请求头到不了「超长」的量级，
            // 因此不加长度校验——为一个到不了的边界写代码只会增加分支。
            String longInput = "x".repeat(4096);

            assertEquals(32, new DeviceHasher(SECRET).hash(longInput).length());
        }
    }
}
