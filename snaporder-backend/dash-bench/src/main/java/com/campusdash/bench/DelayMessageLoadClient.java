package com.campusdash.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * S5 natural-expiry client: publish and grab each errand through the real HTTP API.
 *
 * Usage:
 *   java -cp ... com.campusdash.bench.DelayMessageLoadClient
 *       [baseUrl] [count] [concurrency] [httpTimeoutSeconds]
 */
public final class DelayMessageLoadClient {

    private static final long PUBLISHER_ID = 1001L;
    private static final long RUNNER_BASE_ID = 6_000_000L;

    private DelayMessageLoadClient() {
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://127.0.0.1:8080";
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        int httpTimeoutSeconds = args.length > 3 ? Integer.parseInt(args[3]) : 15;
        Duration requestTimeout = Duration.ofSeconds(httpTimeoutSeconds);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        BenchRunRecorder recorder = new BenchRunRecorder();
        String runId = recorder.startRun(
                "BENCH",
                "S5-NAT",
                concurrency,
                "自然到期；真实 HTTP publish+grab；count=" + count);
        System.out.printf("[run] runId=%s count=%d concurrency=%d%n",
                runId, count, concurrency);

        List<Future<TaskResult>> futures = new ArrayList<>(count);
        try (ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                int sequence = i + 1;
                futures.add(pool.submit((Callable<TaskResult>) () ->
                        publishAndGrab(client, baseUrl, runId, sequence, requestTimeout)));
            }
        }

        List<Long> errandIds = new ArrayList<>(count);
        List<String> errors = new ArrayList<>();
        for (Future<TaskResult> future : futures) {
            try {
                TaskResult result = future.get();
                if (result.errandId() > 0) {
                    errandIds.add(result.errandId());
                }
                if (result.error() != null) {
                    errors.add(result.error());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add("interrupted");
            } catch (ExecutionException e) {
                errors.add("worker task failed: " + e.getCause());
            }
        }

        recorder.trackErrands(runId, errandIds);
        int published = errandIds.size();
        int grabbed = count - errors.size();
        boolean pass = errors.isEmpty() && published == count && grabbed == count;
        String summary = String.format(
                "{\"count\":%d,\"concurrency\":%d,\"published\":%d,\"grabbed\":%d,\"errors\":%d}",
                count, concurrency, published, grabbed, errors.size());
        recorder.finishRun(runId, pass ? "PASS" : "FAIL", summary);
        recorder.close();

        System.out.printf("[setup] published=%d grabbed=%d errors=%d%n",
                published, grabbed, errors.size());
        errors.stream().limit(10).forEach(error -> System.out.println("[error] " + error));
        System.out.println("[run] runId=" + runId);
        if (!pass) {
            throw new IllegalStateException("S5 natural setup failed, runId=" + runId);
        }
    }

    private static TaskResult publishAndGrab(HttpClient client, String baseUrl,
                                             String runId, int sequence,
                                             Duration requestTimeout) {
        long errandId = -1L;
        try {
            String title = "bench_s5nat_" + runId + "_" + sequence;
            HttpResponse<String> published = send(client, HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/errands"))
                    .header("X-User-Id", String.valueOf(PUBLISHER_ID))
                    .header("Content-Type", "application/json")
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"campusId":1,"type":"DELIVERY","title":"%s","rewardCents":100,"slotTotal":1}
                            """.formatted(title)))
                    .build());
            if (!isOk(published)) {
                return new TaskResult(-1, "publish status=" + published.statusCode()
                        + " body=" + compact(published.body()));
            }
            errandId = parseErrandId(published.body());

            long runnerId = RUNNER_BASE_ID + sequence;
            HttpResponse<String> grabbed = send(client, HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/errands/" + errandId + "/grab"))
                    .header("X-User-Id", String.valueOf(runnerId))
                    .header("X-Request-Id", runId + "-grab-" + sequence)
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());
            if (!isOk(grabbed)) {
                return new TaskResult(errandId, "grab errandId=" + errandId
                        + " status=" + grabbed.statusCode()
                        + " body=" + compact(grabbed.body()));
            }
            return new TaskResult(errandId, null);
        } catch (Exception e) {
            return new TaskResult(errandId, "errandId=" + errandId + " " + e);
        }
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request)
            throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean isOk(HttpResponse<String> response) {
        return response.statusCode() == 200 && response.body().contains("\"code\":\"OK\"");
    }

    private static long parseErrandId(String body) {
        String marker = "\"errandId\":";
        int start = body.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("publish response missing errandId: " + compact(body));
        }
        start += marker.length();
        while (start < body.length() && !Character.isDigit(body.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        if (start == end) {
            throw new IllegalStateException("publish response has invalid errandId: " + compact(body));
        }
        return Long.parseLong(body.substring(start, end));
    }

    private static String compact(String body) {
        return body == null ? "" : body.replaceAll("\\s+", " ").substring(0,
                Math.min(body.replaceAll("\\s+", " ").length(), 240));
    }

    private record TaskResult(long errandId, String error) {
    }
}
