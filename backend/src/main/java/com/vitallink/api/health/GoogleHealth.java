package com.vitallink.api.health;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Clock;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.security.Sealer;
import com.vitallink.api.core.userdata.UserData;
import com.vitallink.api.health.Catalogue.Agg;
import com.vitallink.api.health.Catalogue.Metric;
import com.vitallink.api.health.HealthDtos.ConnectionOut;
import com.vitallink.api.health.HealthDtos.SyncOut;
import com.vitallink.api.health.HealthService.Row;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Health (Fitbit and Pixel Watch; the Google Health API replaced the Fitbit Web API in November 2026).
 * The person connects once with Google OAuth (read-only scopes); the API keeps the refresh token sealed and
 * pulls readings when they press Sync and every night. Readings are stored as ordinary 5-minute buckets with the
 * {@code google_health} source, so syncing the same period again changes nothing.
 *
 * <p>Steps, distance, active energy and heart rate come from 5-minute roll-ups; weight, body fat, resting heart
 * rate, HRV and sleep stages from their data points. A data type that fails (no data, a scope the person
 * unticked) is reported and skipped; a revoked grant stops the sync and asks the person to connect again.
 */
@Service
public class GoogleHealth implements UserData {

    private static final Logger log = LoggerFactory.getLogger(GoogleHealth.class);

    public static final String SERVICE = "google_health";
    static final String DEVICE_ID = "google-health";
    static final String DEVICE_NAME = "Fitbit (Google Health)";
    static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
            "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
            "https://www.googleapis.com/auth/googlehealth.sleep.readonly");
    static final int FIRST_SYNC_DAYS = 30;
    static final int MAX_SYNC_DAYS = 90;
    static final Duration STATE_LIFETIME = Duration.ofMinutes(15);
    static final Duration OVERLAP = Duration.ofDays(1);
    static final int MAX_PAGES = 40;

    /** Roll-up data type → (catalogue code, value field, value scale, longest range Google allows). */
    record RollUp(String type, String code, String field, String value, double scale, int maxDays) {}

    static final List<RollUp> ROLL_UPS = List.of(
            new RollUp("steps", "steps", "steps", "countSum", 1, 90),
            new RollUp("distance", "distance", "distance", "millimetersSum", 0.001, 90),
            new RollUp("active-energy-burned", "active_energy", "activeEnergyBurned", "kcalSum", 1, 90),
            new RollUp("heart-rate", "heart_rate", "heartRate", "beatsPerMinuteAvg", 1, 14));

    private final JdbcClient jdbc;
    private final Sealer sealer;
    private final HealthService health;
    private final ObjectMapper json;
    private final HttpClient http;
    private final TransactionTemplate separate;
    private final String clientId;
    private final String clientSecret;
    private final String webUrl;
    private final String authUrl;
    private final String tokenUrl;
    private final String revokeUrl;
    private final String apiUrl;

    public GoogleHealth(
            JdbcClient jdbc,
            Sealer sealer,
            HealthService health,
            ObjectMapper json,
            PlatformTransactionManager transactions,
            @Value("${vitallink.google-health.client-id:}") String clientId,
            @Value("${vitallink.google-health.client-secret:}") String clientSecret,
            @Value("${vitallink.web-url:http://localhost:5173}") String webUrl,
            @Value("${vitallink.google-health.auth-url:https://accounts.google.com/o/oauth2/v2/auth}") String authUrl,
            @Value("${vitallink.google-health.token-url:https://oauth2.googleapis.com/token}") String tokenUrl,
            @Value("${vitallink.google-health.revoke-url:https://oauth2.googleapis.com/revoke}") String revokeUrl,
            @Value("${vitallink.google-health.api-url:https://health.googleapis.com}") String apiUrl) {
        this.jdbc = jdbc;
        this.sealer = sealer;
        this.health = health;
        this.json = json;
        this.clientId = clientId == null ? "" : clientId.strip();
        this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
        this.webUrl = strip(webUrl);
        this.authUrl = authUrl;
        this.tokenUrl = tokenUrl;
        this.revokeUrl = revokeUrl;
        this.apiUrl = strip(apiUrl);
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public boolean configured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty() && sealer.configured();
    }

    /** Where Google sends the browser back: a page of the web portal, which posts the code to {@link #finish}. */
    public String redirectUri() {
        return webUrl + "/devices/google/callback";
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ApiError(
                    503, "google_health_unavailable", "Connecting Google Health isn't set up on this server.");
        }
    }

    // ---------------------------------------------------------------- connect

    /** The Google consent page to send the browser to. The state ties the answer to this person, for 15 minutes. */
    public String authorizeUrl(UUID userId) {
        requireConfigured();
        long expires = Clock.now().toInstant().plus(STATE_LIFETIME).getEpochSecond();
        String state = sealer.sign(userId + "|" + expires + "|" + Ids.uuid7());
        return authUrl + "?"
                + form(Map.of(
                        "client_id",
                        clientId,
                        "redirect_uri",
                        redirectUri(),
                        "response_type",
                        "code",
                        "scope",
                        String.join(" ", SCOPES),
                        "access_type",
                        "offline",
                        "prompt",
                        "consent",
                        "include_granted_scopes",
                        "false",
                        "state",
                        state));
    }

    /** Exchanges the code Google returned, keeps the refresh token sealed, and runs the first sync. */
    public SyncOut finish(UUID userId, String tz, String code, String state) {
        requireConfigured();
        String v = sealer.verify(state);
        String[] parts = v == null ? new String[0] : v.split("\\|");
        if (parts.length != 3
                || !parts[0].equals(userId.toString())
                || Long.parseLong(parts[1]) < Clock.now().toInstant().getEpochSecond()) {
            throw new ApiError(400, "invalid_state", "That link has expired or isn't yours. Start connecting again.");
        }
        JsonNode token = tokenRequest(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirectUri(),
                "client_id", clientId,
                "client_secret", clientSecret));
        String refresh = text(token, "refresh_token");
        if (refresh == null) {
            throw new ApiError(
                    502, "google_no_refresh_token", "Google didn't allow ongoing access. Try connecting again.");
        }
        String granted = Optional.ofNullable(text(token, "scope")).orElse(String.join(" ", SCOPES));
        jdbc.sql("""
                        INSERT INTO vitallink.device_connections (user_id, service, refresh_token, scopes)
                        VALUES (:u, :s, :t, :scopes)
                        ON CONFLICT (user_id, service) DO UPDATE
                          SET refresh_token = EXCLUDED.refresh_token, scopes = EXCLUDED.scopes,
                              connected_at = now(), last_error = NULL
                        """)
                .param("u", userId)
                .param("s", SERVICE)
                .param("t", sealer.seal(refresh))
                .param("scopes", granted)
                .update();
        return sync(userId, tz, text(token, "access_token"));
    }

    // ---------------------------------------------------------------- status

    public ConnectionOut status(UUID userId) {
        return jdbc.sql("""
                        SELECT connected_at, last_sync_at, synced_through, last_error
                        FROM vitallink.device_connections WHERE user_id = :u AND service = :s
                        """)
                .param("u", userId)
                .param("s", SERVICE)
                .query((rs, i) -> new ConnectionOut(
                        SERVICE,
                        DEVICE_NAME,
                        configured(),
                        true,
                        rs.getObject("connected_at", OffsetDateTime.class),
                        rs.getObject("last_sync_at", OffsetDateTime.class),
                        rs.getObject("synced_through", OffsetDateTime.class),
                        rs.getString("last_error")))
                .optional()
                .orElseGet(() -> new ConnectionOut(SERVICE, DEVICE_NAME, configured(), false, null, null, null, null));
    }

    /** Stops syncing, tells Google to drop the grant, and removes the device. Readings already stored stay. */
    public void disconnect(UUID userId) {
        Optional<byte[]> sealed = refreshToken(userId);
        jdbc.sql("DELETE FROM vitallink.device_connections WHERE user_id = :u AND service = :s")
                .param("u", userId)
                .param("s", SERVICE)
                .update();
        jdbc.sql("DELETE FROM vitallink.devices WHERE user_id = :u AND platform = 'google_health'")
                .param("u", userId)
                .update();
        sealed.ifPresent(this::revokeQuietly);
    }

    /** Disconnects when the device being removed is the Google Health one; true if it was. */
    public boolean disconnectIfDevice(UUID userId, UUID deviceId) {
        boolean ours = jdbc.sql("""
                        SELECT count(*) > 0 FROM vitallink.devices
                        WHERE id = :id AND user_id = :u AND platform = 'google_health'
                        """)
                .param("id", deviceId)
                .param("u", userId)
                .query(Boolean.class)
                .single();
        if (ours) {
            disconnect(userId);
        }
        return ours;
    }

    /** Everyone connected, for the nightly sync. */
    public List<UUID> connectedUsers() {
        return jdbc.sql("SELECT user_id FROM vitallink.device_connections WHERE service = :s ORDER BY user_id")
                .param("s", SERVICE)
                .query(UUID.class)
                .list();
    }

    private Optional<byte[]> refreshToken(UUID userId) {
        return jdbc.sql("SELECT refresh_token FROM vitallink.device_connections WHERE user_id = :u AND service = :s")
                .param("u", userId)
                .param("s", SERVICE)
                .query(byte[].class)
                .optional();
    }

    private void revokeQuietly(byte[] sealed) {
        try {
            String t = sealer.open(sealed);
            HttpRequest r = HttpRequest.newBuilder(URI.create(revokeUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token", t))))
                    .build();
            http.send(r, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("google health: revoking the grant failed ({})", e.toString());
        }
    }

    // ---------------------------------------------------------------- sync

    /** Pulls everything since the last sync (a day of overlap) or the last 30 days, and stores it. */
    public SyncOut sync(UUID userId, String tz) {
        requireConfigured();
        return sync(userId, tz, null);
    }

    private SyncOut sync(UUID userId, String tz, String accessToken) {
        OffsetDateTime through = jdbc.sql("""
                        SELECT synced_through FROM vitallink.device_connections WHERE user_id = :u AND service = :s
                        """)
                .param("u", userId)
                .param("s", SERVICE)
                .query((rs, i) -> Optional.ofNullable(rs.getObject("synced_through", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ApiError(404, "not_connected", "Google Health isn't connected."))
                .orElse(null);
        Instant now = Clock.now().toInstant();
        Instant to = bucketStart(now).plusSeconds(300);
        Instant from = through == null
                ? now.minus(Duration.ofDays(FIRST_SYNC_DAYS))
                : through.toInstant().minus(OVERLAP);
        Instant earliest = now.minus(Duration.ofDays(MAX_SYNC_DAYS));
        if (from.isBefore(earliest)) {
            from = earliest;
        }
        final Instant start = bucketStart(from);

        String token = accessToken != null ? accessToken : accessToken(userId);
        Collector c = new Collector(ZoneId.of(tz));
        List<String> problems = new ArrayList<>();
        final Instant f = start;
        try {
            for (RollUp r : ROLL_UPS) {
                each(problems, r.type(), () -> rollUp(token, r, c, f, to));
            }
            each(problems, "weight", () -> samples(token, "weight", "weight", "weightGrams", 0.001, "weight", f, c));
            each(problems, "body-fat", () -> samples(token, "body-fat", "bodyFat", "percentage", 1, "body_fat", f, c));
            each(
                    problems,
                    "daily-resting-heart-rate",
                    () -> daily(
                            token,
                            "daily-resting-heart-rate",
                            "dailyRestingHeartRate",
                            "beatsPerMinute",
                            "resting_heart_rate",
                            f,
                            c));
            each(
                    problems,
                    "daily-heart-rate-variability",
                    () -> daily(
                            token,
                            "daily-heart-rate-variability",
                            "dailyHeartRateVariability",
                            "averageHeartRateVariabilityMilliseconds",
                            "hrv",
                            f,
                            c));
            each(problems, "sleep", () -> sleep(token, f, c));
        } catch (Reconnect e) {
            markError(userId, "Google access was removed. Connect Google Health again.");
            throw new ApiError(409, "reconnect_needed", "Google access was removed. Connect Google Health again.");
        }

        int failedTypes = problems.size();
        List<Row> rows = c.rows(problems);
        int inserted = 0;
        int updated = 0;
        for (int i = 0; i < rows.size(); i += HealthService.MAX_BUCKETS) {
            int[] n = health.upsert(
                    userId, SERVICE, rows.subList(i, Math.min(rows.size(), i + HealthService.MAX_BUCKETS)));
            inserted += n[0];
            updated += n[1];
        }
        if (!rows.isEmpty() || !c.metricIds.isEmpty()) {
            saveDevice(userId, c.metricIds);
        }
        // Every data type failed: say so on the device card rather than looking connected and empty.
        String error = failedTypes == ROLL_UPS.size() + 5 ? "Nothing could be read from Google Health." : null;
        jdbc.sql("""
                        UPDATE vitallink.device_connections
                        SET last_sync_at = now(), synced_through = :to, last_error = :e
                        WHERE user_id = :u AND service = :s
                        """)
                .param("to", to.atOffset(ZoneOffset.UTC))
                .param("e", error)
                .param("u", userId)
                .param("s", SERVICE)
                .update();
        return new SyncOut(
                SERVICE,
                start.atOffset(ZoneOffset.UTC),
                to.atOffset(ZoneOffset.UTC),
                rows.size(),
                inserted,
                updated,
                rows.size() - inserted - updated,
                List.copyOf(problems.subList(0, Math.min(10, problems.size()))));
    }

    /** In its own transaction: the request that hits this fails and rolls back, but the card should still say why. */
    private void markError(UUID userId, String message) {
        separate.executeWithoutResult(s -> jdbc.sql(
                        "UPDATE vitallink.device_connections SET last_error = :e WHERE user_id = :u AND service = :s")
                .param("e", message)
                .param("u", userId)
                .param("s", SERVICE)
                .update());
    }

    private void saveDevice(UUID userId, TreeSet<Integer> metricIds) {
        jdbc.sql("""
                        INSERT INTO vitallink.devices (id, user_id, platform, external_id, name, kind, metric_ids)
                        VALUES (:id, :u, 'google_health', :ext, :name, 'wearable', CAST(:m AS smallint[]))
                        ON CONFLICT (user_id, platform, external_id) DO UPDATE
                          SET metric_ids = (SELECT coalesce(array_agg(DISTINCT x ORDER BY x), '{}')
                                            FROM unnest(devices.metric_ids || EXCLUDED.metric_ids) AS x),
                              last_sync_at = now()
                        """)
                .param("id", Ids.uuid7())
                .param("u", userId)
                .param("ext", DEVICE_ID)
                .param("name", DEVICE_NAME)
                .param("m", metricIds.toArray(Integer[]::new))
                .update();
    }

    /** A grant Google no longer honours: stop the whole sync. */
    static final class Reconnect extends RuntimeException {
        Reconnect() {
            super(null, null, false, false);
        }
    }

    /** A data type Google refused or that failed: report it and carry on with the rest. */
    static final class Skipped extends RuntimeException {
        Skipped(String message) {
            super(message, null, false, false);
        }
    }

    private interface Work {
        void run() throws IOException, InterruptedException;
    }

    private static void each(List<String> problems, String type, Work w) {
        try {
            w.run();
        } catch (Reconnect e) {
            throw e;
        } catch (Skipped e) {
            problems.add(type + ": " + e.getMessage());
        } catch (IOException e) {
            problems.add(type + ": Google Health didn't answer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problems.add(type + ": interrupted");
        } catch (RuntimeException e) {
            log.warn("google health: {} failed", type, e);
            problems.add(type + ": couldn't be read");
        }
    }

    private String accessToken(UUID userId) {
        byte[] sealed = refreshToken(userId)
                .orElseThrow(() -> new ApiError(404, "not_connected", "Google Health isn't connected."));
        try {
            JsonNode t = tokenRequest(Map.of(
                    "grant_type",
                    "refresh_token",
                    "refresh_token",
                    sealer.open(sealed),
                    "client_id",
                    clientId,
                    "client_secret",
                    clientSecret));
            String access = text(t, "access_token");
            if (access == null) {
                throw new ApiError(502, "google_unavailable", "Google Health didn't answer. Try again later.");
            }
            return access;
        } catch (ApiError e) {
            if (e.code().equals("google_grant_revoked")) {
                markError(userId, "Google access was removed. Connect Google Health again.");
                throw new ApiError(409, "reconnect_needed", "Google access was removed. Connect Google Health again.");
            }
            throw e;
        }
    }

    private JsonNode tokenRequest(Map<String, String> fields) {
        HttpRequest r = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields)))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(r, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiError(502, "google_unavailable", "Google didn't answer. Try again later.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError(502, "google_unavailable", "Google didn't answer. Try again later.");
        }
        JsonNode body = parse(res.body());
        if (res.statusCode() / 100 != 2) {
            String err = body == null ? null : text(body, "error");
            if ("invalid_grant".equals(err)) {
                throw new ApiError(
                        400, "google_grant_revoked", "Google didn't accept the sign-in. Try connecting again.");
            }
            log.warn("google health: token endpoint answered {} ({})", res.statusCode(), err);
            throw new ApiError(502, "google_unavailable", "Google didn't answer as expected. Try again later.");
        }
        return body;
    }

    private JsonNode call(String token, String method, String path, String query, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl + path + (query == null ? "" : "?" + query)))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        int s = res.statusCode();
        if (s == 401) {
            throw new Reconnect();
        }
        if (s == 403) {
            throw new Skipped("not allowed (was this permission left unticked?)");
        }
        if (s == 404) {
            throw new Skipped("not available");
        }
        if (s == 429) {
            throw new Skipped("Google asked us to slow down; the next sync will catch up");
        }
        if (s / 100 != 2) {
            throw new Skipped("Google answered " + s);
        }
        JsonNode n = parse(res.body());
        if (n == null) {
            throw new Skipped("unreadable answer");
        }
        return n;
    }

    /** 5-minute windows of a roll-up type, in chunks no longer than Google allows, page by page. */
    private void rollUp(String token, RollUp r, Collector c, Instant from, Instant to)
            throws IOException, InterruptedException {
        Metric m = Catalogue.get(r.code());
        for (Instant s = from; s.isBefore(to); s = s.plus(Duration.ofDays(r.maxDays()))) {
            Instant e = s.plus(Duration.ofDays(r.maxDays()));
            if (e.isAfter(to)) {
                e = to;
            }
            String pageToken = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("range", Map.of("startTime", s.toString(), "endTime", e.toString()));
                body.put("windowSize", "300s");
                body.put("pageSize", 10000);
                if (pageToken != null) {
                    body.put("pageToken", pageToken);
                }
                JsonNode n = call(
                        token,
                        "POST",
                        "/v4/users/me/dataTypes/" + r.type() + "/dataPoints:rollUp",
                        null,
                        json.writeValueAsString(body));
                for (JsonNode p : n.path("rollupDataPoints")) {
                    Instant start = time(text(p, "startTime"));
                    Double v = number(p.path(r.field()).path(r.value()));
                    if (start != null && v != null && (m.aggregation() != Agg.sum || v > 0)) {
                        c.put(m, start, v * r.scale());
                    }
                }
                pageToken = text(n, "nextPageToken");
                if (pageToken == null || pageToken.isEmpty()) {
                    break;
                }
            }
        }
    }

    /** Data points with a sample time (weight, body fat). */
    private void samples(
            String token, String type, String field, String value, double scale, String code, Instant from, Collector c)
            throws IOException, InterruptedException {
        Metric m = Catalogue.get(code);
        String filter = field + ".sample_time.physical_time >= \"" + from + "\"";
        list(token, type, filter, 1000, p -> {
            JsonNode d = p.path(field);
            Instant at = time(text(d.path("sampleTime"), "physicalTime"));
            Double v = number(d.path(value));
            if (at != null && v != null) {
                c.put(m, at, v * scale);
            }
        });
    }

    /** One value a day (resting heart rate, HRV), stored at the start of that day in the person's time zone. */
    private void daily(String token, String type, String field, String value, String code, Instant from, Collector c)
            throws IOException, InterruptedException {
        Metric m = Catalogue.get(code);
        LocalDate first = from.atZone(c.zone).toLocalDate();
        String filter = field + ".date >= \"" + first + "\"";
        list(token, type, filter, 1000, p -> {
            JsonNode d = p.path(field);
            JsonNode date = d.path("date");
            Double v = number(d.path(value));
            if (v != null && date.has("year") && date.has("month") && date.has("day")) {
                LocalDate day = LocalDate.of(
                        date.path("year").asInt(),
                        date.path("month").asInt(),
                        date.path("day").asInt());
                c.put(m, day.atStartOfDay(c.zone).toInstant(), v);
            }
        });
    }

    /** Sleep sessions: each stage's minutes go into the buckets it covers, like an Apple Health import. */
    private void sleep(String token, Instant from, Collector c) throws IOException, InterruptedException {
        String filter = "sleep.interval.end_time >= \"" + from + "\"";
        list(token, "sleep", filter, 25, p -> {
            for (JsonNode st : p.path("sleep").path("stages")) {
                Instant s = time(text(st, "startTime"));
                Instant e = time(text(st, "endTime"));
                if (s == null || e == null || !e.isAfter(s)) {
                    continue;
                }
                switch (String.valueOf(text(st, "type"))) {
                    case "DEEP" -> c.minutes(s, e, "sleep_deep", "sleep_asleep");
                    case "LIGHT" -> c.minutes(s, e, "sleep_core", "sleep_asleep");
                    case "REM" -> c.minutes(s, e, "sleep_rem", "sleep_asleep");
                    case "ASLEEP" -> c.minutes(s, e, "sleep_asleep");
                    case "AWAKE", "RESTLESS" -> c.minutes(s, e, "sleep_awake");
                    default -> {}
                }
            }
        });
    }

    private interface PointSink {
        void accept(JsonNode point);
    }

    private void list(String token, String type, String filter, int pageSize, PointSink sink)
            throws IOException, InterruptedException {
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("filter", filter);
            q.put("pageSize", String.valueOf(pageSize));
            if (pageToken != null) {
                q.put("pageToken", pageToken);
            }
            JsonNode n = call(token, "GET", "/v4/users/me/dataTypes/" + type + "/dataPoints", form(q), null);
            for (JsonNode p : n.path("dataPoints")) {
                sink.accept(p);
            }
            pageToken = text(n, "nextPageToken");
            if (pageToken == null || pageToken.isEmpty()) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- buckets

    /** Sums totals and averages the rest per 5-minute bucket, then checks each value against the catalogue. */
    static final class Collector {
        final ZoneId zone;
        final Map<Long, double[]> acc = new HashMap<>(); // epoch * 128 + metric id → {sum, count}
        final TreeSet<Integer> metricIds = new TreeSet<>();

        Collector(ZoneId zone) {
            this.zone = zone;
        }

        void put(Metric m, Instant at, double v) {
            long key = bucketStart(at).getEpochSecond() * 128 + m.id();
            double[] a = acc.computeIfAbsent(key, k -> new double[2]);
            a[0] += v;
            a[1]++;
            metricIds.add(m.id());
        }

        void minutes(Instant s, Instant e, String... codes) {
            for (long b = bucketStart(s).getEpochSecond(); b < e.getEpochSecond(); b += 300) {
                double overlap = Math.min(b + 300, e.getEpochSecond()) - Math.max(b, s.getEpochSecond());
                if (overlap > 0) {
                    for (String code : codes) {
                        put(Catalogue.get(code), Instant.ofEpochSecond(b), overlap / 60.0);
                    }
                }
            }
        }

        List<Row> rows(List<String> problems) {
            List<Row> rows = new ArrayList<>(acc.size());
            int outside = 0;
            for (var en : acc.entrySet()) {
                Metric m = Catalogue.BY_ID.get((int) Math.floorMod(en.getKey(), 128L));
                double[] a = en.getValue();
                double value = m.aggregation() == Agg.sum ? a[0] : a[0] / a[1];
                value = Math.round(value * 1000) / 1000.0;
                if (value < m.minValue() || value > m.maxValue()) {
                    outside++;
                    continue;
                }
                rows.add(new Row(m.id(), Instant.ofEpochSecond(Math.floorDiv(en.getKey(), 128L)), value, (int)
                        Math.min(32767, Math.max(1, a[1]))));
            }
            if (outside > 0) {
                problems.add(outside + " readings were outside the ranges VitalLink accepts");
            }
            rows.sort(Comparator.comparing(Row::start).thenComparingInt(Row::metricId));
            return rows;
        }
    }

    static Instant bucketStart(Instant t) {
        return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), 300) * 300);
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : json.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    /** Google sends 64-bit numbers as strings. */
    static Double number(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        try {
            return Double.parseDouble(n.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Instant time(String s) {
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String form(Map<String, String> fields) {
        StringBuilder b = new StringBuilder();
        fields.forEach((k, v) -> {
            if (!b.isEmpty()) {
                b.append('&');
            }
            b.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return b.toString();
    }

    // ---------------------------------------------------------------- data rights

    @Override
    public Map<String, String> exportQueries() {
        return Map.of(
                "device_connections",
                "SELECT service, scopes, connected_at, last_sync_at, synced_through, last_error "
                        + "FROM vitallink.device_connections WHERE user_id = :u ORDER BY service");
    }

    @Override
    public void beforeDelete(UUID userId) {
        refreshToken(userId).ifPresent(this::revokeQuietly);
    }
}
