package com.campusdash.domain.risk.model;

/**
 * 风险事件对应的业务操作类型（P9.4 设计 §3.1）。
 *
 * <p>蓝图 §3.9 要求 {@code requestId + operationType} 共同标识一次业务操作。这个组合
 * 之所以需要两个字段，正是因为「同一个 requestId 可能对应多类操作」——同一个请求
 * 先抢单、失败后重新发起，操作的种类不同，它们不该被离线脚本当成同一条。
 *
 * <p><b>本轮只有一个值 {@code GRAB}，仍然做成枚举</b>（而不是字符串常量），买到的是：
 * 日志与载荷里只会出现枚举名（不含空白与控制字符，天然不可伪造，见 {@code LogField}）；
 * 离线脚本的取值范围是封闭的；新增操作类型必须改代码，于是必须被评审看见。
 *
 * <p><b>声明顺序即日志顺序</b>，与 {@link DegradeReason}、{@link RiskRuleId} 同一约定：
 * 同一组值产出的文本必须稳定，否则离线去重会把同一条当成两条。**新值只能追加在末尾。**
 */
public enum RiskOperationType {

    /** 抢单（`POST /errands/{id}/grab`）。本轮唯一会产出风险事件的操作。 */
    GRAB
}
