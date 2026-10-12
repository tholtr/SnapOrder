package com.campusdash.it;

import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 拉一个**真的 worker 进程**起来，供 {@code RiskEventIT} 验「事件真的跨进程走通了」。
 *
 * <p>为什么不把 worker 当 Spring 容器在测试里再起一个：那样两边在同一个 JVM 里，
 * 「消费到事件了」可能只是同进程可见性造成的假象，而 {@code RiskEventIT} 要证明的恰好是
 * 「另一个进程收到了」——那正是 P9.4 与前面八个阶段唯一的形态差异。
 * 也不在测试里 {@code exec} 一个 {@code java -cp target/classes …}：那样绕开了
 * Spring Boot 的 repackage，测的就不是**发布物**了。
 *
 * <h2>隔离库：这不是「更干净」，是**必须**</h2>
 *
 * <p>worker 一启动就跑五个没有 {@code initialDelay} 的 {@code @Scheduled} 作业，
 * 其中超时扫描会把共享测试库 {@code campus_dash} 里历次 IT 留下的过期任务
 * 就地改状态（实测 2 秒内 140 个任务回退、78 个流转，并真发出 8 条 MQ 消息）。
 * 所以子进程的 {@code spring.datasource.url} 被强制指到
 * {@link #ISOLATED_DB}，并且 {@code RiskEventIT} 在启动它之前**先验证那个库存在**，
 * 不存在就跳过——用「库不存在」当作「没做隔离」的硬闸门，比靠注释提醒可靠。
 *
 * <p>Redis 没法这样隔离（{@code RedissonConfig} 只读 host/port，没有 {@code database} 项），
 * 子 worker 与测试共用 db0。这是本轮已知的边界，写在交付记录里。
 *
 * <h2>生命周期</h2>
 *
 * <p>进程跨用例复用（起一次约 10 秒，而起停只与「消费者建好了没有」有关，与用例无关）；
 * 由测试类在 {@code @AfterAll} 里显式 {@link #stop()}，并另注册一个 JVM 退出钩子兜底——
 * 少了钩子，测试 JVM 被 Ctrl-C 掉之后子 worker 会一直活着扫隔离库。
 *
 * <p>{@code stop()} 先 {@code destroy()}（SIGTERM，走 {@code @PreDestroy} 优雅关闭）等 8 秒，
 * 再 {@code destroyForcibly()}。**必须有那个 8 秒**：实测 SIGTERM 后 worker 要在
 * 六个 push consumer 的 {@code @PreDestroy} 里逐个关连接，40 秒都不一定退得干净；
 * 但那次是在共享库上跑，所以它对「能不能杀掉」这件事没有结论——
 * 这里按「先礼后兵」写，等待窗口给足但绝不无限等。
 */
final class WorkerSubprocess {

    /**
     * 子 worker 用的隔离库。名字里带 {@code _it} 是为了让 {@code SHOW DATABASES}
     * 一眼能看出它不是共享的那个。
     */
    static final String ISOLATED_DB = "campus_dash_worker_it";

    /**
     * 就绪标志：{@code RiskEventConsumer.start()} 成功建好消费者后写的那一行。
     *
     * <p>必须用**完整**的「风险事件消费者已启动」而不是「消费者已启动」——
     * worker 里还有资金/结算/超时/延迟双删四个消费者，后四个字是它们共有的后缀，
     * 拿它当标志会在别的消费者先起来时提前返回，然后在断言处以「worker 没收到」的形式
     * 失败，看起来像 MQ 有问题。
     */
    private static final String READY_MARKER = "风险事件消费者已启动";

    /** 冷启动（Spring + 6 个消费者 + 两个连接池）实测约 10 秒，给到 90 秒是留给慢机器。 */
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);

    /** SIGTERM 的等待窗口，见类注释。 */
    private static final Duration TERM_GRACE = Duration.ofSeconds(8);

    private static final Duration FORCE_WAIT = Duration.ofSeconds(5);

    /** 轮询日志文件的间隔。200ms 与「一条事件跨进程往返 100ms 上下」同量级。 */
    private static final long POLL_MILLIS = 200;

    /**
     * worker jar 真会变的那些模块——worker 依赖闭包。
     *
     * <p><b>刻意不列</b> {@code dash-presentation} / {@code dash-bootstrap} / {@code dash-bench}：
     * 它们不进 worker jar，但 {@code /src/main/} 的改动会让「jar 比源码旧」恒成立，
     * 于是只改了 app 配置也会把本用例整类跳过。假警报多了就没人再看这个守卫，
     * 而它会退化成一句没有信息量的提示。
     */
    private static final List<String> JAR_INPUT_MODULES =
            List.of("dash-shared", "dash-domain", "dash-application", "dash-infrastructure", "dash-worker");

    private static final Object LOCK = new Object();

    private static Process process;

    private WorkerSubprocess() {
    }

    // ── 守卫：能不能跑 ──────────────────────────────────────────────────────

    /**
     * jar 在，且不比它要打包进去的源码旧。
     *
     * <p>为什么把「新鲜度」也算进前置条件：{@code mvn test} 不产 jar（它停在 test 阶段），
     * 而只要有人改过 worker 或其上游、又没重新 {@code package}，跑起来的就是**旧 jar**——
     * 那种情况下的绿比红更危险（比如 {@code risk_event_processed} 行的字段改过名字，
     * 断言找不到字段会失败，而报错指向的是「worker 没消费」）。
     * 判据用 mtime，不用 {@code target/classes}：{@code -am} 会重新编译上游，
     * 拿编译产物当基准会自己触发假警报。
     */
    static boolean jarReady() {
        Optional<Path> jar = jar();
        return jar.isPresent() && lastModifiedMillis(jar.get()) >= newestInputMillis();
    }

    /** 跳过时要打印的原因（两种情况给的命令不一样，所以指得出是哪一种）。 */
    static String jarHint() {
        Optional<Path> jar = jar();
        Path target = workerTarget();
        if (jar.isEmpty()) {
            return "找不到 dash-worker 的可执行 jar（" + target + " 下没有 dash-worker-*.jar），"
                    + "跳过多进程用例。先打包：\n  mvn -q package -pl dash-worker -am -DskipTests";
        }
        return "worker jar 比源码旧（改过 worker 或它的上游，但没重新打包），跳过多进程用例。"
                + "jar=" + jar.get().getFileName() + " 打包于 " + lastModifiedMillis(jar.get())
                + "ms，而源码最新改动 " + newestInputMillis() + "ms。先重新打包：\n"
                + "  mvn -q package -pl dash-worker -am -DskipTests";
    }

    // ── 起停 ────────────────────────────────────────────────────────────────

    /**
     * 隔离库在不在 + 子 worker 起来没有。**每个用例之前**调，不是 {@code @BeforeAll}。
     *
     * <p>放在每个用例之前（而不是 {@code @BeforeAll}）：{@code jdbc} 是上下文里的 bean，
     * 而 {@code @BeforeAll} 是静态的——本包的约定是「把 bean 当参数传」，
     * 静态方法拿不到它（{@code @Nested} 不能声明静态成员，静态字段也进不来）。
     * {@code ensureStarted()} 自身幂等，重复调用不会起第二个进程。
     *
     * <p><b>位置来历</b>：本方法原本是 {@code RiskEventIT} 的私有静态方法。P9.5 的
     * {@code WorkerRestartIT} 需要同一段逻辑（它也要先证明隔离库在、再拉子进程），
     * 于是**原样搬过来**，一处都不改——只把类名换掉。复制第二份的代价不是这 20 行，
     * 而是那条跳过提示里的两个 docker 命令：两份一旦不一致，下一次看到提示的人
     * 会照着一份错误的命令去建库，然后得到「用例跳过」而不是「命令错了」。
     */
    static void requireIsolatedDbAndWorker(JdbcTemplate jdbc) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class, ISOLATED_DB);
        Assumptions.assumeTrue(found != null && found > 0,
                "隔离库 " + ISOLATED_DB + " 不存在，跳过多进程用例（子 worker 不能指向共享测试库）。"
                        + "先建库并灌初始数据：\n"
                        + "  docker exec dash-mysql mysql -uroot -pdash123456 -e \"CREATE DATABASE IF NOT EXISTS "
                        + ISOLATED_DB + " CHARACTER SET utf8mb4\"\n"
                        + "  docker exec -i dash-mysql mysql -uroot -pdash123456 " + ISOLATED_DB
                        + " < docker/init.sql");
        ensureStarted();
    }


    /** 已经活着就什么都不做（进程跨用例复用）。 */
    static void ensureStarted() {
        synchronized (LOCK) {
            if (process != null && process.isAlive()) {
                return;
            }
            start();
        }
        // 等就绪在锁外：这一段最长 90 秒，占着锁会让 JVM 退出钩子里的 stop() 干等到底。
        // 并发调用不在考虑范围内——JUnit 默认单线程跑用例。
        if (awaitLine(l -> l.contains(READY_MARKER), READY_TIMEOUT) == null) {
            String detail = tail(40);
            stop();
            throw new IllegalStateException("worker 子进程 " + READY_TIMEOUT.toSeconds()
                    + " 秒内没有报出「" + READY_MARKER + "」，日志 " + logPath() + " 尾部：\n" + detail);
        }
    }

    private static void start() {
        Path jar = jar().orElseThrow(() ->
                new IllegalStateException("worker jar 不存在，不应走到这里（调用方先过了 jarReady 守卫）"));
        Path log = logPath();
        try {
            Files.createDirectories(log.getParent());
            // 截断而不是追加：上一次运行的 risk_event_processed 行留在文件里，
            // 「本轮收到几条」这类计数就会算错，而且错得很隐蔽。
            Files.writeString(log, "");
        } catch (IOException e) {
            throw new IllegalStateException("清空 worker 日志失败：" + log, e);
        }

        ProcessBuilder pb = new ProcessBuilder(
                javaBin(), "-jar", jar.toString(),
                // 只覆盖数据源：Redis / MQ 端点 / 各消费者组名全部沿用 worker 自己的
                // application.yaml——那些**正是发布配置**，在测试里另写一套就等于没测它。
                "--spring.datasource.url=" + jdbcUrl());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("启动 worker 子进程失败：" + jar, e);
        }
        Runtime.getRuntime().addShutdownHook(
                new Thread(WorkerSubprocess::stop, "risk-event-it-worker-shutdown"));
    }

    /** 优雅停，超时则强杀。可重复调用。 */
    static void stop() {
        Process p;
        synchronized (LOCK) {
            p = process;
            process = null;
        }
        if (p == null || !p.isAlive()) {
            return;
        }
        p.destroy();
        if (!waitFor(p, TERM_GRACE)) {
            p.destroyForcibly();
            waitFor(p, FORCE_WAIT);
        }
    }

    // ── 读日志 ──────────────────────────────────────────────────────────────

    /** 子进程日志的全部行。文件还没建好时返回空表（轮询期会走到）。 */
    static List<String> lines() {
        Path log = logPath();
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        try {
            return Files.readAllLines(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 轮询等一行满足条件的日志，等到就返回它，超时返回 {@code null}。
     *
     * <p>先扫一遍再判断进程死没死：进程「写完这一行就退了」的情况必须能取到那一行。
     * 进程已经死了就提前返回——等满超时只会让失败晚 60 秒出现，而信息量一样。
     */
    static String awaitLine(Predicate<String> matcher, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (String line : lines()) {
                if (matcher.test(line)) {
                    return line;
                }
            }
            Process p = process;
            if (p != null && !p.isAlive()) {
                return null;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** 日志尾部若干行，用于失败信息——没有它，失败只剩一句「没收到」，无从下手。 */
    static String tail(int rows) {
        List<String> all = lines();
        return String.join("\n", all.subList(Math.max(0, all.size() - rows), all.size()));
    }

    static Path logPath() {
        return workerTarget().resolve("risk-event-it-worker.log");
    }

    private static String jdbcUrl() {
        return "jdbc:mysql://127.0.0.1:3307/" + ISOLATED_DB
                + "?useSSL=false&serverTimezone=Asia/Shanghai";
    }

    private static String javaBin() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static boolean waitFor(Process p, Duration d) {
        try {
            return p.waitFor(d.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── 定位产物 ────────────────────────────────────────────────────────────

    /** worker 的 target 目录。 */
    static Path workerTarget() {
        return appRoot().resolve("dash-worker").resolve("target");
    }

    /**
     * 仓库根（后端工程目录）。
     *
     * <p>从 {@code user.dir} 往上找 {@code dash-worker/pom.xml}，而不是写死
     * {@code user.dir/..}：surefire 的 {@code user.dir} 是模块目录，但从 IDE 里直接跑
     * 单个测试类时可能是工程根，写死相对层级会在其中一种环境下静默找错地方。
     */
    private static Path appRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("dash-worker").resolve("pom.xml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("从 " + dir + " 往上找不到 dash-worker/pom.xml，无法定位工程根");
    }

    private static Optional<Path> jar() {
        Path target = workerTarget();
        if (!Files.isDirectory(target)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("dash-worker-") && name.endsWith(".jar")
                                && !name.contains("-sources") && !name.contains("-javadoc");
                    })
                    // 有多个就取最新的那个（正常只有 repackage 出来的一个）
                    .max(Comparator.comparingLong(WorkerSubprocess::lastModifiedMillis));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 进 worker jar 的那些文件的最后修改时间。{@code src/main} 下的都算，{@code target} 下的都不算。 */
    private static long newestInputMillis() {
        long newest = 0;
        for (String module : JAR_INPUT_MODULES) {
            Path base = appRoot().resolve(module);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                newest = Math.max(newest, walk.filter(Files::isRegularFile)
                        .filter(WorkerSubprocess::isJarInput)
                        .mapToLong(WorkerSubprocess::lastModifiedMillis)
                        .max().orElse(0));
            } catch (IOException e) {
                // 读不到就把这个模块当作「没有更新」——守卫宁可漏一次，也不该让用例整类跳过
                return newest;
            }
        }
        return newest;
    }

    private static boolean isJarInput(Path p) {
        String path = p.toString().replace('\\', '/');
        if (path.contains("/target/")) {
            return false;
        }
        return path.contains("/src/main/") || p.getFileName().toString().equals("pom.xml");
    }

    private static long lastModifiedMillis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
