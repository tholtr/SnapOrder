package com.campusdash.application.risk;

import com.campusdash.domain.risk.model.Cluster;
import com.campusdash.domain.risk.model.UserWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这一行是给离线分析脚本读的，字段名和顺序等于协议。
 * 本类的作用是把协议锁死——改格式必须同时改这里，改不动就说明那是破坏性变更。
 */
@DisplayName("风控特征日志行")
class RiskFeatureLogTest {

    private static final UserWindow WINDOW = new UserWindow(3, 7, false);
    private static final Cluster CLUSTER = new Cluster(2, 5, 11);

    @Test
    @DisplayName("整行逐字匹配计划 §6 的格式")
    void matchesSpecExactly() {
        String line = RiskFeatureLog.format(1001L, 2002L, "req-abc", WINDOW, CLUSTER, false);

        assertEquals("risk_feature userId=1001 errandId=2002 requestId=req-abc"
                + " count10s=3 count60s=7"
                + " deviceAccounts=2 ipAccounts=5 ipRequests=11"
                + " degraded=false", line);
    }

    @Test
    @DisplayName("九个字段都在，且顺序固定")
    void fieldOrderIsStable() {
        // 顺序不是审美问题：分析脚本若按位置切分，换序会静默产出错指标。
        String[] expected = {"userId=", "errandId=", "requestId=", "count10s=", "count60s=",
                "deviceAccounts=", "ipAccounts=", "ipRequests=", "degraded="};

        String line = RiskFeatureLog.format(1L, 2L, "r", WINDOW, CLUSTER, true);

        int cursor = -1;
        for (String field : expected) {
            int at = line.indexOf(field);
            assertTrue(at > cursor, "字段 " + field + " 缺失或顺序不对：" + line);
            cursor = at;
        }
    }

    @Test
    @DisplayName("整行只有一行——没有换行")
    void alwaysSingleLine() {
        assertFalse(RiskFeatureLog.format(1L, 2L, "r", WINDOW, CLUSTER, false).contains("\n"));
    }

    @Test
    @DisplayName("降级时 degraded=true，计数照常输出")
    void degradedFlagIsRendered() {
        // 降级路径下调用方拿不到真实计数，会传 Cluster.EMPTY；
        // 这一行必须把「这次是降级，不是真的零」说清楚，否则指标会把故障读成风平浪静。
        String line = RiskFeatureLog.format(1L, 2L, "r", new UserWindow(0, 0, false), Cluster.EMPTY, true);

        assertTrue(line.endsWith("degraded=true"), line);
        assertTrue(line.contains("count10s=0") && line.contains("ipRequests=0"), line);
    }

    @Test
    @DisplayName("requestId 里的控制字符被替换，无法伪造出一行新日志")
    void controlCharactersCannotForgeLines() {
        // 消费方是离线分析脚本，一条伪造的 risk_feature 就能污染指标。
        String forged = "abc\nrisk_feature userId=999 errandId=999";

        String line = RiskFeatureLog.format(1L, 2L, forged, WINDOW, CLUSTER, false);

        assertFalse(line.contains("\n"), "换行必须被消除：" + line);
        assertEquals(1, line.lines().count());
        // 内容仍在，只是不再具备「另起一行」的能力
        assertTrue(line.contains("userId=999"), line);
    }

    @Test
    @DisplayName("制表符、回车等也一并替换")
    void otherControlCharactersReplaced() {
        String line = RiskFeatureLog.format(1L, 2L, "a\r\n\tb", WINDOW, CLUSTER, false);

        assertFalse(line.contains("\r"));
        assertFalse(line.contains("\t"));
        assertTrue(line.contains("requestId=a___b"), line);
    }

    @Test
    @DisplayName("空白被中性化——带空格的 requestId 伪造不出一个字段（P9.4 D13）")
    void whitespaceCannotForgeFields() {
        // 这一行是空格分隔的 key=value，而 requestId 来自客户端的 X-Request-Id 头，
        // 它**可以带空格**（Tomcat 拒绝裸 CR/LF，但不拒绝空格——挡控制字符那套论证
        // 在这里不成立）。不中性化的话，下面这个值会渲染出第二个 userId=，
        // 而脚本按空格切分取到的将是客户端写进去的那个。
        String line = RiskFeatureLog.format(1L, 2L, "x userId=999", WINDOW, CLUSTER, false);

        assertTrue(line.contains("requestId=x_userId=999"), line);
        // 关键断言：伪造的那段还在（内容不丢），但不再是独立的 token，切分取不到它
        assertFalse(List.of(line.split(" ")).contains("userId=999"), line);
    }

    @Test
    @DisplayName("requestId 为 null 或空时占位，不打印 null")
    void emptyRequestIdPlaceholder() {
        // 打印字面量 "null" 会让分析脚本把它当成一个真实的 requestId 值去聚合。
        assertTrue(RiskFeatureLog.format(1L, 2L, null, WINDOW, CLUSTER, false)
                .contains("requestId=-"));
        assertTrue(RiskFeatureLog.format(1L, 2L, "", WINDOW, CLUSTER, false)
                .contains("requestId=-"));
    }

    @Test
    @DisplayName("负数计数照常打印（不吞掉异常值）")
    void negativeCountsAreNotSwallowed() {
        // 计数来自 Lua，正常不会是负数。真出现负数说明脚本坏了，
        // 此时抹平成 0 会让故障彻底隐身。这条锁定「不美化数据」。
        String line = RiskFeatureLog.format(1L, 2L, "r", new UserWindow(-1, -1, true), new Cluster(-1, -1, -1), false);

        assertTrue(line.contains("count10s=-1"), line);
        assertTrue(line.contains("deviceAccounts=-1"), line);
    }
}
