package com.campusdash.it;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 集成测试依赖 docker-compose 起的常驻 MySQL/Redis（外加 RocketMQ，只对需要 broker 的用例）。
 *
 * 为什么不用 Testcontainers：compose 已经把容器起好了，测试直连常驻容器可以省掉
 * 每次跑测试都拉起/销毁容器的几十秒开销。代价是需要先 docker compose up，
 * 所以这里做端口探测，中间件没起时整类跳过（skip 而不是 fail），
 * 保证在任何环境下 mvn test 都不会因为没起 Docker 而变红。
 */
final class MiddlewareAvailable {

    private MiddlewareAvailable() {
    }

    static boolean check() {
        return portOpen("127.0.0.1", 3307) && portOpen("127.0.0.1", 6380);
    }

    /**
     * RocketMQ 可达性。**与 {@link #check()} 分开而不是并进去**：全仓只有
     * {@code RiskEventIT} 一个用例需要 broker（它是唯一一个 {@code dash.mq.enabled=true}
     * 的上下文），把 8081 并进 {@code check()} 会让另外 27 个 IT 凭空多一个前置条件——
     * 没起 broker 时它们本可以照常跑，却会整类跳过。
     *
     * <p>探的是 <b>8081</b>（gRPC proxy），不是 9876（nameserver）：应用连的是前者，
     * 探 9876 会得到一个「9876 通但应用连不上」的假绿——nameserver 起着而 proxy
     * 没起是一个真实出现过的组合（见 {@code docker/docker-compose.yaml} 里的两个服务）。
     */
    static boolean mqReachable() {
        return portOpen("127.0.0.1", 8081);
    }

    private static boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
