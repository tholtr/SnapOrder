package com.campusdash.presentation;

import com.campusdash.application.usecase.*;
import com.campusdash.application.usecase.query.ErrandActionResolver;
import com.campusdash.application.usecase.query.GetErrandDetailUseCase;
import com.campusdash.application.usecase.query.QueryErrandListUseCase;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.presentation.risk.RiskContextResolver;
import com.campusdash.presentation.risk.RiskRequest;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务接口：发布 / 列表 / 详情 / 全部生命周期操作。
 *
 * 身份不再从 X-User-Id 裸取，而是从 AuthInterceptor 解析出的 CurrentUser 拿。
 * 前端渲染的操作按钮由详情接口的 availableActions 驱动（见 ErrandActionResolver），
 * 前端不实现第二份状态机。
 */
@RestController
@RequestMapping("/api/errands")
public class ErrandController {

    private final PublishErrandUseCase publishUseCase;
    private final GrabErrandUseCase grabUseCase;
    private final ConfirmErrandUseCase confirmUseCase;
    private final PickUpErrandUseCase pickUpUseCase;
    private final DeliverErrandUseCase deliverUseCase;
    private final SettleErrandUseCase settleUseCase;
    private final RefundErrandUseCase refundUseCase;
    private final DisputeErrandUseCase disputeUseCase;
    private final ArbitrateErrandUseCase arbitrateUseCase;
    private final QueryErrandListUseCase queryUseCase;
    private final GetErrandDetailUseCase detailUseCase;
    private final ErrandActionResolver actionResolver;
    private final ErrandRepository errandRepository;

    public ErrandController(PublishErrandUseCase publishUseCase,
                            GrabErrandUseCase grabUseCase,
                            ConfirmErrandUseCase confirmUseCase,
                            PickUpErrandUseCase pickUpUseCase,
                            DeliverErrandUseCase deliverUseCase,
                            SettleErrandUseCase settleUseCase,
                            RefundErrandUseCase refundUseCase,
                            DisputeErrandUseCase disputeUseCase,
                            ArbitrateErrandUseCase arbitrateUseCase,
                            QueryErrandListUseCase queryUseCase,
                            GetErrandDetailUseCase detailUseCase,
                            ErrandActionResolver actionResolver,
                            ErrandRepository errandRepository) {
        this.publishUseCase = publishUseCase;
        this.grabUseCase = grabUseCase;
        this.confirmUseCase = confirmUseCase;
        this.pickUpUseCase = pickUpUseCase;
        this.deliverUseCase = deliverUseCase;
        this.settleUseCase = settleUseCase;
        this.refundUseCase = refundUseCase;
        this.disputeUseCase = disputeUseCase;
        this.arbitrateUseCase = arbitrateUseCase;
        this.queryUseCase = queryUseCase;
        this.detailUseCase = detailUseCase;
        this.actionResolver = actionResolver;
        this.errandRepository = errandRepository;
    }

    public record PublishRequest(Long campusId, ErrandType type, String title,
                                 Long rewardCents, Integer slotTotal) {}

    @PostMapping
    public Result<PublishErrandUseCase.Result> publish(@RequestBody PublishRequest req) {
        long userId = CurrentUser.get();
        var cmd = new PublishErrandUseCase.Command(
                req.campusId() == null ? 1L : req.campusId(),
                userId,
                req.type() == null ? ErrandType.DELIVERY : req.type(),
                req.title(),
                req.rewardCents(),
                req.slotTotal() == null ? 1 : req.slotTotal());
        return Result.ok(publishUseCase.publish(cmd));
    }

    /** 任务广场：默认只看 PUBLISHED */
    @GetMapping
    public Result<Object> list(@RequestParam(defaultValue = "1") long campusId,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(required = false) String cursor) {
        long viewer = CurrentUser.get();
        if (cursor != null) {
            var cursorPage = queryUseCase.listByCursor(campusId, status, cursor, size);
            return Result.ok(Map.of(
                    "items", cursorPage.items().stream().map(e -> toCard(e, viewer)).toList(),
                    "nextCursor", cursorPage.nextCursor() == null ? "" : cursorPage.nextCursor()));
        }
        return Result.ok(queryUseCase.list(campusId, status, page, size).stream()
                .map(e -> toCard(e, viewer))
                .toList());
    }

    /** 我的任务：我发布的 / 我抢的 */
    @GetMapping("/mine")
    public Result<List<Map<String, Object>>> mine(@RequestParam(defaultValue = "PUBLISHED_BY_ME") String role,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        long userId = CurrentUser.get();
        List<Errand> list = "GRABBED_BY_ME".equals(role)
                ? queryUseCase.myGrabbed(userId, page, size)
                : queryUseCase.myPublished(userId, page, size);
        return Result.ok(list.stream().map(e -> toCard(e, userId)).toList());
    }

    /**
     * 详情：带 availableActions，前端据此渲染按钮。
     *
     * 走缓存（P5）：任务的静态与状态字段来自 Cache Aside 链路；
     * availableActions 与 role 不进缓存——它们依赖"谁在看"，
     * 缓存进去会导致 A 用户看到 B 用户的按钮集合（缓存串号）。
     * 这是缓存设计里最容易出事的一类：**凡是与请求者身份相关的派生数据都不能进共享缓存**。
     */
    @GetMapping("/{errandId}")
    public Result<Map<String, Object>> detail(@PathVariable long errandId) {
        long viewer = CurrentUser.get();
        var cachedJson = detailUseCase.detailJson(errandId);
        if (cachedJson.isEmpty()) {
            return Result.fail(ErrorCode.ERRAND_NOT_FOUND);
        }
        // 动作集合按查看者实时计算：需要聚合本体做身份判定，这一步不走缓存
        return errandRepository.findById(errandId)
                .map(e -> Result.ok(toCard(e, viewer)))
                .orElseGet(() -> Result.fail(ErrorCode.ERRAND_NOT_FOUND));
    }

    /** 状态时间线（事件溯源的展示面） */
    @GetMapping("/{errandId}/timeline")
    public Result<List<Map<String, Object>>> timeline(@PathVariable long errandId) {
        CurrentUser.get();
        return Result.ok(queryUseCase.statusLog(errandId).stream()
                .map(c -> Map.<String, Object>of(
                        "time", c.time().toString(), "from", c.from(), "to", c.to(),
                        "round", c.round(), "operatorId", c.operatorId()))
                .toList());
    }

    /**
     * 抢单。requestId 由客户端传入做幂等，重试用同一个值。
     * 并发抢单演示时前端会同时发 N 个请求、各自带不同 requestId。
     *
     * <p>空白归一化交给 {@link RiskContextResolver#requestIdOf}，本类不自己判。
     * 曾在这里写成 {@code requestId == null ? UUID... : requestId}——<b>空串与纯空白
     * 会被原样放行</b>，幂等键随之退化成 {@code errand:idem:{errandId}:}，
     * 该任务下所有带空头的请求共用一个槽。又因为 {@code grab.lua} 命中幂等键后
     * 只看存下来的值、不看调用者，跑腿 B 会收到针对跑腿 A 所接任务的「抢单成功」。
     * 归一化口径与风控去重成员同源，两处不会再分叉。
     *
     * <h2>requestId 只解析一次（P9.3 决策 D4）</h2>
     *
     * <p>上面那个缺陷修复统一了**函数**（两处都调 {@code requestIdOf}），但没有统一
     * **取值**：{@code ErrandController} 调一次、{@code RiskContextInterceptor} 再调一次，
     * 而 {@code requestIdOf} 在头缺失或空白时每次生成一个新 UUID。于是对**不发
     * {@code X-Request-Id} 头的客户端**，两条链路拿到的是两个不同的字符串。
     *
     * <p>为什么 P9.3 之前无害、之后有害：{@code requestId} 是两条链路之间的**关联键**。
     * P9.4 的风险事件靠它标识「哪一次业务操作」，P9.5 的离线统计按
     * {@code requestId + operationType} 去重。接入之后，{@code risk_decision} 行里的
     * {@code requestId} 与 {@code errand:idem:} 键里的那个值不是同一个字符串，
     * 「这条风控决策对应哪一次抢单」在数据上就无法回答——而按实施计划 §9.4，
     * 本轮前端不发这个头，等于对全部真实流量都断了这条关联。
     *
     * <p><b>优先取 attribute</b>：拦截器已经把「这次请求是哪个请求」算过一次，且它同时
     * 就是风控链路用的那个值，复用它是唯一能保证两条链路同值的做法。
     * <b>attribute 缺失时才回退</b>：拦截器在三种情况下不写 attribute——风控总开关关闭、
     * 该路径没有已认证用户、解析抛异常（它记一条 warn 后放行）。前两种情况下风控链路
     * 根本不存在（不会产生 {@code risk_decision} 行），第三种已经被记了 warn。
     * 回退分支只为「拦住 500」而存在，不改变任何一条已生效链路的口径。
     *
     * <p>两条链路仍然共用 {@code requestIdOf} 这一个函数（D-2 的成果不丢），
     * 只是不再各调一次。
     */
    @PostMapping("/{errandId}/grab")
    public Result<GrabErrandUseCase.Result> grab(
            @PathVariable long errandId,
            @RequestAttribute(value = RiskRequest.ATTR, required = false) RiskRequest risk,
            @RequestHeader(value = RiskContextResolver.REQUEST_ID_HEADER,
                    required = false) String requestId) {
        long runnerId = CurrentUser.get();
        var cmd = new GrabErrandUseCase.Command(
                errandId, runnerId,
                risk != null ? risk.requestId() : RiskContextResolver.requestIdOf(requestId),
                60,
                risk != null ? risk.deviceId() : null,
                risk != null ? risk.clientIp() : null);
        GrabErrandUseCase.Result result = grabUseCase.grab(cmd);
        return result.grabbed() ? Result.ok(result) : Result.fail(result.code(), result);
    }

    @PostMapping("/{errandId}/confirm")
    public Result<Void> confirm(@PathVariable long errandId) {
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, CurrentUser.get()));
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/pickup")
    public Result<Void> pickup(@PathVariable long errandId) {
        pickUpUseCase.pickUp(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/deliver")
    public Result<Void> deliver(@PathVariable long errandId) {
        deliverUseCase.deliver(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/settle")
    public Result<Map<String, Object>> settle(@PathVariable long errandId) {
        var r = settleUseCase.settle(errandId, CurrentUser.get());
        return Result.ok(Map.of("result", r.name()));
    }

    @PostMapping("/{errandId}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable long errandId) {
        var r = refundUseCase.cancelAndRefund(errandId, CurrentUser.get());
        return Result.ok(Map.of("result", r.name()));
    }

    @PostMapping("/{errandId}/dispute")
    public Result<Void> dispute(@PathVariable long errandId) {
        disputeUseCase.raise(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    public record ArbitrateRequest(String favor) {}

    @PostMapping("/{errandId}/arbitrate")
    public Result<Map<String, Object>> arbitrate(@PathVariable long errandId,
                                                 @RequestBody ArbitrateRequest req) {
        var favor = "PUBLISHER".equalsIgnoreCase(req.favor())
                ? ArbitrateErrandUseCase.Favor.PUBLISHER
                : ArbitrateErrandUseCase.Favor.RUNNER;
        var r = arbitrateUseCase.arbitrate(errandId, favor, CurrentUser.get());
        return Result.ok(Map.of("result", r.name()));
    }

    /** 统一的任务卡片：列表与详情共用，保证 availableActions 处处一致 */
    private Map<String, Object> toCard(Errand e, long viewer) {
        var resolved = actionResolver.resolve(e, viewer);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", e.id());
        card.put("title", e.title());
        card.put("status", e.status().name());
        card.put("type", e.type().name());
        card.put("rewardCents", e.reward().cents());
        card.put("slotTotal", e.slotTotal());
        card.put("slotTaken", e.slotTaken());
        card.put("publisherId", e.publisherId());
        card.put("grabberId", e.grabberId() == null ? -1L : e.grabberId());
        card.put("round", e.round());
        card.put("role", resolved.role());
        card.put("availableActions", resolved.actions());
        return card;
    }
}
