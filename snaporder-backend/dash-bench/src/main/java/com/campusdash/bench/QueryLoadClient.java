package com.campusdash.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * S2 任务列表查询压测客户端。
 *
 * 对同一数据集、页大小和页码分别测量 OFFSET 与游标分页的 HTTP 延迟。
 * 游标目标页的 token 只在测量前通过 JDBC 找到边界行，不计入 HTTP 延迟样本。
 *
 * 用法：
 *   java -cp ... com.campusdash.bench.QueryLoadClient \
 *     [baseUrl] [pageSize] [pagesCsv] [rounds] [concurrency]
 *
 * 示例：
 *   java -cp ... com.campusdash.bench.QueryLoadClient \
 *     http://127.0.0.1:8080 50 1,1000,10000 30 20
 */
public final class QueryLoadClient {

    private static final String DEFAULT_BASE = "http://127.0.0.1:8080";
    private static final long CAMPUS_ID = 1L;
    private static final String STATUS = "PUBLISHED";
    private static final long LOGIN_USER = 1001L;

    private QueryLoadClient() {
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE;
        // QueryErrandListUseCase 当前将 pageSize 限制为 50，客户端必须保持同一口径。
        int pageSize = positive(args.length > 1 ? args[1] : "50", "pageSize");
        // seed_query.sql 默认 100,000 条数据，默认只覆盖第 1 页和第 1000 页。
        // 使用第 10000 页时，应先以 query_count=1000000 重新造数。
        int[] pages = parsePages(args.length > 2 ? args[2] : "1,1000");
        int rounds = positive(args.length > 3 ? args[3] : "30", "rounds");
        int concurrency = positive(args.length > 4 ? args[4] : "1", "concurrency");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        String token = login(client, baseUrl, LOGIN_USER);

        String jdbcUrl = System.getProperty("query.jdbc.url", BenchRunRecorder.DEFAULT_JDBC_URL);
        String jdbcUser = System.getProperty("query.jdbc.user", BenchRunRecorder.DEFAULT_USER);
        String jdbcPassword = System.getProperty("query.jdbc.password", BenchRunRecorder.DEFAULT_PASSWORD);

        try (Connection jdbc = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
             BenchRunRecorder recorder = new BenchRunRecorder(jdbcUrl, jdbcUser, jdbcPassword)) {
            for (Mode mode : Mode.values()) {
                for (int page : pages) {
                    runCase(client, jdbc, recorder, baseUrl, token, mode,
                            pageSize, page, rounds, concurrency);
                }
            }
        }
    }

    private static void runCase(HttpClient client, Connection jdbc, BenchRunRecorder recorder,
                                String baseUrl, String token, Mode mode,
                                int pageSize, int page, int rounds, int concurrency) throws Exception {
        String cursor = mode == Mode.CURSOR ? cursorForPage(jdbc, pageSize, page) : null;
        String url = buildUrl(baseUrl, mode, pageSize, page, cursor);
        String runId = recorder.startRun("BENCH", "S2-" + mode.name(), concurrency,
                "查询性能对比；应用与数据库、发压端同机，页码=" + page + "，页大小=" + pageSize);

        // 预热不计入正式样本，避免连接建立和 JIT 编译污染首轮结果。
        for (int i = 0; i < Math.min(5, rounds); i++) {
            RequestResult result = request(client, url, token);
            if (!result.ok()) {
                throw new IllegalStateException("预热请求失败，url=" + url + " status=" + result.statusCode());
            }
        }

        List<Long> latencies = new ArrayList<>(rounds * concurrency);
        Map<Integer, Integer> statusCounts = new ConcurrentHashMap<>();
        int errors = 0;
        try (ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < rounds; round++) {
                List<Callable<RequestResult>> tasks = new ArrayList<>(concurrency);
                for (int i = 0; i < concurrency; i++) {
                    tasks.add(() -> request(client, url, token));
                }
                List<Future<RequestResult>> futures = pool.invokeAll(tasks);
                for (Future<RequestResult> future : futures) {
                    RequestResult result = future.get();
                    latencies.add(result.latencyMs());
                    if (!result.ok()) {
                        errors++;
                        statusCounts.merge(result.statusCode(), 1, Integer::sum);
                    }
                }
            }
        }

        Collections.sort(latencies);
        long p50 = pct(latencies, 50);
        long p95 = pct(latencies, 95);
        long p99 = pct(latencies, 99);
        long max = latencies.isEmpty() ? -1 : latencies.get(latencies.size() - 1);
        int total = latencies.size();
        boolean pass = errors == 0 && total == rounds * concurrency;

        System.out.println("================ S2 查询结果 ================");
        System.out.printf(Locale.ROOT,
                "mode=%s page=%d pageSize=%d rounds=%d concurrency=%d runId=%s%n",
                mode, page, pageSize, rounds, concurrency, runId);
        System.out.printf(Locale.ROOT,
                "requests=%d errors=%d latency P50/P95/P99/Max=%d/%d/%d/%d ms%n",
                total, errors, p50, p95, p99, max);
        if (!statusCounts.isEmpty()) {
            System.out.println("httpErrors=" + statusCounts);
        }
        System.out.println("================================================");

        String summary = String.format(Locale.ROOT,
                "{\"mode\":\"%s\",\"page\":%d,\"pageSize\":%d,"
                        + "\"rounds\":%d,\"concurrency\":%d,\"requests\":%d,"
                        + "\"errors\":%d,\"p50Ms\":%d,\"p95Ms\":%d,\"p99Ms\":%d,\"maxMs\":%d}",
                mode, page, pageSize, rounds, concurrency, total, errors, p50, p95, p99, max);
        recorder.finishRun(runId, pass ? "PASS" : "FAIL", summary);
        if (!pass) {
            throw new IllegalStateException("查询压测出现请求错误，runId=" + runId);
        }
    }

    private static RequestResult request(HttpClient client, String url, String token) {
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - started) / 1_000_000;
            boolean ok = response.statusCode() == 200 && response.body().contains("\"code\":\"OK\"");
            return new RequestResult(ok, response.statusCode(), latency);
        } catch (Exception e) {
            long latency = (System.nanoTime() - started) / 1_000_000;
            return new RequestResult(false, 599, latency);
        }
    }

    private static String cursorForPage(Connection jdbc, int pageSize, int page) throws Exception {
        if (page <= 1) {
            // 空 cursor 仍需显式传参，才能让 Controller 进入游标分页分支。
            return "";
        }
        long offset = ((long) page - 1) * pageSize - 1;
        try (PreparedStatement ps = jdbc.prepareStatement("""
                SELECT created_at, id
                  FROM errand
                 WHERE campus_id = ? AND status = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1 OFFSET ?
                """)) {
            ps.setLong(1, CAMPUS_ID);
            ps.setString(2, STATUS);
            ps.setLong(3, offset);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException(
                            "找不到目标页游标边界，请确认造数规模足够：page=" + page + " pageSize=" + pageSize);
                }
                String raw = rs.getTimestamp("created_at").toInstant().toEpochMilli()
                        + ":" + rs.getLong("id");
                return Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static String buildUrl(String baseUrl, Mode mode, int pageSize, int page, String cursor) {
        String common = baseUrl + "/api/errands?campusId=" + CAMPUS_ID
                + "&status=" + STATUS + "&size=" + pageSize;
        if (mode == Mode.OFFSET) {
            return common + "&page=" + (page - 1);
        }
        return common + "&cursor=" + (cursor == null ? "" : cursor);
    }

    private static String login(HttpClient client, String baseUrl, long userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":" + userId + "}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String marker = "\"token\":\"";
        int start = response.body().indexOf(marker);
        if (response.statusCode() != 200 || start < 0) {
            throw new IllegalStateException("登录失败：HTTP " + response.statusCode());
        }
        start += marker.length();
        int end = response.body().indexOf('"', start);
        return response.body().substring(start, end);
    }

    private static int[] parsePages(String csv) {
        String[] parts = csv.split(",");
        int[] pages = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            pages[i] = positive(parts[i].trim(), "page");
        }
        return pages;
    }

    private static int positive(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return parsed;
    }

    private static long pct(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int index = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private enum Mode {
        OFFSET,
        CURSOR
    }

    private record RequestResult(boolean ok, int statusCode, long latencyMs) {
    }
}
