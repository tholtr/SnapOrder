package com.campusdash.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S2 稳态抢单压测客户端。
 *
 * 做法：先准备一批 PUBLISHED 任务，再把请求轮转到任务池里，避免所有流量都打到单个售罄任务。
 *
 * 用法：
 *   java -cp ... com.campusdash.bench.GrabRampLoadClient [baseUrl] [stageCsv] [durationSeconds] [taskPoolSize] [slotTotal]
 */
public final class GrabRampLoadClient {

    private static final String DEFAULT_BASE = "http://127.0.0.1:8080";
    private static final long PUBLISHER_ID = 1001L;
    private static final String TITLE_PREFIX = "bench_ramp_grab_";
    private static final long WARMUP_USER_ID = 5_000_000L;

    private GrabRampLoadClient() {
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE;
        int[] stages = parseStages(args.length > 1 ? args[1] : "100,200,400,800,1600");
        int durationSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 60;
        int taskPoolSize = args.length > 3 ? Integer.parseInt(args[3]) : 500;
        int slotTotal = args.length > 4 ? Integer.parseInt(args[4]) : 1000;
        Distribution distribution = parseDistribution(args.length > 5 ? args[5] : "uniform");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        for (int stage : stages) {
            runStage(client, baseUrl, stage, durationSeconds, taskPoolSize, slotTotal, distribution);
        }
    }

    private static void runStage(HttpClient client, String baseUrl, int concurrency,
                                 int durationSeconds, int taskPoolSize, int slotTotal,
                                 Distribution distribution) throws Exception {
        BenchRunRecorder recorder = new BenchRunRecorder();
        String runId = recorder.startRun("BENCH", "S2-GRAB", concurrency,
                "稳态跨任务抢单；应用/中间件/发压端同机；taskPool=" + taskPoolSize
                        + ", slotTotal=" + slotTotal + ", dist=" + distribution.name().toLowerCase());

        List<Long> errandIds = seedTasks(client, baseUrl, taskPoolSize, slotTotal);
        recorder.trackErrands(runId, errandIds);

        // 先做短预热，避免把连接建立和 JIT 统计到正式样本里。
        warmup(client, baseUrl, Math.min(15, Math.max(3, durationSeconds / 6)), Math.min(64, concurrency));

        AtomicInteger success = new AtomicInteger();
        AtomicInteger slotFull = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();
        java.util.Map<String, AtomicInteger> errorTypes = new ConcurrentHashMap<>();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong requestSeq = new AtomicLong();
        AtomicLong runnerSeq = new AtomicLong(2_000_000L);

        long measureStart = System.nanoTime();
        long deadline = measureStart + TimeUnit.SECONDS.toNanos(durationSeconds);
        CountDownLatch done = new CountDownLatch(concurrency);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    try {
                        while (System.nanoTime() < deadline) {
                            long seq = requestSeq.incrementAndGet();
                            long errandId = selectErrandId(errandIds, seq, distribution);
                            long runnerId = runnerSeq.incrementAndGet();
                            HttpRequest req = HttpRequest.newBuilder()
                                    .uri(URI.create(baseUrl + "/api/errands/" + errandId + "/grab"))
                                    .header("X-User-Id", String.valueOf(runnerId))
                                    .header("X-Request-Id", UUID.randomUUID().toString())
                                    .header("Content-Type", "application/json")
                                    .timeout(Duration.ofSeconds(10))
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .build();
                            long started = System.nanoTime();
                            try {
                                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                                long latencyMs = (System.nanoTime() - started) / 1_000_000;
                                latencies.add(latencyMs);
                                switch (classify(resp.statusCode(), resp.body())) {
                                    case SUCCESS -> success.incrementAndGet();
                                    case SLOT_FULL -> slotFull.incrementAndGet();
                                    case GRAB_CONFLICT -> conflict.incrementAndGet();
                                    case RATE_LIMITED -> rateLimited.incrementAndGet();
                                    case ERROR -> error.incrementAndGet();
                                }
                            } catch (Exception e) {
                                error.incrementAndGet();
                                String type = e.getClass().getSimpleName()
                                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                                errorTypes.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            if (!done.await(durationSeconds + 120L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("稳态抢单未在限定时间内完成 concurrency=" + concurrency);
            }
        }

        long elapsedMs = Math.max(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - measureStart), 1);
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        int total = success.get() + slotFull.get() + conflict.get() + rateLimited.get() + error.get();
        long qps = Math.round(total * 1000.0 / elapsedMs);
        long p50 = pct(sorted, 50);
        long p95 = pct(sorted, 95);
        long p99 = pct(sorted, 99);
        long max = sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1);

        System.out.println("================ S2 稳态抢单结果 ================");
        System.out.printf("runId=%s concurrency=%d duration=%ds%n", runId, concurrency, durationSeconds);
        System.out.printf("taskPool=%d slotTotal=%d%n", taskPoolSize, slotTotal);
        System.out.printf("requests=%d success=%d slotFull=%d conflict=%d rateLimited=%d errors=%d qps=%d%n",
                total, success.get(), slotFull.get(), conflict.get(), rateLimited.get(), error.get(), qps);
        System.out.printf("latency P50/P95/P99/Max=%d/%d/%d/%d ms%n", p50, p95, p99, max);
        if (!errorTypes.isEmpty()) {
            System.out.println("errors=" + errorTypes);
        }
        System.out.println("=================================================");

        boolean pass = error.get() == 0;
        recorder.finishRun(runId, pass ? "PASS" : "FAIL", String.format(
                "{\"stage\":%d,\"taskPoolSize\":%d,\"slotTotal\":%d,\"requests\":%d,"
                        + "\"success\":%d,\"slotFull\":%d,\"conflict\":%d,\"rateLimited\":%d,"
                        + "\"errors\":%d,\"qps\":%d,\"p50Ms\":%d,\"p95Ms\":%d,\"p99Ms\":%d,\"maxMs\":%d}",
                concurrency, taskPoolSize, slotTotal, total, success.get(), slotFull.get(),
                conflict.get(), rateLimited.get(), error.get(), qps, p50, p95, p99, max));
        recorder.close();

        if (!pass) {
            throw new IllegalStateException("稳态抢单出现基础设施错误，runId=" + runId);
        }
    }

    private static List<Long> seedTasks(HttpClient client, String baseUrl, int taskPoolSize, int slotTotal) throws Exception {
        List<Long> ids = new ArrayList<>(taskPoolSize);
        for (int i = 0; i < taskPoolSize; i++) {
            String json = """
                    {"campusId":1,"type":"DELIVERY","title":"%s%d","rewardCents":100,"slotTotal":%d}
                    """.formatted(TITLE_PREFIX, i + 1, slotTotal);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/errands"))
                    .header("X-User-Id", String.valueOf(PUBLISHER_ID))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            ids.add(SpikeLoadClient.parseErrandId(resp.body()));
        }
        return ids;
    }

    private static void warmup(HttpClient client, String baseUrl, int seconds, int concurrency) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch done = new CountDownLatch(concurrency);
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    try {
                        while (System.nanoTime() < deadline) {
                            HttpRequest req = HttpRequest.newBuilder()
                                    .uri(URI.create(baseUrl + "/api/errands?campusId=1&status=PUBLISHED&size=20"))
                                    .header("X-User-Id", String.valueOf(WARMUP_USER_ID))
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build();
                            client.send(req, HttpResponse.BodyHandlers.ofString());
                        }
                    } catch (Exception ignored) {
                        // 预热阶段不计入结果
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await(seconds + 30L, TimeUnit.SECONDS);
        }
    }

    private static long selectErrandId(List<Long> errandIds, long seq, Distribution distribution) {
        if (distribution == Distribution.UNIFORM) {
            return errandIds.get((int) (seq % errandIds.size()));
        }
        int hotSize = Math.max(1, (int) Math.ceil(errandIds.size() * 0.2));
        int coldSize = Math.max(1, errandIds.size() - hotSize);
        if ((seq % 10) < 8) {
            return errandIds.get((int) (seq % hotSize));
        }
        return errandIds.get(hotSize + (int) (seq % coldSize));
    }

    private static Distribution parseDistribution(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "uniform", "roundrobin" -> Distribution.UNIFORM;
            case "hot80", "hot", "skew" -> Distribution.HOT80;
            default -> throw new IllegalArgumentException("未知分布: " + raw);
        };
    }

    private static int[] parseStages(String csv) {
        String[] parts = csv.split(",");
        int[] stages = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            stages[i] = Integer.parseInt(parts[i].trim());
        }
        return stages;
    }

    private static Outcome classify(int statusCode, String body) {
        if (statusCode == 200 && body.contains("\"code\":\"OK\"")) {
            return Outcome.SUCCESS;
        }
        if (body.contains("SLOT_FULL")) {
            return Outcome.SLOT_FULL;
        }
        if (body.contains("GRAB_CONFLICT")) {
            return Outcome.GRAB_CONFLICT;
        }
        if (body.contains("GRAB_RATE_LIMITED")) {
            return Outcome.RATE_LIMITED;
        }
        return Outcome.ERROR;
    }

    private static long pct(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int index = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private enum Distribution {
        UNIFORM,
        HOT80
    }

    private enum Outcome {
        SUCCESS,
        SLOT_FULL,
        GRAB_CONFLICT,
        RATE_LIMITED,
        ERROR
    }
}
