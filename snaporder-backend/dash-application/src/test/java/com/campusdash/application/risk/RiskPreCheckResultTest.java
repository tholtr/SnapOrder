package com.campusdash.application.risk;

import com.campusdash.domain.risk.model.DegradeReason;
import com.campusdash.domain.risk.model.RiskAction;
import com.campusdash.domain.risk.model.RiskDecision;
import com.campusdash.domain.risk.model.RiskEvent;
import com.campusdash.domain.risk.model.RiskOperationType;
import com.campusdash.domain.risk.model.RiskOutcome;
import com.campusdash.domain.risk.model.RiskRuleId;
import com.campusdash.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交付物的三态与不变式（P9.3 设计 §5.3）+ 事件 id 的两种形态与交付（P9.4 的 D2/D3）。
 *
 * <p>本类的重点是第三态：{@code effectiveAction() == PASS} 从此**不再蕴含「放行」**。
 * 这条一旦被调用方读错，{@code fail-open=false} 就静默失效——而拒绝正是那个开关
 * 唯一要做的事。所以「不可用」的三种读法（{@code denied()} / {@code errorCode()} /
 * {@code effectiveAction()}）各钉一遍，并且把「写反了会怎样」也钉住。
 *
 * <p>P9.4 加了第四件交付物：{@code eventId}。它的判据与 {@code unavailable} 那三个
 * boolean 是同一类问题——**判据与取值的关系靠两个地方的代码维系**，所以照旧收进构造器的
 * 不变式。但它只收一个方向：**判据成立就必须有 id**；反方向（有 id 却不产生事件）
 * 刻意留白，守卫改放在 {@code toEvent}。两个方向各有一条用例，理由见各自的注释。
 */
@DisplayName("预检结果")
class RiskPreCheckResultTest {

    /** 一个代表「这次分配到了 id」的值。用固定串而非随机 UUID：断言要能逐字比对。 */
    private static final String EVENT_ID = "0f8c1e2a-7b3d-4c5e-9a1b-2c3d4e5f6a7b";

    private static final RiskDecision PASS = RiskDecision.pass("1");
    private static final RiskDecision BLOCK =
            new RiskDecision(80, RiskAction.BLOCK, List.of(RiskRuleId.BLACKLIST_USER), "1", List.of());

    /**
     * 分数为 0、动作仍是 {@code PASS}，但有一项证据没拿到。
     *
     * <p>它是本类里最重要的一个 fixture：{@link RiskDecision#producesEvent()} 的
     * 两个触发条件里，只有它单独成立的那一类。任何「按 action 判要不要发事件」的写法
     * 都会在这一类上静默漏掉——所以它必须存在。
     */
    private static final RiskDecision DEGRADED_PASS =
            RiskDecision.pass("1").withDegradeReasons(List.of(DegradeReason.BLACKLIST_FAILED));

    @Test
    @DisplayName("SHADOW：算出 BLOCK 但放行，且不是「不可用」")
    void shadowAllows() {
        RiskPreCheckResult result = new RiskPreCheckResult(BLOCK, false, false, EVENT_ID);

        assertEquals(RiskAction.BLOCK, result.decision().action(), "决策必须真算，不能退化成占位");
        assertEquals(RiskAction.PASS, result.effectiveAction());
        assertFalse(result.denied());
        assertEquals(ErrorCode.OK, result.errorCode());
    }

    @Test
    @DisplayName("ENFORCE：BLOCK 被拒")
    void enforceDenies() {
        RiskPreCheckResult result = new RiskPreCheckResult(BLOCK, true, false, EVENT_ID);

        assertTrue(result.denied());
        assertEquals(RiskAction.BLOCK, result.effectiveAction());
        assertEquals(ErrorCode.RISK_BLOCKED, result.errorCode());
    }

    @Test
    @DisplayName("不可用：生效动作仍是 PASS，但必须被拒")
    void unavailableDeniesWhileEffectiveActionStaysPass() {
        // 这就是第三态存在的全部理由。调用点若沿用 `effectiveAction() != PASS` 当判据，
        // 这一条会静默漏放——而它正是 fail-open=false 唯一要拦下的情形。
        RiskPreCheckResult result = new RiskPreCheckResult(PASS, true, true, null);

        assertEquals(RiskAction.PASS, result.effectiveAction());
        assertTrue(result.denied(), "调用方要读的是 denied()，不是 effectiveAction()");
        assertEquals(ErrorCode.RISK_UNAVAILABLE, result.errorCode());
    }

    @Test
    @DisplayName("「不可用」不能长在已经算出拦截动作的决策上")
    void unavailableRequiresAPassDecision() {
        // 预算耗尽是「少一个证据」，不是「放弃判断」。已经算出 BLOCK 的决策若被标成
        // 「风控没结论」，一次该拦的请求就被说成了风控故障。
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new RiskPreCheckResult(BLOCK, true, true, EVENT_ID));

        assertTrue(ex.getMessage().contains("unavailable"));
    }

    @Test
    @DisplayName("「不可用」不能长在未生效的预检上——顺带挡住两个相邻 boolean 写反")
    void unavailableRequiresEnforcement() {
        // (enforced, unavailable) 是相邻的两个 boolean。把正常路径的 (PASS, true, false)
        // 写成 (PASS, false, true) 编译期一声不吭，语义却完全反了；
        // 这条不变式是唯一能当场发现它的地方。
        assertThrows(IllegalArgumentException.class,
                () -> new RiskPreCheckResult(PASS, false, true, null));
    }

    // ------------------------------------------------------------------
    // eventId：判据、形态与交付（P9.4 的 D2/D3）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("降级但分数为 0 的 PASS 也要事件——只按 action 判会漏掉这一整类")
    void degradedPassStillProducesAnEvent() {
        // 这个用例锁的是 producesEvent() 的**第二个触发条件**。
        // 「挡了」才发事件是最容易想到的写法，而它会把「没挡、但证据不全」整类丢掉：
        // 那恰恰是聚合闸门专门留了 DEGRADED 一类要记下来的东西。
        RiskPreCheckResult result = new RiskPreCheckResult(DEGRADED_PASS, true, false, EVENT_ID);

        assertEquals(RiskAction.PASS, result.decision().action(), "前提：动作确实是 PASS");
        assertTrue(result.decision().producesEvent(), "前提：降级让它仍然产生事件");
        assertFalse(result.denied(), "前提：降级不是放行，它不该被拒");
        assertEquals(EVENT_ID, result.eventId());
    }

    @Test
    @DisplayName("producesEvent 为真却带 null——投递点会替这里抛，栈顶必须留在这一层")
    void eventIdMustBeAllocatedWhenAnEventIsProduced() {
        // 这个方向坏掉时，调用方拿 null 去建 RiskEvent，而 RiskEvent 的紧凑构造器
        // 会当场抛「eventId 不能为空白」。那次抛发生在**抢单请求的路径上**，
        // 离真正的错误（有人少分配了一个 id）隔了很远。
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new RiskPreCheckResult(BLOCK, false, false, null));

        assertTrue(ex.getMessage().contains("producesEvent"), ex.getMessage());
    }

    @Test
    @DisplayName("producesEvent 为假却带着 id——构造器刻意不拦，但 toEvent 交不出事件")
    void anIdWithoutAnEventIsAllowedButNotDeliverable() {
        // 这个方向**是允许存在的**，所以这里断的是「不抛」——它锁的是不变式的**边界**，
        // 不是某个取值。坏起来确实更隐蔽（这一行的 eventId 有值，于是 P9.5 的关联会拿它
        // 去找一条不存在的载荷，而「找不到」只会被读成「投递丢了」），但拦它等于把
        // 「已经分配了 id、后来决定不发」一并禁掉——那是将来最可能想加的一种形态
        // （先拿 id 去记 trace，再看要不要投），而它本身并不矛盾。
        //
        // 那一半的守卫因此在 toEvent：不产生事件的决策，一个事件都交不出来。
        RiskPreCheckResult result = new RiskPreCheckResult(PASS, false, false, EVENT_ID);

        assertEquals(EVENT_ID, result.eventId(), "id 照原样留着，不因为「不产生事件」被抹掉");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> result.toEvent(RiskOperationType.GRAB, "req-abc", 1L, 2L, 3L));
        assertTrue(ex.getMessage().contains("producesEvent"), ex.getMessage());
    }

    @Test
    @DisplayName("toEvent：字段逐个搬过去，requestId 与 occurredAt 用调用方给的")
    void toEventCarriesTheDecisionAndTheOperationContext() {
        RiskPreCheckResult result = new RiskPreCheckResult(DEGRADED_PASS, true, false, EVENT_ID);

        RiskEvent event = result.toEvent(RiskOperationType.GRAB, "req-abc", 3003L, 2001L, 1700000000123L);

        assertEquals(EVENT_ID, event.eventId(), "id 必须是分配到的那个，不是新生成一个");
        assertEquals("req-abc", event.requestId());
        assertEquals(RiskOperationType.GRAB, event.operationType());
        assertEquals(3003L, event.errandId());
        assertEquals(2001L, event.runnerId());
        assertEquals(1700000000123L, event.occurredAt());
        assertEquals(RiskAction.PASS, event.decisionAction());
        assertEquals(RiskOutcome.PASSED, event.outcome());
        assertTrue(event.degraded());
        assertEquals(List.of(DegradeReason.BLACKLIST_FAILED), event.degradeReasons());
    }

    @Test
    @DisplayName("SHADOW：outcome 跟着 decisionAction 走，跟 denied() 走会自相矛盾")
    void shadowEventIsDeniedEvenThoughTheRequestIsAllowed() {
        // 这是 toEvent 里唯一需要解释的一处。SHADOW 下 (BLOCK, enforced=false)：
        //   denied() == false          —— 因为这次决策不生效，请求确实放行了
        //   decision.action() == BLOCK —— 因为决策本身真算出了 BLOCK
        // 用 denied() 推 outcome 会产出 decisionAction=BLOCK outcome=PASSED 的载荷，
        // 而设计 §3.3 写的是「decisionAction 为 PASS 时才是 PASSED」。
        // 事件记的是**决策算出了什么**；「这次算不算数」由 enforced 与运行模式表达。
        RiskPreCheckResult result = new RiskPreCheckResult(BLOCK, false, false, EVENT_ID);

        assertFalse(result.denied(), "前提：SHADOW 下这次请求是放行的");

        RiskEvent event = result.toEvent(RiskOperationType.GRAB, "req-abc", 3003L, 2001L, 1L);

        assertEquals(RiskOutcome.DENIED, event.outcome());
        assertEquals(RiskAction.BLOCK, event.decisionAction());
    }

    @Test
    @DisplayName("不可用：请求被拒，但载荷里仍是 outcome=PASSED")
    void unavailableEventIsPassedEvenThoughTheRequestIsDenied() {
        // 上面那条的镜像。这里 denied() == true 而 decision.action() == PASS，
        // 于是 outcome=PASSED 看起来同样别扭——但两处用的是**同一条规则**：
        // outcome 只由 decisionAction 决定。若两处各按自己的直觉写，这里会是
        // DENIED、那里是 PASSED，而两份载荷放在一起看才看得出不一致。
        RiskPreCheckResult result = new RiskPreCheckResult(DEGRADED_PASS, true, true, EVENT_ID);

        assertTrue(result.denied(), "前提：不可用时请求必须被拒");

        RiskEvent event = result.toEvent(RiskOperationType.GRAB, "req-abc", 3003L, 2001L, 1L);

        assertEquals(RiskOutcome.PASSED, event.outcome());
        assertEquals(RiskAction.PASS, event.decisionAction());
        assertTrue(event.degraded(), "「为什么没结论」必须留在载荷里，否则这次拒绝对不上账");
    }

    @Test
    @DisplayName("toEvent：不可用的那次调用方压根不该走到这里，走到就当场抛")
    void toEventRefusesWhenThereIsNoEvent() {
        // 调用方必须先判 producesEvent()（**不是** eventId 非空，两者不等价——
        // 上一条用例那类「有 id 却不产生事件」的形态也走不到这里）。反过来写——先建事件再看要不要发——
        // 会在投递点上抛，而那时已经离出错的判断很远了。
        RiskPreCheckResult unavailable = new RiskPreCheckResult(PASS, true, true, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> unavailable.toEvent(RiskOperationType.GRAB, "req-abc", 1L, 2L, 3L));

        assertTrue(ex.getMessage().contains("producesEvent"), ex.getMessage());
    }

    @Test
    @DisplayName("普通 PASS 的预检没有 eventId——不打印、不投递，但结果本身是合法的")
    void plainPassCarriesNoEventId() {
        // producesEvent() 为假的那一类走到这里是**正常**的，不是缺陷：
        // 绝大多数请求都是它。所以构造不该抛，toEvent 才是那个说不的地方。
        RiskPreCheckResult result = new RiskPreCheckResult(PASS, true, false, null);

        assertNull(result.eventId());
        assertFalse(result.denied());
    }
}
