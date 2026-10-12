package com.campusdash.shared;

/**
 * 日志行里**自由文本字段**的净化。
 *
 * <p><b>为什么只有自由文本需要过这里</b>：风控日志行的其余字段要么是数字
 * （{@code userId}、{@code riskScore}、各种计数、{@code lagMs}），要么是枚举名
 * （{@code action}、{@code matchedRules}、{@code degradeReasons}、{@code operationType}）。
 * 枚举名由代码定义，不含空白与控制字符，天然无法伪造出一行新日志。
 * 而 {@code requestId} 来自客户端、{@code ruleVersion} 来自配置——两者都是**外部输入**，
 * 含换行的值会把一行日志劈成两行，而这一行的消费方是离线分析脚本：
 * 一条伪造的 {@code risk_decision} 就能污染指标。
 *
 * <p><b>为什么所有日志行共用这一个实现、而不是各写一份</b>：这是个安全相关的方法，
 * 复制一份迟早会漂移，而漂移的后果是其中一条日志重新变得可伪造——不报错、不告警，
 * 只有被利用时才看得出来。Tomcat 本身会拒绝含裸 CR/LF 的请求头，所以这道闸平时用不上；
 * 但「上游已经拦住了」是外部实现细节，换个容器、或换个来源（P9.3 的来源不只 HTTP 头）
 * 就不成立了。
 *
 * <p><b>它为什么在 {@code dash-shared}</b>（P9.4）：D13 要把同一道闸接到四条新日志行上，
 * 而它们分散在三个模块里——{@code RiskDecisionLog} / {@code RiskFeatureLog} 在
 * {@code dash-application}，{@code risk_event_publish} 由 {@code dash-infrastructure} 的适配器写，
 * {@code risk_event_processed} 由 {@code dash-worker} 的处理器写。{@code dash-infrastructure}
 * **看不见** {@code dash-application}（pom 层面就看不见），所以这个类原先所在的包
 * 是三个使用方里有一个够不着的。把它放到最底层的共享模块是唯一能**结构性**排除
 * 「再抄一份」的办法——与 P9.1 把 {@code DeviceHasher} 交出去给 {@code dash-bench} 复用
 * 是同一个理由：抄一份的后果是两边算出的东西不一致，而且完全无感。
 *
 * <p><b>只替换、不转义</b>：保留可读性（{@code a\r\n\tb} → {@code a___b}），
 * 同时保证整行一定是一行。
 *
 * <p><b>空白也一并中性化（P9.4 决策 D13）</b>：一行日志是空格分隔的 {@code key=value}，
 * 而 {@code requestId} 来自客户端的 {@code X-Request-Id} 头——它可以带空格。于是
 * {@code X-Request-Id: x action=BLOCK} 会渲染出
 *
 * <pre>
 *   risk_decision userId=1 ... requestId=x action=BLOCK ... requestId=x action=PASS ...
 * </pre>
 *
 * <p>离线脚本按空格切分取 {@code action} 时读到的是**客户端写进去的值**。这不是日志难看，
 * 是**指标可被外部输入伪造**——而风控的整份效果报告都建立在这些行上。挡空格的理由
 * 与挡 CR/LF 的理由不同：Tomcat 拒绝含裸 CR/LF 的请求头（所以那道闸平时用不上），
 * 但它**不拒绝**含空格的请求头。
 *
 * <p><b>范围不是「ASCII 空格」，而是三个谓词的并集</b>——多出来的每一个都不是顺手扩大。
 * 它们分两类，**失效方式不同，所以要挡的东西也不同**：
 * <ul>
 *   <li>{@code U+2028}（LINE SEPARATOR）与 {@code U+2029}（PARAGRAPH SEPARATOR）**非** ISO
 *       控制字符，{@code isISOControl} 放行它们，但 Python 的 {@code str.splitlines()} 把它们
 *       当换行——一条客户端可控的值能凭空造出**一整行**。这类由
 *       {@link Character#isWhitespace} 收住。</li>
 *   <li>{@code U+00A0}（NBSP）、{@code U+2007}（FIGURE SPACE）、{@code U+202F}（NNBSP）
 *       反而**不**是换行（{@code isWhitespace} 对它们为 false，所以曾从这里穿过去），
 *       但 Python 的 {@code str.split()} 认它们是分隔符——{@code X-Request-Id: x + NBSP +
 *       action=BLOCK} 照样能多切出一个字段。这类只有 {@link Character#isSpaceChar} 收得住。</li>
 * </ul>
 *
 * <p>{@code isSpaceChar} 对上面那三个都为 true，而对**真正切不开**的 {@code U+200B}（ZWSP）
 * 为 false——这是选它而不是「把 Unicode 空白全挡上」的理由：后者会把零宽字符也一并换掉，
 * 而它既造不出新行也切不出新字段，挡它不会更安全、只会让值更难认。
 * 代价是含空白的值显示为下划线。
 */
public final class LogField {

    /** 空值与未填充字段的统一占位。让字段边界在肉眼和切分脚本里都看得见。 */
    public static final String EMPTY = "-";

    private LogField() {
    }

    public static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return EMPTY;
        }
        boolean clean = true;
        for (int i = 0; i < value.length(); i++) {
            if (isNeutralized(value.charAt(i))) {
                clean = false;
                break;
            }
        }
        if (clean) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(isNeutralized(c) ? '_' : c);
        }
        return sb.toString();
    }

    /**
     * 单个字符是否需要被替换成 {@code _}。控制字符与三类空白都要——三个谓词各自负责什么，
     * 见类注释里那张两类失效方式的说明，**别把任意一个删掉当成简化**。
     *
     * <p><b>为什么抽成一个方法，而不是在上面那两处各写一遍条件</b>：两处必须给出
     * **完全相同**的答案。若快路径漏判了某一类字符（比如只查控制字符），它就会把这个值
     * 判成「干净的」直接原样返回，慢路径根本没机会跑——闸门整体失效，而两条条件各自
     * 看上去都写得对。D13 给这个方法加空白、以及紧随其后补上 {@code isSpaceChar}，
     * 都是这一类「两处要同时改」的改动，所以把它收成一处，让「漏改一处」不再是一种失误。
     *
     * <p>纯空白值（如 {@code "   "}）会被渲染成 {@code ___} 而不是 {@link #EMPTY}。
     * 刻意不合并：它与「字段没填」是两回事，伪造不出任何东西，而 {@code -} 会让人以为
     * 客户端没传这个头。
     */
    private static boolean isNeutralized(char c) {
        return Character.isISOControl(c)          // 换行、回车、制表符……
                || Character.isWhitespace(c)      // ASCII 空格 + U+2028 / U+2029 / U+3000
                || Character.isSpaceChar(c);      // 补 U+00A0 / U+2007 / U+202F（不间断空格一类）
    }
}
