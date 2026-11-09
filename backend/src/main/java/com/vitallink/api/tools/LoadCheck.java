package com.vitallink.api.tools;

import com.vitallink.api.core.Settings;
import com.vitallink.api.core.security.DevTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the performance targets against a running API (docs/09):
 *
 * <ul>
 *   <li>a 5,000-bucket ingest batch in under 2 s;
 *   <li>the busiest reads (dashboard, My Health overview, training, goals) at p95 under 300 ms, warm, for an
 *       account with 90 days of data (run {@code DemoSeed} first; it signs in as demo@example.com).
 * </ul>
 *
 * <pre>
 * ./mvnw -q compile exec:java -Dexec.mainClass=com.vitallink.api.tools.LoadCheck -Dexec.args="http://localhost:8000"
 * </pre>
 *
 * Local and test only (dev tokens). Exits 1 if a target is missed.
 */
public final class LoadCheck {

    static final int ROUNDS = 40;

    private LoadCheck() {}

    public static void main(String[] args) throws Exception {
        String base = (args.length > 0 ? args[0] : "http://localhost:8000").replaceAll("/+$", "");
        Settings settings = Env.settings();
        if (!settings.isDev()) {
            throw new IllegalStateException("LoadCheck signs in with dev tokens: local or test only.");
        }
        HttpClient http = HttpClient.newHttpClient();
        JsonMapper json = JsonMapper.builder().build();
        boolean ok = true;

        // 1. Ingest: a fresh account, one batch of 5,000 buckets (about 17 days of one metric).
        String fresh = DevTokens.mint(
                settings,
                UUID.randomUUID(),
                "load-" + UUID.randomUUID() + "@example.com",
                true,
                null,
                null,
                3600,
                null);
        send(http, base + "/v1/me", fresh, null);
        long start = Math.floorDiv(Instant.now().getEpochSecond(), 300) * 300 - 5000L * 300;
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            buckets.add(Map.of(
                    "metric",
                    "heart_rate",
                    "start",
                    Instant.ofEpochSecond(start + 300L * i).toString(),
                    "value",
                    60 + i % 30,
                    "samples",
                    60));
        }
        String batch = json.writeValueAsString(
                Map.of("idempotency_key", UUID.randomUUID().toString(), "platform", "healthkit", "buckets", buckets));
        long t0 = System.nanoTime();
        int status = send(http, base + "/v1/me/health/ingest", fresh, batch);
        double ingestMs = (System.nanoTime() - t0) / 1e6;
        boolean ingestOk = status == 202 && ingestMs < 2000;
        ok &= ingestOk;
        System.out.printf(
                "%s ingest 5,000 buckets: %.0f ms (HTTP %d, target < 2,000 ms)%n",
                ingestOk ? "PASS" : "FAIL", ingestMs, status);

        // 2. Reads for the demo account, warm.
        String demo = DevTokens.mint(
                settings, DevTokens.subjectFor("demo@example.com"), "demo@example.com", true, null, null, 3600, null);
        Map<String, double[]> times = new HashMap<>();
        List<String> paths = List.of(
                "/v1/me/health/dashboard", "/v1/me/health/overview", "/v1/me/training", "/v1/me/goals/overview");
        for (String p : paths) {
            send(http, base + p, demo, null); // warm up
            double[] t = new double[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                long s = System.nanoTime();
                int code = send(http, base + p, demo, null);
                t[i] = (System.nanoTime() - s) / 1e6;
                if (code != 200) {
                    throw new IllegalStateException(p + " answered " + code + " (run DemoSeed first)");
                }
            }
            Arrays.sort(t);
            times.put(p, t);
        }
        for (String p : paths) {
            double[] t = times.get(p);
            double p50 = t[t.length / 2];
            double p95 = t[(int) Math.ceil(t.length * 0.95) - 1];
            boolean pass = p95 < 300;
            ok &= pass;
            System.out.printf(
                    "%s %-26s p50 %4.0f ms  p95 %4.0f ms (target p95 < 300 ms)%n", pass ? "PASS" : "FAIL", p, p50, p95);
        }
        System.exit(ok ? 0 : 1);
    }

    static int send(HttpClient http, String url, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
