package com.campusdash.it;

import com.campusdash.application.usecase.PublishErrandUseCase;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.risk.model.RiskEventGateType;
import com.campusdash.infrastructure.risk.DeviceHasher;
import com.campusdash.infrastructure.risk.RiskKeys;
import com.campusdash.infrastructure.risk.RiskProperties;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 风控类集成测试共用的夹具动作：发单、真实 HTTP 抢单、按用户清理 Redis 键。
 *
 * <p><b>为什么是这些方法、而不是一个抽象基类</b>：本包既有四个共享助手类
 * （{@code MiddlewareAvailable} / {@code BenchRun} / …），全是「同包的 final 类 + 静态方法」，
 * 没有一个是基类。这条约定在 {@code RiskGrabLinkIT} 的类注释里写着：{@code @Nested} 非静态
 * 内部类不能声明静态成员，所以共用工具只能写成静态方法、把 bean 当参数传。
 * 本类把这条约定显式化成一处可引用的地方，而不是让每个 IT 各抄一份。
 *
 * <h2>两个使用方的差异决定了方法的形状</h2>
 *
 * <p>方法一律**把 bean 当参数收**，不自己持有：两个使用方各在各自的 Spring 上下文里，
 * 它们的 {@code StringRedisTemplate} / {@code JdbcTemplate} 不是同一个对象。
 * 同理，用户 id 段（各 IT 私有的 {@code USER_BASE}）留在各自类里，这里只收参数——
 * 编号段是**每个 IT 之间的隔离手段**，把它收进共享类等于把隔离取消掉。
 *
 * <p><b>只有 {@link #PUBLISHER} 是共享的</b>：它是仓库级的固定账号
 * （{@code CreditIT} / {@code ApiEndpointIT} 等也用它），钱包余额由
 * {@link #resetPublisherBalance(JdbcTemplate)} 每次用例前恢复，所以两个 IT 共用它不会互相影响。
 *
 * <h2>本类的方法全部是从 {@code RiskGrabLinkIT} 里**原样搬过来**的</h2>
 *
 * <p>搬动时只改了 javadoc，没有改行为——{@code RiskGrabLinkIT} 是 P9.3 的验收证据，
 * 它的断言一个都没动。判据（{@code idemKey} 的格式、{@code purgeUser} 的那几个键）
 * 仍然按原注释的理由写在这里：它们是**镜像**，不是新定义。
 */
final class ItFixtures {

    /**
     * 全仓多个 IT 共用的发单人。
     *
     * <p>发单要冻结赏金，所以每个用例前必须把余额恢复（{@link #resetPublisherBalance}），
     * 否则上一个用例冻结的钱会让下一个用例的发单或抢单失败。
     */
    static final long PUBLISHER = 1001L;

    private ItFixtures() {
    }

    /**
     * 幂等键格式，**镜像** {@code RedisGrabSlotAdapter.idemKey}（那是私有的）。
     *
     * <p>写在这里而不是加一个公开访问器：那个方法是实现细节，为了测试把它变成公开 API
     * 是反过来的。格式真要改，使用它的几个 IT 会一起红——那正是想要的提醒。
     */
    static String idemKey(long errandId, String requestId) {
        return "errand:idem:{" + errandId + "}:" + requestId;
    }

    /** 发一个指定名额的 DELIVERY 任务，返回它的 id。 */
    static long publishSlots(PublishErrandUseCase publish, int slots) {
        var result = publish.publish(new PublishErrandUseCase.Command(
                1L, PUBLISHER, ErrandType.DELIVERY, "risk_link_" + UUID.randomUUID(), 800L, slots));
        assertEquals(ErrandStatus.PUBLISHED, result.status());
        return result.errandId();
    }

    /** 抢单要冻结发单人赏金，每个用例前把余额恢复，避免用例间相互影响。 */
    static void resetPublisherBalance(JdbcTemplate jdbc) {
        jdbc.update("UPDATE wallet_account SET available = 100000, frozen = 0 "
                + "WHERE owner_id = ? AND owner_type = 'USER'", PUBLISHER);
    }

    /**
     * 清掉某个用户会用到的那几个风控键。{@code deviceId} 非空时连设备键一起清。
     *
     * <p>开头清而不是只在收尾清：上一轮崩溃留下的键会一直躺在共享测试库里，
     * 而窗口计数偏大意味着**下一次运行会更容易越线**，看起来像用例本身不稳。
     * 黑名单键没有 TTL，只有「下次运行开头清掉」能救它。
     */
    static void purgeUser(StringRedisTemplate redis, RiskProperties props,
                          DeviceHasher hasher, long userId, String deviceId) {
        List<String> keys = new ArrayList<>(List.of(
                RiskKeys.userWindow(userId, props.userWindow().shortSeconds()),
                RiskKeys.userWindow(userId, props.userWindow().longSeconds()),
                RiskKeys.limitKey(userId),
                RiskKeys.blacklistUser(userId)));
        if (deviceId != null) {
            keys.add(RiskKeys.deviceCluster(hasher.hash(deviceId)));
        }
        redis.delete(keys);
    }

    /**
     * 清掉该用户的三个**动作类**事件聚合闸门键（P9.4 的 {@code risk:event:gate:*}）。
     *
     * <p>{@link #purgeUser} 清的是决策用的键，这几个是**投递**用的键——它们的 TTL 只有
     * 10 秒（{@code LIMIT}）/ 60 秒（{@code REVIEW}、{@code BLOCK}），但一个用例刚跑完
     * 就到下一个用例、中间没有 10 秒，于是「这个用户今天第一条 LIMIT 事件」这条前提
     * 会带着上一个用例的残留。聚合开启时，残留让本该 {@code SENT} 的那条变成
     * {@code SUPPRESSED}，用例以「没收到消息」的形式失败，而原因与它要验的东西无关。
     *
     * <p><b>{@code DEGRADED} 不在这里</b>：它的主体是**实例**不是用户（{@code RiskKeys}
     * 里两个重载的分工），所以按用户清不掉——需要它的用例自己按 {@code instance-id} 清。
     */
    static void purgeEventGates(StringRedisTemplate redis, long userId) {
        redis.delete(List.of(
                RiskKeys.eventGate(RiskEventGateType.LIMIT, userId),
                RiskKeys.eventGate(RiskEventGateType.REVIEW, userId),
                RiskKeys.eventGate(RiskEventGateType.BLOCK, userId)));
    }

    /** 用真 HTTP 登录，返回 token。 */
    static String login(TestRestTemplate rest, long userId) {
        var resp = rest.postForEntity("/api/auth/login", Map.of("userId", userId), Map.class);
        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        return (String) data.get("token");
    }

    /**
     * 抢单请求。{@code requestIdHeader} 为 {@code null} 时**不带** {@code X-Request-Id}——
     * 那正是服务端自己生成 {@code requestId} 的那条分支，也是本轮真实前端走的分支。
     */
    static HttpEntity<Void> grabRequest(String token, String requestIdHeader) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        if (requestIdHeader != null) {
            h.set("X-Request-Id", requestIdHeader);
        }
        return new HttpEntity<>(h);
    }
}
