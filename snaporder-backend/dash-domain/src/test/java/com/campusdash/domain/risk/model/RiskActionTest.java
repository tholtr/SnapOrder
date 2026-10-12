package com.campusdash.domain.risk.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档位映射的边界。{@link RiskAction#ofScore} 是决策里唯一的定级入口（决策 D1），
 * 它错一格，所有规则的档位标注就全错——所以三个边界值逐一对齐断言。
 */
@DisplayName("风控四级动作")
class RiskActionTest {

    @ParameterizedTest(name = "{0} 分 → {1}")
    @CsvSource({
            "0,    PASS",
            "1,    PASS",
            "29,   PASS",     // LIMIT 下界的前一格
            "30,   LIMIT",    // 边界值属于该档，不是 PASS
            "31,   LIMIT",
            "59,   LIMIT",    // REVIEW 下界的前一格
            "60,   REVIEW",
            "61,   REVIEW",
            "79,   REVIEW",   // BLOCK 下界的前一格
            "80,   BLOCK",
            "81,   BLOCK",
            "490,  BLOCK",
    })
    @DisplayName("跨过 30/60/80 时动作恰好切换，边界值取上档")
    void mapsScoreToAction(int score, RiskAction expected) {
        assertEquals(expected, RiskAction.ofScore(score));
    }

    @Test
    @DisplayName("三档下界就是常量本身，不是另抄的一份字面量")
    void bandFloorsAreTheConstants() {
        // 这条断言看着同义反复，但它锁住的是一件真事：规则分值引用的是这三个常量。
        // 一旦有人把 ofScore 里的 80 改成字面量、又把 BLOCK_MIN 调走，
        // 黑名单分数与档位就会脱钩——本条会先红。
        assertEquals(RiskAction.LIMIT, RiskAction.ofScore(RiskAction.LIMIT_MIN));
        assertEquals(RiskAction.REVIEW, RiskAction.ofScore(RiskAction.REVIEW_MIN));
        assertEquals(RiskAction.BLOCK, RiskAction.ofScore(RiskAction.BLOCK_MIN));
    }

    @Test
    @DisplayName("声明顺序即严重度，升序")
    void declarationOrderIsSeverity() {
        // 严重度靠 ordinal 表达，不再维护平行的 level 字段——那会多出一条
        // 「level 写反了但没人发现」的失败路径。重排常量顺序会先红在这里。
        assertTrue(RiskAction.PASS.ordinal() < RiskAction.LIMIT.ordinal(), "PASS 必须最轻");
        assertTrue(RiskAction.LIMIT.ordinal() < RiskAction.REVIEW.ordinal(), "LIMIT 必须轻于 REVIEW");
        assertTrue(RiskAction.REVIEW.ordinal() < RiskAction.BLOCK.ordinal(), "REVIEW 必须轻于 BLOCK");
    }

    @Test
    @DisplayName("负数分退回 PASS，不会意外落进某个档位")
    void negativeScoreFallsBackToPass() {
        // 正常路径下分数是各规则分值之和，不可能为负。真出现负数说明规则表被改坏了，
        // 此时退回 PASS 是唯一不产生误伤的答案——但绝不能让它落进 BLOCK。
        assertEquals(RiskAction.PASS, RiskAction.ofScore(-1));
        assertEquals(RiskAction.PASS, RiskAction.ofScore(Integer.MIN_VALUE));
    }
}
