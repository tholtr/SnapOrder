package com.campusdash.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 净化器自己的契约。
 *
 * <p><b>与 {@code RiskDecisionLogTest} / {@code RiskFeatureLogTest} 不重复</b>：
 * 那两个类验的是**调用路径**——某一条日志行确实把自己的自由文本字段送进了这里
 * （万一有人从那条路径上把调用摘掉，只有对应的那条会红）。本类验的是**净化器本身**：
 * 哪些字符要挡、脏字符落在值的哪个位置、快慢两条路径是否给出同一个答案。
 * 这两件事各有各的失效方式，别把其中一边删掉当成去重。
 *
 * <p><b>为什么它现在住 {@code dash-shared}</b>：P9.4 的 D13 要让四条新日志行
 * 共用这一道闸，而它们分散在 application / infrastructure / worker 三个模块里，
 * 后者看不见前者。详见 {@link LogField} 的类注释。
 */
@DisplayName("日志字段净化")
class LogFieldTest {

    @Test
    @DisplayName("空值与空串都渲染成 -，字段边界肉眼可见")
    void blankInputsBecomeThePlaceholder() {
        assertEquals("-", LogField.safe(null));
        assertEquals("-", LogField.safe(""));
        assertEquals("-", LogField.EMPTY);
    }

    @Test
    @DisplayName("干净的值原样返回，一个字符都不动")
    void cleanValuesPassThrough() {
        assertEquals("abc-123", LogField.safe("abc-123"));
        // 冒号、花括号、引号都不在阻挡范围内：它们伪造不出一行新日志，
        // 也不参与空格切分。净化只挡「能把一行劈开」和「能造出新字段」的字符。
        assertEquals("a:b{c}\"d", LogField.safe("a:b{c}\"d"));
    }

    @Test
    @DisplayName("控制字符换成下划线：换行、回车、制表符")
    void controlCharactersAreNeutralized() {
        // 这是 D13 之前就有的行为，本阶段一个字没改。留着是为了让 D13 的改动
        // 有一个「改动前」的对照——它红了说明有人动了控制字符那一半。
        assertEquals("a_b", LogField.safe("a\nb"));
        assertEquals("a_b", LogField.safe("a\rb"));
        assertEquals("a_b", LogField.safe("a\tb"));
        assertEquals("a___b", LogField.safe("a\r\n\tb"));
    }

    @Test
    @DisplayName("空格也换成下划线（D13）——否则客户端能凭空造出一个字段")
    void spacesAreNeutralizedToo() {
        // X-Request-Id 是客户端可控的，而它可以带空格。渲染成
        //   risk_decision ... requestId=x action=BLOCK ...
        // 之后，离线脚本按空格切分取 action 时读到的是**客户端写进去的值**。
        // 这不是排版问题，是 P9.5 的指标可被外部输入伪造。
        assertEquals("a_b", LogField.safe("a b"));
        assertEquals("x_action=BLOCK", LogField.safe("x action=BLOCK"));
    }

    @Test
    @DisplayName("U+2028 / U+2029 也要挡——它们不是 ISO 控制字符，但 Python 认它们是换行")
    void unicodeLineSeparatorsAreNeutralized() {
        // 用转义写法，不写原始字符：它们是零宽的，源码里看不出来，
        // 编辑器和格式化器又可能悄悄动它们。
        //
        // 顺带一条 Java 的坑：编译器在**注释里**也处理 Unicode 转义，
        // 所以这行注释里连「反斜杠 + u」这两个字符都不能连着出现——上面那句
        // 原本写的是「用反斜杠u转义写」，编译器直接报「非法的 Unicode 逃逸」。
        assertEquals("a_b", LogField.safe("a\u2028b"));
        assertEquals("a_b", LogField.safe("a\u2029b"));
        assertEquals("a_b_c", LogField.safe("a\u2028b\u2029c"));
        // 这两个字符如果漏掉，一条客户端可控的值就能造出一整行——正是本类要堵的东西。
        assertFalse(LogField.safe("a\u2028b").contains("\u2028"));
    }

    @Test
    @DisplayName("三种不换行的 Unicode 空格也要挡——它们造不出新行，但 Python 的 split() 认它们是分隔符")
    void nonBreakingSpacesAreNeutralized() {
        // 与上一个用例是**两类不同的失效方式**，所以两条不能互相代替：
        // U+2028 能凭空造出一整行；这三个造不出新行，只是让客户端多切出一个字段
        // （X-Request-Id: x + NBSP + action=BLOCK）。而 isWhitespace 对它们恰恰是 false，
        // 于是它们从两个条件中间穿了过去——这正是本类要钉住的那道缝。
        //
        // 同样用转义写：这三个字符在编辑器里跟普通空格长得一模一样，肉眼看不出来。
        assertEquals("a_b", LogField.safe("a\u00A0b"));            // NBSP
        assertEquals("a_b", LogField.safe("a\u2007b"));            // FIGURE SPACE
        assertEquals("a_b", LogField.safe("a\u202Fb"));            // NNBSP
        assertEquals("x_action=BLOCK", LogField.safe("x\u00A0action=BLOCK"));

        // 反面样本：ZWSP 既不是换行，Python 也不拿它当分隔符，所以它**不该**被换掉。
        // 这条是「别顺手改成『把 Unicode 空白全挡上』」的守卫——那种改法在这里红。
        assertEquals("a\u200Bb", LogField.safe("a\u200Bb"));
    }

    @Test
    @DisplayName("纯空白值渲染成全下划线，不是 -")
    void whitespaceOnlyBecomesUnderscores() {
        // 刻意不合并成 EMPTY：「客户端传了一个全是空格的头」与「这个字段没填」
        // 是两回事，后者才是 -。前者伪造不出任何东西，保留它的长度反而更好排查。
        assertEquals("___", LogField.safe("   "));
    }

    @Test
    @DisplayName("脏字符落在哪个位置结果都一样——快慢两条路径给出同一个答案")
    void dirtyPositionDoesNotChangeThePath() {
        // 这是 D13 注释里那条不变式的落点：safe 有一条快路径（先扫一遍，全干净就直接
        // 返回原值）和一条慢路径（逐字符重建）。两处必须给出**完全相同**的答案，
        // 否则快路径漏判的字符会让闸门整体失效，而两条条件各自看上去都写得对。
        //
        // 三个位置各来一次：第 0 位、末位、中间。任一处快路径漏判，这里就红。
        assertEquals("_abc", LogField.safe("\nabc"));
        assertEquals("abc_", LogField.safe("abc\n"));
        assertEquals("a_b", LogField.safe("a b"));

        // 全是脏字符：慢路径的 StringBuilder 容量按原长度走，长度必须保持
        assertEquals("___", LogField.safe("\r\n\t"));
    }

    @Test
    @DisplayName("长度不因净化而改变——字段位置在肉眼和切分脚本里都不跳")
    void lengthIsPreserved() {
        // 替换而不是删除：一行日志的字段边界靠空格定位，值里少一个字符
        // 会让肉眼比对与 `cut`/`awk` 的列号悄悄错位。
        assertEquals("a b c".length(), LogField.safe("a\rb\tc").length());
    }
}
