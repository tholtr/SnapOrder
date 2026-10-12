package com.campusdash.worker;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * worker 侧测试的中间件探测。
 *
 * <p><b>为什么这里有一份、dash-bootstrap 里还有一份</b>：{@code com.campusdash.it.MiddlewareAvailable}
 * 是 **dash-bootstrap 测试源里的包级私有类**，跨模块看不见；仓库也没有 test-jar 或共享测试模块
 * （27 个 IT 全在 dash-bootstrap，此前没有跨模块复用测试工具的需求）。为一个 20 行的端口探测
 * 新建一个模块不成比例，所以这里是本模块自己的一份。<b>两边的端口必须一致</b>——它们描述的是
 * 同一个 docker-compose，改端口时两处一起改（端口本来就在各份 yaml 里重复出现，这是既成事实）。
 *
 * <p><b>为什么 worker 的上下文测试必须有守卫</b>：{@code RedissonConfig} 是无条件的
 * {@code @Configuration}，它的 {@code redissonClient()} 工厂方法**在 bean 创建时就连接**
 * （实测：把 redis 指向死端口，上下文刷新直接失败，报
 * {@code Unable to connect to Redis server}）。所以「给 worker 写一个不需要中间件的
 * 上下文测试」这条路是堵死的——即便 {@code dash.mq.enabled=false}。
 * 这与 dash-bootstrap 的 27 个 IT 是同一个处境，处理方式也照抄：**中间件没起就整类跳过，
 * 而不是变红**，保证任何环境下 {@code mvn test} 都不会因为没起 Docker 而失败。
 */
final class WorkerMiddleware {

    private WorkerMiddleware() {
    }

    static boolean check() {
        return portOpen("127.0.0.1", 3307) && portOpen("127.0.0.1", 6380);
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
