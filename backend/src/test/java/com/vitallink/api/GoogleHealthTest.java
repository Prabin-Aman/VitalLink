package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vitallink.api.support.ApiTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Google Health connect and sync against a stand-in for Google (token, revoke and Health API endpoints) on a
 * local port: the consent link, the code exchange, readings stored as buckets, a type Google refuses, a re-sync
 * that changes nothing, a revoked grant, disconnecting, and the export leaving the token out.
 */
class GoogleHealthTest extends ApiTest {

    static final FakeGoogle GOOGLE = FakeGoogle.start();

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry r) {
        r.add("vitallink.google-health.client-id", () -> "client-123");
        r.add("vitallink.google-health.client-secret", () -> "secret-456");
        r.add("vitallink.google-health.token-url", () -> GOOGLE.url + "/token");
        r.add("vitallink.google-health.revoke-url", () -> GOOGLE.url + "/revoke");
        r.add("vitallink.google-health.api-url", () -> GOOGLE.url);
        r.add("vitallink.google-health.auth-url", () -> "https://accounts.example/auth");
    }

    @AfterAll
    static void stop() {
        GOOGLE.server.stop(0);
    }

    @Override
    protected String user() {
        return afternoonUser();
    }

    static Map<String, String> query(String url) {
        Map<String, String> q = new HashMap<>();
        for (String kv : URI.create(url).getRawQuery().split("&")) {
            int i = kv.indexOf('=');
            q.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    /** Starts connecting and returns the state Google would hand back. */
    String start(String t) {
        Res r = post(t, "/v1/me/connections/google-health/start", Map.of());
        assertThat(r.status()).isEqualTo(200);
        String url = r.at("/url").asString();
        Map<String, String> q = query(url);
        assertThat(url).startsWith("https://accounts.example/auth?");
        assertThat(q.get("client_id")).isEqualTo("client-123");
        assertThat(q.get("redirect_uri")).endsWith("/devices/google/callback");
        assertThat(q.get("access_type")).isEqualTo("offline");
        assertThat(q.get("scope")).contains("googlehealth.sleep.readonly").doesNotContain("writeonly");
        return q.get("state");
    }

    Res connect(String t) {
        String code = GOOGLE.newCode();
        return post(t, "/v1/me/connections/google-health/finish", Map.of("code", code, "state", start(t)));
    }

    @Test
    void connectingPullsReadingsIntoBuckets() {
        String t = user();
        Res r = connect(t);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.at("/service").asString()).isEqualTo("google_health");
        assertThat(r.at("/inserted").asInt()).isGreaterThan(0);
        assertThat(r.at("/problems").toString())
                .contains("active-energy-burned")
                .contains("not allowed");

        JsonNode latest = get(t, "/v1/me/health/latest?metrics=weight,heart_rate,resting_heart_rate")
                .body();
        Map<String, Double> values = new HashMap<>();
        latest.forEach(
                n -> values.put(n.get("metric").asString(), n.get("value").asDouble()));
        assertThat(values)
                .containsEntry("weight", 70.5)
                .containsEntry("heart_rate", 72.0)
                .containsEntry("resting_heart_rate", 58.0);

        JsonNode steps =
                get(t, "/v1/me/health/series/steps?days=1&interval=day").body();
        assertThat(steps.at("/points/0/value").asDouble()).isEqualTo(120.0);
        JsonNode distance =
                get(t, "/v1/me/health/series/distance?days=1&interval=day").body();
        assertThat(distance.at("/points/0/value").asDouble()).isEqualTo(100.0);

        JsonNode status = get(t, "/v1/me/connections").body().get(0);
        assertThat(status.get("connected").asBoolean()).isTrue();
        assertThat(status.get("available").asBoolean()).isTrue();
        assertThat(status.get("last_sync_at").isNull()).isFalse();
        assertThat(get(t, "/v1/me/devices").text()).contains("Fitbit (Google Health)");

        // Syncing the same period again stores nothing new.
        Res again = post(t, "/v1/me/connections/google-health/sync", Map.of());
        assertThat(again.status()).as(again.text()).isEqualTo(200);
        assertThat(again.at("/inserted").asInt()).isZero();
        assertThat(again.at("/updated").asInt()).isZero();
    }

    @Test
    void theStateBelongsToOnePersonAndOneFlow() {
        String alice = user();
        String bob = user();
        String state = start(alice);
        Res stolen =
                post(bob, "/v1/me/connections/google-health/finish", Map.of("code", GOOGLE.newCode(), "state", state));
        assertThat(stolen.status()).isEqualTo(400);
        assertThat(stolen.code()).isEqualTo("invalid_state");
        Res forged = post(
                alice,
                "/v1/me/connections/google-health/finish",
                Map.of("code", GOOGLE.newCode(), "state", state + "x"));
        assertThat(forged.code()).isEqualTo("invalid_state");
        assertThat(post(alice, "/v1/me/connections/google-health/sync", Map.of())
                        .code())
                .isEqualTo("not_connected");
    }

    @Test
    void aRevokedGrantAsksToConnectAgain() {
        String t = user();
        assertThat(connect(t).status()).isEqualTo(200);
        GOOGLE.revokeAll();
        Res r = post(t, "/v1/me/connections/google-health/sync", Map.of());
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("reconnect_needed");
        // Saved in its own transaction, so the card can say why even though the request failed.
        assertThat(get(t, "/v1/me/connections").body().get(0).get("last_error").asString())
                .contains("Connect Google Health again");
    }

    @Test
    void disconnectingRevokesAndKeepsReadings() {
        String t = user();
        assertThat(connect(t).status()).isEqualTo(200);
        int revokedBefore = GOOGLE.revokes.get();
        assertThat(delete(t, "/v1/me/connections/google-health").status()).isEqualTo(204);
        assertThat(GOOGLE.revokes.get()).isEqualTo(revokedBefore + 1);
        assertThat(get(t, "/v1/me/connections").body().get(0).get("connected").asBoolean())
                .isFalse();
        assertThat(get(t, "/v1/me/devices").text()).doesNotContain("Google Health");
        assertThat(get(t, "/v1/me/health/latest?metrics=weight").text()).contains("70.5");
    }

    @Test
    void forgettingTheDeviceDisconnects() {
        String t = user();
        assertThat(connect(t).status()).isEqualTo(200);
        String id = null;
        for (JsonNode d : get(t, "/v1/me/devices").body()) {
            if (d.get("name").asString().contains("Google Health")) {
                id = d.get("id").asString();
            }
        }
        assertThat(id).isNotNull();
        assertThat(delete(t, "/v1/me/devices/" + id).status()).isEqualTo(204);
        assertThat(get(t, "/v1/me/connections").body().get(0).get("connected").asBoolean())
                .isFalse();
    }

    @Test
    void theExportLeavesTheTokenOut() {
        String t = user();
        assertThat(connect(t).status()).isEqualTo(200);
        Res export = get(t, "/v1/me/export");
        assertThat(export.status()).isEqualTo(200);
        assertThat(export.text())
                .contains("device_connections")
                .contains("google_health")
                .doesNotContain("refresh_token");
    }

    /** Just enough of Google: OAuth token/revoke and the Health API calls the sync makes. */
    static final class FakeGoogle {
        final HttpServer server;
        final String url;
        final AtomicInteger codes = new AtomicInteger();
        final AtomicInteger revokes = new AtomicInteger();
        final Set<String> refresh = ConcurrentHashMap.newKeySet();
        final Set<String> access = ConcurrentHashMap.newKeySet();

        private FakeGoogle(HttpServer server) {
            this.server = server;
            this.url = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        static FakeGoogle start() {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeGoogle g = new FakeGoogle(s);
                s.createContext("/", g::handle);
                s.start();
                return g;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        String newCode() {
            return "code-" + codes.incrementAndGet();
        }

        void revokeAll() {
            refresh.clear();
            access.clear();
        }

        static Map<String, String> form(String body) {
            Map<String, String> m = new HashMap<>();
            for (String kv : body.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    m.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
            return m;
        }

        /** Today 9:00 in the test users' zone: always in the past, always "today". */
        static Instant nine() {
            ZonedDateTime t = LocalDate.now(AFTERNOON).atTime(9, 0).atZone(AFTERNOON);
            return t.toInstant();
        }

        void handle(HttpExchange x) throws IOException {
            String path = x.getRequestURI().getPath();
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/token")) {
                Map<String, String> f = form(body);
                if (!"client-123".equals(f.get("client_id")) || !"secret-456".equals(f.get("client_secret"))) {
                    send(x, 401, "{\"error\":\"invalid_client\"}");
                } else if ("authorization_code".equals(f.get("grant_type"))
                        && f.get("code").startsWith("code-")) {
                    String n = f.get("code").substring(5);
                    refresh.add("r-" + n);
                    access.add("a-" + n);
                    send(
                            x,
                            200,
                            "{\"access_token\":\"a-" + n + "\",\"refresh_token\":\"r-" + n
                                    + "\",\"expires_in\":3599,\"token_type\":\"Bearer\"}");
                } else if ("refresh_token".equals(f.get("grant_type")) && refresh.contains(f.get("refresh_token"))) {
                    String a = "a-" + f.get("refresh_token").substring(2) + "-" + System.nanoTime();
                    access.add(a);
                    send(x, 200, "{\"access_token\":\"" + a + "\",\"expires_in\":3599}");
                } else {
                    send(x, 400, "{\"error\":\"invalid_grant\"}");
                }
                return;
            }
            if (path.equals("/revoke")) {
                revokes.incrementAndGet();
                refresh.remove(form(body).get("token"));
                send(x, 200, "{}");
                return;
            }
            String auth = x.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !access.contains(auth.substring(7))) {
                send(x, 401, "{\"error\":{\"code\":401}}");
                return;
            }
            Instant nine = nine();
            if (path.endsWith(":rollUp")) {
                // Like Google, only windows inside the requested range (key order in the JSON isn't fixed).
                var from = java.util.regex.Pattern.compile("\"startTime\":\"([^\"]+)\"")
                        .matcher(body);
                var to = java.util.regex.Pattern.compile("\"endTime\":\"([^\"]+)\"")
                        .matcher(body);
                if (!from.find() || !to.find()) {
                    send(x, 400, "{}");
                    return;
                }
                if (nine.isBefore(Instant.parse(from.group(1))) || !nine.isBefore(Instant.parse(to.group(1)))) {
                    send(x, 200, "{}");
                    return;
                }
            }
            String type = path.replaceAll(".*/dataTypes/([^/]+)/dataPoints.*", "$1");
            String window = "\"startTime\":\"" + nine + "\",\"endTime\":\"" + nine.plusSeconds(300) + "\"";
            String out = switch (path.endsWith(":rollUp") ? "rollUp:" + type : "list:" + type) {
                case "rollUp:steps" -> "{\"rollupDataPoints\":[{" + window + ",\"steps\":{\"countSum\":\"120\"}}]}";
                case "rollUp:distance" ->
                    "{\"rollupDataPoints\":[{" + window + ",\"distance\":{\"millimetersSum\":\"100000\"}}]}";
                case "rollUp:heart-rate" ->
                    "{\"rollupDataPoints\":[{" + window + ",\"heartRate\":{\"beatsPerMinuteAvg\":72}}]}";
                case "rollUp:active-energy-burned" -> null;
                case "list:weight" ->
                    "{\"dataPoints\":[{\"weight\":{\"sampleTime\":{\"physicalTime\":\"" + nine.minusSeconds(3600)
                            + "\"},\"weightGrams\":70500}}]}";
                case "list:body-fat" -> "{}";
                case "list:daily-resting-heart-rate" -> {
                    LocalDate d = LocalDate.now(AFTERNOON).minusDays(1);
                    yield "{\"dataPoints\":[{\"dailyRestingHeartRate\":{\"date\":{\"year\":" + d.getYear()
                            + ",\"month\":" + d.getMonthValue() + ",\"day\":" + d.getDayOfMonth()
                            + "},\"beatsPerMinute\":\"58\"}}]}";
                }
                case "list:sleep" -> {
                    Instant s = nine.minusSeconds(8 * 3600);
                    yield "{\"dataPoints\":[{\"sleep\":{\"type\":\"STAGES\",\"stages\":["
                            + "{\"type\":\"DEEP\",\"startTime\":\"" + s + "\",\"endTime\":\"" + s.plusSeconds(1800)
                            + "\"},"
                            + "{\"type\":\"REM\",\"startTime\":\"" + s.plusSeconds(1800) + "\",\"endTime\":\""
                            + s.plusSeconds(3000) + "\"}]}}]}";
                }
                default -> "404";
            };
            if (out == null) {
                send(x, 403, "{\"error\":{\"code\":403}}");
            } else if (out.equals("404")) {
                send(x, 404, "{\"error\":{\"code\":404}}");
            } else {
                send(x, 200, out);
            }
        }

        static void send(HttpExchange x, int status, String body) throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(status, b.length);
            x.getResponseBody().write(b);
            x.close();
        }
    }
}
