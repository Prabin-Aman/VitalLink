package com.vitallink.api.tools;

import com.vitallink.api.core.security.DevTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the phone app (phase 9) over real HTTP: reads the last few hours from a pretend watch and
 * phone and uploads them to {@code POST /v1/me/health/ingest} the way the app will — 5-minute buckets,
 * the devices that measured them, a fresh idempotency key per batch. Values are derived from each
 * bucket's time, so a second run re-sends the same buckets and the API counts them as unchanged.
 *
 * <pre>
 * ./mvnw -q compile exec:java -Dexec.mainClass=com.vitallink.api.tools.PhoneSim \
 *   -Dexec.args="http://localhost:8000 --email demo@example.com --hours 6 --platform healthkit"
 * </pre>
 *
 * Local/test only (it signs in with a dev token). Prints the API's counts; exits 1 if an upload fails.
 */
public final class PhoneSim {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    static final List<String> WATCH_METRICS = List.of(
            "heart_rate",
            "resting_heart_rate",
            "hrv",
            "steps",
            "distance",
            "active_energy",
            "exercise_minutes",
            "stand_hours",
            "flights_climbed",
            "sleep_asleep",
            "sleep_deep",
            "sleep_core",
            "sleep_rem",
            "sleep_awake",
            "oxygen_saturation",
            "respiratory_rate",
            "vo2_max");

    private PhoneSim() {}

    /** The pretend devices, named like the demo account's so an upload refreshes them instead of adding more. */
    public static List<Map<String, Object>> devices(String platform) {
        boolean ios = platform.equals("healthkit");
        return List.of(
                Map.of(
                        "external_id",
                        ios ? "demo-watch" : "pixel-watch",
                        "name",
                        ios ? "Apple Watch S9" : "Pixel Watch 2",
                        "kind",
                        "wearable",
                        "metrics",
                        WATCH_METRICS),
                Map.of(
                        "external_id",
                        ios ? "demo-phone" : "pixel-phone",
                        "name",
                        ios ? "iPhone 15" : "Pixel 8",
                        "kind",
                        "phone",
                        "metrics",
                        List.of("steps", "distance", "flights_climbed")));
    }

    /** Steps, heart rate and active energy for every finished 5-minute bucket in the last `hours`. */
    public static List<Map<String, Object>> buckets(Instant now, int hours) {
        Instant end = now.truncatedTo(ChronoUnit.MINUTES);
        end = end.minusSeconds(end.getEpochSecond() % 300).minus(5, ChronoUnit.MINUTES);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Instant t = end.minus(hours, ChronoUnit.HOURS).plus(5, ChronoUnit.MINUTES);
                !t.isAfter(end);
                t = t.plus(5, ChronoUnit.MINUTES)) {
            int hour = t.atZone(CHICAGO).getHour();
            boolean awake = hour >= 7 && hour < 23;
            Random r = new Random(t.getEpochSecond()); // same bucket → same values on every run
            int steps = awake ? r.nextInt(r.nextInt(10) < 2 ? 900 : 180) : 0;
            out.add(bucket("steps", t, steps, 1));
            out.add(bucket("heart_rate", t, (awake ? 64 : 54) + r.nextInt(steps > 500 ? 60 : 14), 5));
            out.add(bucket("active_energy", t, Math.round((awake ? 1.5 : 0.4) + steps * 0.04) * 1.0, 1));
        }
        return out;
    }

    static Map<String, Object> bucket(String metric, Instant start, double value, int samples) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("metric", metric);
        b.put("start", start.toString());
        b.put("value", value);
        b.put("samples", samples);
        return b;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].startsWith("-")) {
            System.err.println(
                    "usage: PhoneSim <base-url> [--email E] [--hours N] [--platform healthkit|health_connect]");
            System.exit(2);
        }
        String base = args[0].replaceAll("/+$", "");
        String email = "demo@example.com";
        int hours = 6;
        String platform = "healthkit";
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--email" -> email = args[++i];
                case "--hours" -> hours = Math.max(1, Math.min(72, Integer.parseInt(args[++i])));
                case "--platform" -> platform = args[++i];
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        String token = DevTokens.mint(Env.settings(), DevTokens.subjectFor(email), email, true, null, null, 3600, null);
        JsonMapper json = JsonMapper.builder().build();
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        List<Map<String, Object>> all = buckets(Instant.now(), hours);
        boolean ok = true;
        // Like the app: at most 5,000 buckets per request, devices sent with the first batch.
        for (int from = 0; from < all.size(); from += 5000) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("idempotency_key", UUID.randomUUID().toString());
            body.put("platform", platform);
            body.put("devices", from == 0 ? devices(platform) : List.of());
            body.put("buckets", all.subList(from, Math.min(all.size(), from + 5000)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/v1/me/health/ingest"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.printf("POST /v1/me/health/ingest -> %d %s%n", res.statusCode(), res.body());
            ok &= res.statusCode() == 202;
        }
        System.exit(ok ? 0 : 1);
    }
}
