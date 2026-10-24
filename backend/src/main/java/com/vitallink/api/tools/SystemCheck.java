package com.vitallink.api.tools;

import com.vitallink.api.core.Settings;
import com.vitallink.api.core.security.DevTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Black-box system test of a running API over real HTTP (local or test deployments: it signs in with dev
 * tokens). Two fresh accounts go through every module end to end, including a signed file upload and
 * download, sharing → read → revoke, the nightly job, export and delete.
 *
 * <pre>
 * ./mvnw -q compile exec:java -Dexec.mainClass=com.vitallink.api.tools.SystemCheck -Dexec.args="http://localhost:8000"
 * </pre>
 *
 * Exits 0 when every step passes, 1 otherwise.
 */
public final class SystemCheck {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private final String base;
    private final Settings settings;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> failures = new ArrayList<>();
    private int steps;

    SystemCheck(String base, Settings settings) {
        this.base = base.replaceAll("/+$", "");
        this.settings = settings;
    }

    record Res(int status, JsonNode body, byte[] raw) {
        JsonNode at(String pointer) {
            return body.at(pointer);
        }
    }

    Res call(String method, String token, String path, Object body) throws Exception {
        String url = path.startsWith("http") ? path : base + path;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body instanceof byte[] bytes) {
            b.header("Content-Type", "application/pdf").method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
        } else if (body != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        JsonNode node;
        try {
            node = r.body().length == 0 ? json.missingNode() : json.readTree(r.body());
        } catch (RuntimeException e) {
            node = json.missingNode();
        }
        return new Res(r.statusCode(), node, r.body());
    }

    void check(String step, boolean ok, Object detail) {
        steps++;
        System.out.printf("%s %s%n", ok ? "PASS" : "FAIL", step);
        if (!ok) {
            failures.add(step);
            System.out.println("     " + detail);
        }
    }

    String token(String email) {
        return DevTokens.mint(settings, UUID.randomUUID(), email, true, "System", "Check", 3600, null);
    }

    static String iso(ZonedDateTime t) {
        return t.toOffsetDateTime().toString();
    }

    void run() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String aEmail = "system-a-" + tag + "@example.com";
        String bEmail = "system-b-" + tag + "@example.com";
        String a = token(aEmail);
        String b = token(bEmail);

        check("GET /health", call("GET", null, "/health", null).status() == 200, "");
        check(
                "GET /ready (database reachable)",
                call("GET", null, "/ready", null).status() == 200,
                "");
        check("no token → 401", call("GET", null, "/v1/me", null).status() == 401, "");
        Res meA = call("GET", a, "/v1/me", null);
        Res meB = call("GET", b, "/v1/me", null);
        check("first call creates both accounts", meA.status() == 200 && meB.status() == 200, meA.status());
        String aId = meA.at("/id").asString();

        // Health: sync a few buckets, then read them back.
        long bucket = Math.floorDiv(Instant.now().getEpochSecond(), 300) * 300 - 600;
        String start = Instant.ofEpochSecond(bucket).toString();
        Res ingest = call(
                "POST",
                a,
                "/v1/me/health/ingest",
                Map.of(
                        "idempotency_key", UUID.randomUUID().toString(),
                        "platform", "healthkit",
                        "devices",
                                List.of(Map.of(
                                        "external_id",
                                        "watch-" + tag,
                                        "name",
                                        "Test Watch",
                                        "kind",
                                        "wearable",
                                        "metrics",
                                        List.of("steps", "heart_rate"))),
                        "buckets",
                                List.of(
                                        Map.of("metric", "steps", "start", start, "value", 1234),
                                        Map.of("metric", "heart_rate", "start", start, "value", 70))));
        check(
                "health ingest accepted",
                ingest.status() == 202 && ingest.at("/inserted").asInt() == 2,
                ingest.body());
        Res series = call("GET", a, "/v1/me/health/series/steps?days=1", null);
        double stepTotal = 0;
        for (JsonNode p : series.at("/points")) stepTotal += p.get("value").asDouble();
        check("steps series reads the synced value", stepTotal == 1234, series.body());
        check(
                "manual weight reading",
                call("POST", a, "/v1/me/health/manual", Map.of("metric", "weight", "value", 72.4))
                                .status()
                        == 201,
                "");
        check("dashboard", call("GET", a, "/v1/me/health/dashboard", null).status() == 200, "");
        for (String tab : List.of(
                "overview",
                "tabs/vitals",
                "tabs/sleep",
                "tabs/heart",
                "tabs/respiratory",
                "tabs/body",
                "tabs/mental",
                "tabs/digital")) {
            Res r = call("GET", a, "/v1/me/health/" + tab, null);
            check("My Health " + tab, r.status() == 200, r.status());
        }

        // Training and nutrition.
        Res workout = call(
                "POST",
                a,
                "/v1/me/workouts",
                Map.of(
                        "type",
                        "walk",
                        "title",
                        "System walk",
                        "started_at",
                        Instant.now().minusSeconds(3600).toString(),
                        "duration_s",
                        1800,
                        "distance_m",
                        2400));
        check("log a workout", workout.status() == 201, workout.body());
        check(
                "training view counts it",
                call("GET", a, "/v1/me/training", null).at("/total_sessions").asInt() == 1,
                "");
        Res food = call("GET", a, "/v1/foods/search?q=banana", null);
        Res meal = call(
                "POST",
                a,
                "/v1/me/meals",
                Map.of(
                        "slot",
                        "snack",
                        "eaten_at",
                        Instant.now().minusSeconds(60).toString(),
                        "items",
                        List.of(Map.of("food_id", food.at("/0/id").asString(), "quantity_g", 118))));
        check(
                "log a meal from the food catalogue",
                meal.status() == 201 && meal.at("/kcal").asInt() == 105,
                meal.body());

        // Care: a dose 30 minutes ago, marked taken.
        ZonedDateTime dose = ZonedDateTime.now(CHICAGO).minusMinutes(30).truncatedTo(ChronoUnit.MINUTES);
        Res med = call(
                "POST",
                a,
                "/v1/me/medications",
                Map.of(
                        "name",
                        "Metformin",
                        "form",
                        "tablet",
                        "strength",
                        "500 mg",
                        "times",
                        List.of(dose.toLocalTime().toString()),
                        "started_on",
                        LocalDate.now(CHICAGO).minusDays(3).toString()));
        check("add a medication", med.status() == 201, med.body());
        Res taken = call(
                "PUT",
                a,
                "/v1/me/medications/" + med.at("/id").asString() + "/doses/" + iso(dose),
                Map.of("status", "taken"));
        check("mark the dose taken", taken.status() == 200, taken.body());
        Res appt = call(
                "POST",
                a,
                "/v1/me/appointments",
                Map.of(
                        "clinician",
                        "Dr. Test",
                        "specialty",
                        "Cardiology",
                        "starts_at",
                        iso(ZonedDateTime.now(CHICAGO).plusDays(1)),
                        "mode",
                        "in_person",
                        "state",
                        "confirmed"));
        check("add an appointment", appt.status() == 201, appt.body());
        check(
                "next appointment",
                call("GET", a, "/v1/me/appointments?upcoming=true&limit=1", null)
                                .body()
                                .size()
                        == 1,
                "");
        check(
                "emergency medical ID",
                call("GET", a, "/v1/me/emergency-profile", null)
                        .at("/medications/0")
                        .asString()
                        .startsWith("Metformin"),
                "");

        // Records: signed upload over real HTTP, confirm, signed download.
        Res record = call(
                "POST",
                a,
                "/v1/me/records",
                Map.of(
                        "title",
                        "Scan",
                        "type",
                        "imaging",
                        "date",
                        LocalDate.now().toString()));
        byte[] pdf = ("%PDF-1.7\nsystem check " + tag + "\n").getBytes();
        Res upload = call(
                "POST",
                a,
                "/v1/me/records/" + record.at("/id").asString() + "/upload",
                Map.of("file_name", "scan.pdf", "content_type", "application/pdf", "size", pdf.length));
        Res put = call("PUT", null, upload.at("/upload_url").asString(), pdf);
        check("upload the file to the signed URL", put.status() / 100 == 2, put.status());
        Res confirm = call("POST", a, "/v1/me/records/" + record.at("/id").asString() + "/confirm", null);
        check(
                "confirm: the server checked the stored file",
                "attached".equals(confirm.at("/file/status").asString()),
                confirm.body());
        Res link = call("GET", a, "/v1/me/records/" + record.at("/id").asString() + "/download", null);
        Res file = call("GET", null, link.at("/url").asString(), null);
        check("download returns the same bytes", Arrays.equals(file.raw(), pdf), file.status());

        Res lab = call(
                "POST",
                a,
                "/v1/me/labs",
                Map.of(
                        "name",
                        "Lipid Profile",
                        "short_name",
                        "Lipids",
                        "collected_at",
                        Instant.now().minusSeconds(86400).toString(),
                        "results",
                        List.of(Map.of("name", "LDL", "value", 120, "unit", "mg/dL", "low", 0, "high", 100))));
        check("type in a lab panel", lab.status() == 201, lab.body());
        check(
                "blood-group results are refused",
                call(
                                        "POST",
                                        a,
                                        "/v1/me/labs",
                                        Map.of(
                                                "name",
                                                "Group",
                                                "short_name",
                                                "G",
                                                "collected_at",
                                                Instant.now().toString(),
                                                "results",
                                                List.of(Map.of(
                                                        "name", "ABO blood group", "value", 1, "low", 0, "high", 2))))
                                .status()
                        == 422,
                "");
        check(
                "the lab result is in the feed",
                call("GET", a, "/v1/me/notifications", null)
                        .at("/0/title")
                        .asString()
                        .contains("lab"),
                "");
        check(
                "goal with computed status",
                call(
                                                "POST",
                                                a,
                                                "/v1/me/goals",
                                                Map.of(
                                                        "title",
                                                        "Walk 1,000 steps daily",
                                                        "metric",
                                                        "steps",
                                                        "target",
                                                        1000))
                                        .status()
                                == 201
                        && call("GET", a, "/v1/me/goals/overview", null)
                                .at("/goals/0/status")
                                .asString()
                                .equals("on_track"),
                "");
        check("library", call("GET", a, "/v1/library", null).at("/trending").size() > 0, "");

        // Sharing: grant → read → refused scope → revoke → refused, all in the access log.
        Res share = call(
                "POST",
                a,
                "/v1/me/shares",
                Map.of("email", bEmail, "label", "Sam", "kind", "family", "scopes", List.of("activity", "records")));
        check("share activity and records", share.status() == 201, share.body());
        check(
                "viewer reads shared workouts",
                call("GET", b, "/v1/users/" + aId + "/workouts", null).status() == 200,
                "");
        check(
                "viewer reads shared records (the scan and the lab record)",
                call("GET", b, "/v1/users/" + aId + "/records", null).body().size() == 2,
                "");
        Res vitals = call("GET", b, "/v1/users/" + aId + "/health/series/heart_rate?days=1", null);
        check(
                "vitals weren't shared → 403",
                vitals.status() == 403 && vitals.at("/error/code").asString().equals("scope_not_granted"),
                vitals.body());
        check(
                "viewer's family view shows the owner",
                call("GET", b, "/v1/me/family", null).body().size() == 2,
                "");
        check(
                "owner sees the reads in the access log",
                call("GET", a, "/v1/me/access-log", null).body().size() >= 3,
                "");
        check(
                "revoke",
                call("DELETE", a, "/v1/me/shares/" + share.at("/id").asString(), null)
                                .status()
                        == 204,
                "");
        Res after = call("GET", b, "/v1/users/" + aId + "/workouts", null);
        check(
                "after revoking → 403 revoked",
                after.status() == 403 && after.at("/error/code").asString().equals("revoked"),
                after.body());

        String secret = Env.read().get("JOB_SECRET");
        if (secret != null && !secret.isBlank()) {
            HttpRequest job = HttpRequest.newBuilder(URI.create(base + "/internal/jobs/nightly"))
                    .header("X-Job-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMinutes(10))
                    .build();
            HttpResponse<String> r = http.send(job, HttpResponse.BodyHandlers.ofString());
            check(
                    "nightly job runs",
                    r.statusCode() == 200
                            && json.readTree(r.body()).get("users_failed").asInt() == 0,
                    r.body());
        }

        // Data rights.
        Res export = call("GET", a, "/v1/me/export", null);
        check(
                "export has every section",
                export.status() == 200
                        && export.at("/medications").size() == 1
                        && export.at("/records").size() == 2
                        && export.at("/workouts").size() == 1,
                export.status());
        check("delete the account", call("DELETE", a, "/v1/me", null).status() == 204, "");
        check("delete the second account", call("DELETE", b, "/v1/me", null).status() == 204, "");

        System.out.printf("%n%d steps, %d failed%n", steps, failures.size());
    }

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8000";
        Settings settings = Env.settings();
        if (!settings.isDev()) {
            throw new IllegalStateException("SystemCheck signs in with dev tokens: local or test only.");
        }
        SystemCheck check = new SystemCheck(base, settings);
        check.run();
        System.exit(check.failures.isEmpty() ? 0 : 1);
    }
}
