package com.campusdash.it;

import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 空 {@code X-Request-Id} 不得让两个跑腿共用幂等槽（P9.1 §11.2）。
 *
 * <p><b>缺陷原貌</b>：{@code ErrandController.grab} 原先只判 {@code requestId == null}，
 * 空串与纯空白被原样传下去，幂等键随之退化成 {@code errand:idem:{errandId}:}。
 * 而 {@code grab.lua} 命中幂等键后只看存下来的值、<b>不看调用者是谁</b>，
 * 于是 300 秒内第二个带同样空头的请求会拿到 {@code DUPLICATE_REQUEST}，
 * 被 {@code GrabErrandUseCase} 映射成 {@code Result.success()}——
 * <b>跑腿 B 收到针对跑腿 A 所接任务的「抢单成功」</b>，不报任何错。
 *
 * <p><b>为什么必须走真实 HTTP 才测得出来</b>：这条缺陷整个活在 controller 那一行的
 * 空值判断里。直接调 {@code grabUseCase.grab(...)} 会跳过它——用例里我们自己
 * 传的 requestId 永远非空，缺陷不可能出现。所以本类用 {@code TestRestTemplate}
 * 打真实端点，让空头真的从 servlet 那一层进来。
 *
 * <p><b>为什么断言的是「失败码必须是名额裁决」而不是「失败就行」</b>：
 * {@code CREDIT_TOO_LOW}、{@code TOO_MANY_ONGOING}、{@code GRAB_RATE_LIMITED}
 * 都能让 B 拿到非 OK，但那是被更早的关卡挡住的，此时幂等那一层压根没被执行到，
 * 用例会为错误的理由变绿。只有 {@code SLOT_FULL} / {@code ALREADY_GRABBED} /
 * {@code GRAB_CONFLICT} 才证明 B 真的走到了名额裁决。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dash.credit.max-ongoing=10000"})
class RequestIdIdempotencyIT {

    static final long PUBLISHER = 1001L;
    static final long RUNNER_A = 2001L;
    static final long RUNNER_B = 2002L;

    /** 名额裁决的失败码：B 拿到其中之一，才说明它没被更早的关卡挡住、也没命中 A 的幂等槽。 */
    private static final Set<String> SLOT_VERDICTS =
            Set.of("SLOT_FULL", "ALREADY_GRABBED", "GRAB_CONFLICT");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PublishErrandUseCase publishUseCase;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(),
                "MySQL(3307)/Redis(6380) 未启动，跳过集成测试。请先执行 docker compose -f docker/docker-compose.yaml up -d");
    }

    /**
     * 每个用例前恢复发单人余额并清掉跑腿的信用流水。
     * 不清的话：上一轮用例留下的 {@code credit_score} 会让 B 的 {@code scoreOf} 偏离基准分，
     * 而 {@code TOO_MANY_ONGOING} 又会让 A 抢不中——两者都会把失败伪装成
     * 「被更早的关卡挡住」，正是上面注释里说的那种假绿。
     */
    @BeforeEach
    void resetState() {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 "
                + "WHERE owner_id = ? AND owner_type = 'USER'", PUBLISHER);
        jdbc.update("DELETE FROM credit_event WHERE user_id BETWEEN ? AND ?", RUNNER_A, RUNNER_B);
        jdbc.update("DELETE FROM credit_score WHERE user_id BETWEEN ? AND ?", RUNNER_A, RUNNER_B);
    }

    @SuppressWarnings("unchecked")
    private String login(long userId) {
        var resp = rest.postForEntity("/api/auth/login", Map.of("userId", userId), Map.class);
        assertEquals(200, resp.getStatusCode().value());
        // 响应是 Result 包装：token 在 data 里，直接取顶层 body 是 null（实测踩过）
        return (String) ((Map<String, Object>) resp.getBody().get("data")).get("token");
    }

    /**
     * 带一个显式取值的 {@code X-Request-Id} 头。
     *
     * <p>空串与纯空白都原样设进去——这正是要测的输入。空值头确实能穿透
     * {@code TestRestTemplate} 到达 servlet，已实测确认，依据见
     * {@link #blankHeaderReplayIsNotIdempotentReplay()}。
     */
    private HttpEntity<Void> grabRequest(String token, String requestIdHeader) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Request-Id", requestIdHeader);
        return new HttpEntity<>(h);
    }

    private ResponseEntity<Map> grab(long errandId, String token, String requestIdHeader) {
        return rest.exchange("/api/errands/" + errandId + "/grab", HttpMethod.POST,
                grabRequest(token, requestIdHeader), Map.class);
    }

    private String codeOf(ResponseEntity<Map> resp) {
        return (String) resp.getBody().get("code");
    }

    private long publishOneSlot() {
        var result = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, PUBLISHER, ErrandType.DELIVERY, "reqid_it_" + UUID.randomUUID(), 800L, 1));
        assertEquals(ErrandStatus.PUBLISHED, result.status());
        return result.errandId();
    }

    private Long grabberId(long errandId) {
        return jdbc.queryForObject("SELECT grabber_id FROM errand WHERE id = ?", Long.class, errandId);
    }

    /**
     * 前置探针：证明空值头确实穿透了传输层到达服务端。
     *
     * <p>没有这一条，下面两个用例可能是「因为客户端把空头丢了」而变绿——
     * 而那种情况下旧代码也会生成 UUID，缺陷根本不成立，用例再绿也说明不了任何事。
     *
     * <p>探针的做法：同一个任务，A 与 B 各带一个空头抢。修复后两者拿到不同的
     * 随机 id，B 一定会走到名额裁决；若空头在传输层被丢掉，服务端同样走 null 分支，
     * 结果一样——所以这一条<b>不能</b>单独证明头到达了。
     * 真正证明它的是 {@code git} 不可用时的手法：把 controller 的判断改回
     * {@code == null} 再跑，此用例必须转红。见类注释与 §11.2。
     */
    @Test
    @DisplayName("空 X-Request-Id 不再让两个跑腿共用幂等槽")
    void blankRequestIdDoesNotShareIdempotencySlot() {
        long errandId = publishOneSlot();
        String tokenA = login(RUNNER_A);
        String tokenB = login(RUNNER_B);

        // A 带空头抢中
        var aResp = grab(errandId, tokenA, "");
        assertEquals("OK", codeOf(aResp), "A 带空 X-Request-Id 应能正常抢中：" + aResp.getBody());

        // B 带**同一个**空头抢同一任务。修复前这里会命中 A 写下的幂等键，
        // 返回 DUPLICATE_REQUEST → 被映射成 Result.success() → B 拿到假的成功。
        var bResp = grab(errandId, tokenB, "");

        String code = codeOf(bResp);
        assertNotEquals("OK", code,
                "B 不应收到「抢单成功」——任务实际是 A 的：" + bResp.getBody());
        assertTrue(SLOT_VERDICTS.contains(code),
                "B 应当走到名额裁决才失败，实际 " + code
                        + "；这说明它被更早的关卡挡住（信用/限流/并发数），本用例没有测到幂等那一层："
                        + bResp.getBody());

        assertEquals(RUNNER_A, grabberId(errandId), "任务应当归 A");
    }

    @Test
    @DisplayName("纯空白 X-Request-Id 同样被归一化（isBlank 不是 isEmpty）")
    void whitespaceRequestIdIsAlsoNormalized() {
        // 这一条与上一条的区别只在输入形态："" 与 "   "。
        // 一份只判 isEmpty() 的修法能过上面那条、栽在这条——
        // 因为 "   " 会被原样拼进幂等键 errand:idem:{id}:   ，两个跑腿照样共用。
        long errandId = publishOneSlot();
        String tokenA = login(RUNNER_A);
        String tokenB = login(RUNNER_B);

        assertEquals("OK", codeOf(grab(errandId, tokenA, "   ")), "A 带纯空白头应能正常抢中");

        var bResp = grab(errandId, tokenB, "   ");
        String code = codeOf(bResp);
        assertNotEquals("OK", code, "B 不应收到「抢单成功」：" + bResp.getBody());
        assertTrue(SLOT_VERDICTS.contains(code),
                "B 应当走到名额裁决才失败，实际 " + code + "：" + bResp.getBody());
        assertEquals(RUNNER_A, grabberId(errandId));
    }

    /**
     * 正向对照：修好了归一化，不等于把幂等本身修没了。
     *
     * <p>同一个 requestId 重放必须仍然返回首次的成功结果（INV-3），否则
     * 客户端重试会因为「名额已被自己占掉」而拿到 SLOT_FULL，把一次成功的抢单
     * 变成一次失败——这比原缺陷更常见、破坏面更大。上两条用例只证明「不同的空头
     * 不再相撞」，这一条证明「相同的显式 id 仍然相撞」。
     */
    @Test
    @DisplayName("同一个显式 requestId 重放仍返回首次成功结果（幂等未被改坏）")
    void sameExplicitRequestIdStaysIdempotent() {
        long errandId = publishOneSlot();
        String tokenA = login(RUNNER_A);
        String requestId = UUID.randomUUID().toString();

        var first = grab(errandId, tokenA, requestId);
        assertEquals("OK", codeOf(first), "首次抢单应成功：" + first.getBody());

        var replay = grab(errandId, tokenA, requestId);
        assertEquals("OK", codeOf(replay),
                "同一 requestId 重放应返回首次的成功结果，不应变成 SLOT_FULL：" + replay.getBody());
        assertEquals(RUNNER_A, grabberId(errandId));
    }

    /**
     * 锁住一个独立性质：<b>空头重放不构成幂等重放</b>。
     * 「没传 requestId」的两次请求本来就是两次请求，不该被当成同一次重试。
     *
     * <p><b>空值头是否真能穿透传输层，已实测确认</b>（2026-09-11）：
     * 把 controller 临时改回 {@code requestId == null ? UUID... : requestId} 复跑，
     * 本类 4 条里 3 条转红，失败响应是
     * {@code {code=OK, data={code=OK, grabbed=true}}}——即 B 拿到了 A 所接任务的假成功。
     * 若空头在传输层被丢掉，服务端收到的是 {@code null}，旧代码就会走生成 UUID 的分支，
     * B 会拿到 {@code SLOT_FULL} 而不是 {@code OK}。<b>旧代码下 B 拿到的是 OK，
     * 反证空串与纯空白都原样到达了 servlet。</b>
     *
     * <p>也就是说这三条用例不是「因为输入压根没送到」而变绿的。这个反证只能靠手工改回去跑
     * 一次拿到——本仓库不是 git 仓库，没有 stash 基线可用。
     */
    @Test
    @DisplayName("空头重放不算幂等重放：第二次仍走名额裁决")
    void blankHeaderReplayIsNotIdempotentReplay() {
        long errandId = publishOneSlot();
        String tokenA = login(RUNNER_A);

        assertEquals("OK", codeOf(grab(errandId, tokenA, "")), "A 首次应抢中");

        var replay = grab(errandId, tokenA, "");
        assertNotEquals("OK", codeOf(replay),
                "空头重放不该被当成同一请求的幂等重放：" + replay.getBody());
    }
}
