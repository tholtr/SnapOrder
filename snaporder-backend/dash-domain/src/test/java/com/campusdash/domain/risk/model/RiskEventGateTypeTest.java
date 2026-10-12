package com.campusdash.domain.risk.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 四个聚合窗口的**语义值**（P9.4 决策 D5）。
 *
 * <p>为什么单开一个测试类只钉四个数字：D5 决定了它们**不做配置项**，所以「窗口配错了」
 * 这条故障通路在运行期是关死的——坏事只可能发生在源码里。它同时意味着没有任何
 * yaml、没有任何启动校验会替你发现数字被改过。
 *
 * <p>断言必须逐值（10/60/60/10），不能写成「大于 0」或「不大于 60」：这四个数字就是
 * 规则本身（「同一用户 10 秒最多一条」），改一个数等于改语义，必须同时改设计文档。
 * 一条宽松的断言对它没有任何约束力——把 10 改成 30 照样过。
 */
@DisplayName("事件聚合闸门类型")
class RiskEventGateTypeTest {

    @Test
    @DisplayName("四个窗口逐值钉死：10 / 60 / 60 / 10——这是蓝图 §3.9 那张表，不是可调参数")
    void windowsAreTheBlueprintValues() {
        // 逐值断言的理由写在类注释里。这里额外把「谁是多少」写清楚：
        // 动作侧三个窗口跟着**事件的证据价值**走——LIMIT 是三档里最轻的（先限频看看），
        // 所以窗口最短、信息量最大；REVIEW 与 BLOCK 要人工或离线介入，60 秒一条足够。
        // 降级侧 10 秒是「一个实例的通告频率」，与用户无关。
        assertEquals(10, RiskEventGateType.LIMIT.windowSeconds());
        assertEquals(60, RiskEventGateType.REVIEW.windowSeconds());
        assertEquals(60, RiskEventGateType.BLOCK.windowSeconds());
        assertEquals(10, RiskEventGateType.DEGRADED.windowSeconds());
    }

    @Test
    @DisplayName("REVIEW 与 BLOCK 同窗口不是巧合——两个不同的键，各记一条")
    void reviewAndBlockShareTheWindowButNotTheKey() {
        // 同窗口容易被读成「写重了」。这条把意图记下来：窗口是同源的（都按
        // 「需要人看的事件，60 秒一条」定），但键不同，所以同一个用户 60 秒内
        // 可以各产生一条 REVIEW 与一条 BLOCK，互不抑制。
        //
        // 键名不同的断言在 RiskKeysTest 里（那边才是键的契约），这里只钉窗口同源。
        assertEquals(RiskEventGateType.REVIEW.windowSeconds(), RiskEventGateType.BLOCK.windowSeconds());
    }

    @Test
    @DisplayName("声明顺序即日志顺序，且四个常量一个不多一个不少")
    void declarationOrderIsTheLogOrder() {
        // 与 DegradeReason / RiskRuleId 同型：声明顺序是被冻结的跨系统契约。
        // 这里只出现四个值，顺序本身在单行日志上看不出来——它管的是离线脚本
        // 按 gateType 分组时的排列，以及「谁排在谁前面」这件事不要被顺手调换。
        //
        // 长度断言是防「顺手加第五类」：加闸门是设计级改动（D5 的窗口要重定、
        // RiskKeys 的 switch 会编译失败），不该在没人注意的时候发生。真加了，
        // 这条会红一次，逼着改动者来看一眼。
        assertEquals(List.of(
                        RiskEventGateType.LIMIT,
                        RiskEventGateType.REVIEW,
                        RiskEventGateType.BLOCK,
                        RiskEventGateType.DEGRADED),
                Arrays.asList(RiskEventGateType.values()));
    }
}
