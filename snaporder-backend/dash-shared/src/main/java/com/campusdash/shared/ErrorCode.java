package com.campusdash.shared;

/**
 * 业务错误码。抢单是高失败率场景（N 抢 1 时绝大多数请求都会失败），
 * 所以"抢不到"不是异常而是正常返回码，只有真正的异常状况才抛异常。
 */
public enum ErrorCode {

    OK("成功"),

    // 抢单结果码
    SLOT_FULL("名额已满"),
    ALREADY_GRABBED("你已抢过该任务"),
    ERRAND_NOT_GRABBABLE("任务当前不可抢"),
    DUPLICATE_REQUEST("重复请求"),
    GRAB_CONFLICT("并发冲突，抢单失败"),
    GRAB_RATE_LIMITED("当前任务过热，请稍后再试"),

    // 风控（P9.2）。三个码独立，不复用 GRAB_RATE_LIMITED —— 两者语义不同：
    // 那个说的是「当前任务过热」（按任务限流，谁赶上谁倒霉），
    // 这三个说的是「你的行为异常」（按用户风控，换个人就没事）。
    // 合并成一个码，客户端就无法区分「稍后重试」和「你被盯上了」，
    // 离线统计也分不出限流与风控各拦了多少。
    RISK_LIMITED("请求过于频繁，请稍后再试"),
    RISK_REVIEW_REQUIRED("请求已被风控标记，等待审核"),
    RISK_BLOCKED("请求已被风控拦截"),
    // 风控（P9.3）。与上面三个**方向相反**：那三个说的是「你被判成了什么」，
    // 都是对行为的判定，用户换个人就没事；这一个说的是「风控自己没给出结论」，
    // 运维要看的是 Redis 与 deadline-ms 预算，而不是某个用户的行为。
    // 混成一个码，P9.5 就分不出「拦对了多少人」和「有多少次根本没拦住」。
    RISK_UNAVAILABLE("风控服务暂不可用，请稍后再试"),

    // 资格
    CREDIT_TOO_LOW("信用分不足"),
    TOO_MANY_ONGOING("在途任务数已达上限"),

    // 资金
    INSUFFICIENT_BALANCE("余额不足"),
    ESCROW_ALREADY_EXISTS("该任务已托管"),
    ESCROW_NOT_FOUND("托管单不存在"),
    ESCROW_NOT_HELD("托管单已结算或已退款"),
    ALREADY_SETTLED("任务已结算"),
    ALREADY_REFUNDED("任务已退款"),
    SETTLE_CONFLICT("并发冲突，结算失败"),
    REFUND_CONFLICT("并发冲突，退款失败"),

    // 确认与流转
    NOT_CURRENT_GRABBER("你不是当前抢中者"),
    ALREADY_CONFIRMED("任务已确认"),
    TRANSFER_SKIPPED("流转已失效（状态或轮次不匹配）"),
    NO_CANDIDATE("候选队列为空"),

    // 状态与权限
    NOT_PUBLISHER("你不是该任务的发单人"),
    NOT_ARBITRABLE("任务当前不可仲裁"),

    // 通用
    UNAUTHORIZED("未登录或会话已过期"),
    ERRAND_NOT_FOUND("任务不存在"),
    ACCOUNT_NOT_FOUND("账户不存在"),
    ILLEGAL_STATE_TRANSITION("非法状态流转"),
    STALE_VERSION("数据已被并发修改"),
    INTERNAL_ERROR("系统内部错误");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
