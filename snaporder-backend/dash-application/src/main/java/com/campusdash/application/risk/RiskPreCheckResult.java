package com.campusdash.application.risk;

import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskDecision;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.model.RiskOutcome;
import com.campusdash.shared.ErrorCode;

/**
 * 一次预检对调用方的交付物：**算出来的决策** + **这次算数不算数**。
 *
 * <p><b>为什么要把「决策」和「是否生效」分成两个字段，而不是只给一个动作</b>：
 * {@code SHADOW} 的定义是「计算并记录风险结果，但不拦截」（蓝图 §3.10），
 * 也就是「算了，但不算数」。若这里只暴露一个动作，{@code SHADOW} 就只能
 * 返回 {@code PASS}——那会让 {@code risk_decision} 日志行里**全部是 PASS**，
 * 等于把 SHADOW 阶段唯一的产出（真实流量的误伤率）丢掉了，
 * 而观察一段真实流量再决定要不要切 ENFORCE 正是 SHADOW 存在的理由。
 *
 * <h2>第三态：不可用（P9.3 设计 §5.3）</h2>
 *
 * <p>P9.2 把「算出来的动作」与「生效的动作」分开之后，调用方只需要读
 * {@link #effectiveAction()}——**那句话在 P9.3 之前成立，现在不成立了**。
 * 时间预算耗尽且 {@code fail-open=false} 时，这次预检的结论是「风控没给出可用结论」，
 * 而它对应的 {@link #effectiveAction()} 仍然是 {@code PASS}（决策确实是 PASS，
 * 决策本身没变，变的是「这次决策算不算数」）。于是
 * {@code effectiveAction() == PASS} 不再蕴含「放行」——调用点若继续写
 * {@code effectiveAction() != PASS}，{@code fail-open=false} 会**静默失效**，
 * 而拒绝正是这个开关唯一要做的事。
 *
 * <p><b>所以调用方唯一要读的是 {@link #denied()}</b>。它与
 * {@code errorCode() != OK} 在当前实现下等价，但保留前者是因为调用方要表达的是
 * 「这条请求要不要拒」，而不是「有没有错误码」——后者将来多一个非拒绝性质的码
 * 就会静默失效（对 {@code errorCode()} 用穷尽 {@code switch} 是同一个思路：
 * 让新增的档位在那里**编译不过**）。
 *
 * @param decision    完整的决策结果。{@code SHADOW} 下它同样是**真算出来**的，
 *                    不是 {@code PASS} 占位
 * @param enforced    本次决策是否生效。为 true 当且仅当运行模式是 {@code ENFORCE}
 *                    且风控总开关是开的
 * @param unavailable 本次预检没能给出可用结论（预算耗尽 + {@code ENFORCE} +
 *                    {@code fail-open=false} + 决策仍为 {@code PASS}）。
 *                    为真时调用方必须拒绝，见 {@link #denied()}
 * @param eventId     本次决策对应的风险事件 id（P9.4 的 D3）。**有事件时必非空**，
 *                    没有事件时是 {@code null}——但反过来不成立：{@code producesEvent()}
 *                    为假而带着 id 的形态构造器**不拦**（理由见紧凑构造器的注释）。
 *                    调用方拿它投递事件，见 {@link #toEvent(RiskOperationType, String, long, long, long)}
 */
public record RiskPreCheckResult(RiskDecision decision, boolean enforced, boolean unavailable,
                                 String eventId) {

    /**
     * 不变式：{@code unavailable} 为真时，本次预检必须是生效的（ENFORCE），
     * 且决策必须是 {@code PASS}。
     *
     * <p>两个条件分别由用例第 8 步那行条件的两半保证（{@code enforce} 与
     * {@code action == PASS}）——写进构造器是因为**它们是同一个 if 的两半，
     * 将来改一处就会静默漂移**，而漂移的后果是「不可用」被标在一个已经算出
     * {@code BLOCK} 的决策上，等于把一次该拦的请求说成「风控没结论」。
     *
     * <p>顺带挡住一类位置参数错误：{@code enforced} 与 {@code unavailable} 是
     * 相邻的两个 boolean，写反了编译期看不出来；取值不同时（本类里唯一有意义的取值
     * 组合就是「一真一假」）交换后必然触发这条不变式，当场抛。
     */
    public RiskPreCheckResult {
        if (unavailable && (!enforced || decision.action() != RiskAction.PASS)) {
            throw new IllegalArgumentException(
                    "预检结果自相矛盾：unavailable=true 时强制要求 enforced=true 且 action=PASS，"
                            + "实际为 enforced=" + enforced + "、action=" + decision.action() + "。"
                            + "「不可用」的语义是「风控没给出结论」，"
                            + "它不能长在一个已经算出拦截动作的决策上。");
        }
        // producesEvent 为真时 eventId 必须非空。**只堵这一个方向**：
        //
        //   eventId 为空而 producesEvent 为真 —— 调用方会拿 null 去建 RiskEvent，
        //   而 RiskEvent 的紧凑构造器会当场抛。那次抛出来在**投递点**上，
        //   是一个抢单请求的路径上；真正的错误却在分配这一刻。这条不变式把栈顶挪回这里。
        //
        // 反过来那个方向（有 id 而 producesEvent 为假）**刻意不拦**。它坏起来更隐蔽——
        // 日志行会报出一个永远不会存在的事件 id，P9.5 的关联对着它去找一条没有的载荷，
        // 而「找不到」只会被读成「投递丢了」，于是一个逻辑错误被记成一次传输故障。
        // 即便如此也不拦，是因为拦它等于把「已经分配了 id，后来决定不发」一并禁掉——
        // 那是将来最可能想加的一种形态（先拿 id 去记 trace，再看要不要投），
        // 而它本身并不矛盾。所以这一半的守卫放在 {@link #toEvent}：
        // 不产生事件的决策，一个事件都交不出来。
        if (decision.producesEvent() && eventId == null) {
            throw new IllegalArgumentException(
                    "预检结果自相矛盾：decision.producesEvent() 为真，eventId 却是 null。"
                            + "判据成立就必须有 id——它只按 producesEvent 分配，"
                            + "在别处再判一次就会漂移（P9.4 的 D2/D3）。");
        }
    }

    /**
     * 把这次预检变成一条待投递的风险事件（P9.4 设计 §5.4）。
     *
     * <p><b>为什么放在这里，而不是 {@code RiskEvent} 上的静态工厂</b>：设计原文写的是
     * {@code RiskEvent.of(result, ...)}，但 {@code RiskEvent} 在 {@code dash-domain}，
     * 而 {@code RiskPreCheckResult} 在 {@code dash-application}——**domain 看不见
     * application**，那个签名按字面写不出来（pom 层面就编译不过）。方向倒过来就没有问题：
     * application 看得见 domain。效果一样，且拼装这件事本来就属于应用层——
     * 它正是那个同时握着「决策」与「操作上下文」的地方。
     *
     * <p><b>{@code outcome} 由 {@code decisionAction} 推出，不用 {@link #denied()}</b>：
     * 设计 §3.3 写的就是「{@code decisionAction == PASS} 时是 PASSED」。
     * 用 {@code denied()} 在 {@code SHADOW} 下会错——那时 {@code effectiveAction()}
     * 被压成 {@code PASS}、{@code denied()} 为假，而决策本身是 {@code BLOCK}，
     * 于是载荷里会出现 {@code decisionAction=BLOCK outcome=PASSED} 这种自相矛盾的行。
     * 事件记的是**决策算出了什么**，不是**这次算不算数**（后者是 {@code enforced}，
     * 而它已经由 {@code risk_decision} 行与运行模式表达）。
     *
     * @param operationType 操作类型，本轮恒为 {@link RiskOperationType#GRAB}
     * @param requestId     触发本次操作的请求 id，会原样进载荷（序列化时转义）
     * @param errandId      任务 id
     * @param runnerId      跑腿者 id。它同时也是动作类聚合闸门的主体
     * @param occurredAt    决策发生的时刻（epoch 毫秒）。由调用方取，因为「这次操作
     *                      什么时候发生的」是调用方的知识，不是预检的
     * @throws IllegalStateException 本次预检没有事件可交付（{@code decision.producesEvent()}
     *                               为假——注意判据不是 {@code eventId} 非空，两者不等价）
     */
    public RiskEvent toEvent(RiskOperationType operationType, String requestId,
                             long errandId, long runnerId, long occurredAt) {
        if (!decision.producesEvent()) {
            throw new IllegalStateException(
                    "这次预检没有风险事件可交付：decision.producesEvent() 为假时调用方不该走到这里。"
                            + "判据是 producesEvent() 而**不是** eventId 非空——构造器允许"
                            + "「有 id 却不产生事件」的形态存在，所以带着 id 走到这里同样是错的。"
                            + "先判 producesEvent() 再调 toEvent——反过来会在这里才失败，"
                            + "而那时已经离出错的判断很远了。");
        }
        return new RiskEvent(eventId, requestId, operationType, errandId, runnerId,
                decision.action() == RiskAction.PASS ? RiskOutcome.PASSED : RiskOutcome.DENIED,
                decision.action(), decision.degraded(), decision.degradeReasons(), occurredAt);
    }

    /** 调用方真正要执行的动作：{@code SHADOW} 下恒为 {@link RiskAction#PASS}。 */
    public RiskAction effectiveAction() {
        return enforced ? decision.action() : RiskAction.PASS;
    }

    /**
     * 这次请求是否必须被拒绝——**调用方唯一要读的东西**（见类注释的「第三态」）。
     *
     * <p>{@code unavailable} 必须单独判，不能折叠进 {@link #effectiveAction()}：
     * 后者在不可用时仍然是 {@code PASS}，而那是决策的真实内容。
     */
    public boolean denied() {
        return unavailable || effectiveAction() != RiskAction.PASS;
    }

    /**
     * 生效动作对应的错误码；{@code PASS} 时为 {@link ErrorCode#OK}，
     * 不可用时为 {@link ErrorCode#RISK_UNAVAILABLE}。
     *
     * <p>用**穷尽 switch**（不写 {@code default}）而不是查表或 if 链：{@code RiskAction}
     * 将来若新增一档，这里会**编译不过**，而不是安静地落到 {@code default} 上
     * 返回一个语义不明的码。风控的失败模式里，「新档位没人处理」是最不该由
     * 线上流量来发现的一种。不可用那个分支写成 switch 之前的 {@code if}，
     * 而不是塞进 switch——塞进去就必然要写 {@code default}，上面那条保证随之作废。
     */
    public ErrorCode errorCode() {
        if (unavailable) {
            return ErrorCode.RISK_UNAVAILABLE;
        }
        return switch (effectiveAction()) {
            case PASS -> ErrorCode.OK;
            case LIMIT -> ErrorCode.RISK_LIMITED;
            case REVIEW -> ErrorCode.RISK_REVIEW_REQUIRED;
            case BLOCK -> ErrorCode.RISK_BLOCKED;
        };
    }
}
