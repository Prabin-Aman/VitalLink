package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.health.Catalogue;
import com.vitallink.api.health.Catalogue.Metric;
import com.vitallink.api.health.HealthService;
import com.vitallink.api.support.ApiTest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Health core: catalogue, ingest, manual readings, series, latest, dashboard, devices. */
class HealthTest extends ApiTest {

    /** Users here live in {@link ApiTest#AFTERNOON}, so fixed morning times are always in the past. */
    static final ZoneId ZONE = AFTERNOON;

    @Override
    protected String user() {
        return afternoonUser();
    }

    protected static ZonedDateTime todayAt(int hour, int minute, int daysAgo) {
        return todayAt(ZONE, hour, minute, daysAgo);
    }

    /** Floor to a 5-minute boundary, ISO with offset. */
    static String bucket(ZonedDateTime t) {
        long ts = Math.floorDiv(t.toEpochSecond(), 300) * 300;
        return Instant.ofEpochSecond(ts).atZone(ZONE).toOffsetDateTime().toString();
    }

    static Map<String, Object> b(String metric, ZonedDateTime when, double value, int samples) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric);
        m.put("start", bucket(when));
        m.put("value", value);
        m.put("samples", samples);
        return m;
    }

    static Map<String, Object> b(String metric, ZonedDateTime when, double value) {
        return b(metric, when, value, 1);
    }

    Res ingest(String auth, List<Map<String, Object>> buckets, UUID key, List<Map<String, Object>> devices) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotency_key", (key != null ? key : UUID.randomUUID()).toString());
        body.put("platform", "healthkit");
        body.put("devices", devices);
        body.put("buckets", buckets);
        return post(auth, "/v1/me/health/ingest", body);
    }

    Res ingest(String auth, List<Map<String, Object>> buckets) {
        return ingest(auth, buckets, null, List.of());
    }

    static double sum(JsonNode points) {
        double total = 0;
        for (JsonNode p : points) {
            total += p.get("value").asDouble();
        }
        return total;
    }

    static List<Double> values(JsonNode points) {
        return StreamSupport.stream(points.spliterator(), false)
                .map(p -> p.get("value").asDouble())
                .toList();
    }

    // ---------------------------------------------------------------- catalogue

    @Test
    void databaseCatalogueMatchesCode() throws Exception {
        Map<Integer, Metric> stored = new HashMap<>();
        try (var c = sql();
                var rs = c.createStatement()
                        .executeQuery("SELECT id, code, scope::text, unit, aggregation::text, min_value, max_value, "
                                + "manual_allowed, normal_low, normal_high FROM vitallink.metrics")) {
            while (rs.next()) {
                Double low = rs.getObject(9) == null ? null : rs.getDouble(9);
                Double high = rs.getObject(10) == null ? null : rs.getDouble(10);
                Metric m = Catalogue.get(rs.getString(2));
                stored.put(
                        rs.getInt(1),
                        new Metric(
                                rs.getInt(1),
                                rs.getString(2),
                                com.vitallink.api.core.audit.Audit.Scope.valueOf(rs.getString(3)),
                                rs.getString(4),
                                Catalogue.Agg.valueOf(rs.getString(5)),
                                rs.getDouble(6),
                                rs.getDouble(7),
                                rs.getBoolean(8),
                                low,
                                high,
                                m == null ? null : m.label()));
            }
        }
        assertThat(stored.values()).containsExactlyInAnyOrderElementsOf(Catalogue.METRICS);
    }

    @Test
    void catalogueHasNoBloodFeatures() {
        List<String> banned = List.of("blood_type", "blood_group", "donor", "blood_bank");
        assertThat(Catalogue.METRICS)
                .noneMatch(m -> banned.stream().anyMatch(w -> m.code().contains(w)));
    }

    // ---------------------------------------------------------------- ingest

    @Test
    void ingestInsertsThenUpdatesThenIsUnchanged() {
        String u = user();
        ZonedDateTime when = todayAt(8, 40, 0);
        Res first = ingest(u, List.of(b("steps", when, 400), b("heart_rate", when, 71.5, 60)));
        assertThat(first.status()).as(first.text()).isEqualTo(202);
        assertThat(first.at("/submitted").asInt()).isEqualTo(2);
        assertThat(first.at("/inserted").asInt()).isEqualTo(2);
        assertThat(first.at("/updated").asInt()).isZero();
        assertThat(first.at("/unchanged").asInt()).isZero();
        assertThat(first.at("/replayed").asBoolean()).isFalse();
        // Late watch data corrects the bucket: the new value wins.
        Res second = ingest(u, List.of(b("steps", when, 412), b("heart_rate", when, 71.5, 60)));
        assertThat(List.of(
                        second.at("/inserted").asInt(),
                        second.at("/updated").asInt(),
                        second.at("/unchanged").asInt()))
                .containsExactly(0, 1, 1);
        assertThat(sum(get(u, "/v1/me/health/series/steps?days=1").at("/points")))
                .isEqualTo(412);
    }

    @Test
    void sameKeySamePayloadReplays() {
        String u = user();
        UUID key = UUID.randomUUID();
        var buckets = List.of(b("steps", todayAt(9, 0, 0), 100));
        Res first = ingest(u, buckets, key, List.of());
        Res again = ingest(u, buckets, key, List.of());
        assertThat(again.status()).isEqualTo(202);
        assertThat(again.at("/batch_id")).isEqualTo(first.at("/batch_id"));
        assertThat(again.at("/inserted")).isEqualTo(first.at("/inserted"));
        assertThat(again.at("/replayed").asBoolean()).isTrue();
    }

    @Test
    void sameKeyDifferentPayloadIs409() {
        String u = user();
        UUID key = UUID.randomUUID();
        ingest(u, List.of(b("steps", todayAt(9, 0, 0), 100)), key, List.of());
        Res res = ingest(u, List.of(b("steps", todayAt(9, 0, 0), 101)), key, List.of());
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.code()).isEqualTo("idempotency_conflict");
    }

    @Test
    void keysArePerUser() {
        UUID key = UUID.randomUUID();
        String a = user();
        String other = user();
        assertThat(ingest(a, List.of(b("steps", todayAt(9, 0, 0), 1)), key, List.of())
                        .status())
                .isEqualTo(202);
        assertThat(ingest(other, List.of(b("steps", todayAt(9, 0, 0), 2)), key, List.of())
                        .at("/replayed")
                        .asBoolean())
                .isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "blood_type,1,0,unknown metric",
        "heart_rate,400,0,value outside",
        "steps,-1,0,value outside",
        "steps,1,120,5-minute boundary",
        "steps,1,-1,future"
    })
    void invalidBucketsRejectTheWholeBatch(String metric, double value, int shiftSeconds, String problem) {
        String u = user();
        ZonedDateTime start = todayAt(0, 0, 0).plusSeconds(shiftSeconds);
        if (problem.equals("future")) {
            long now = Math.floorDiv(Instant.now().getEpochSecond(), 300) * 300;
            start = Instant.ofEpochSecond(now).atZone(ZONE).plusHours(2);
        }
        Map<String, Object> bad = Map.of("metric", metric, "start", iso(start), "value", value);
        Res res = ingest(u, List.of(b("steps", todayAt(1, 0, 0), 5), bad));
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.code()).isEqualTo("invalid_buckets");
        assertThat(res.at("/error/detail/0/problem").asString()).contains(problem);
        assertThat(res.at("/error/detail/0/index").asInt()).isEqualTo(1);
        // nothing was saved, not even the valid bucket
        assertThat(sum(get(u, "/v1/me/health/series/steps?days=1").at("/points")))
                .isZero();
    }

    @Test
    void duplicateBucketInOneBatchIsRejected() {
        String u = user();
        ZonedDateTime when = todayAt(10, 0, 0);
        assertThat(ingest(u, List.of(b("steps", when, 1), b("steps", when, 2))).status())
                .isEqualTo(422);
    }

    @Test
    void oversizedBatchIs413() {
        String u = user();
        ZonedDateTime start = todayAt(0, 0, 30);
        var buckets = IntStream.range(0, 5001)
                .mapToObj(i -> b("steps", start.plusMinutes(5L * i), 1))
                .toList();
        assertThat(ingest(u, buckets).status()).isEqualTo(413);
    }

    // ---------------------------------------------------------------- series

    @Test
    void daySeriesFillsGapsInLocalTime() {
        String u = user();
        ingest(u, List.of(b("steps", todayAt(9, 0, 2), 3000), b("steps", todayAt(23, 55, 2), 1000)));
        JsonNode points = get(u, "/v1/me/health/series/steps?days=7").at("/points");
        assertThat(points.size()).isEqualTo(7);
        // buckets are local midnights in Chicago
        for (JsonNode p : points) {
            assertThat(OffsetDateTime.parse(p.get("bucket").asString())
                            .atZoneSameInstant(ZONE)
                            .toLocalTime())
                    .isEqualTo(LocalTime.MIDNIGHT);
        }
        assertThat(values(points)).containsExactly(0.0, 0.0, 0.0, 0.0, 4000.0, 0.0, 0.0);
    }

    @Test
    void averageSeriesSkipsEmptyBuckets() {
        String u = user();
        ingest(u, List.of(b("heart_rate", todayAt(8, 0, 0), 60), b("heart_rate", todayAt(9, 0, 0), 80)));
        Res res = get(u, "/v1/me/health/series/heart_rate?days=7");
        assertThat(res.at("/unit").asString()).isEqualTo("bpm");
        assertThat(values(res.at("/points"))).containsExactly(70.0);
    }

    @Test
    void weekSeries() {
        String u = user();
        ingest(u, List.of(b("distance", todayAt(7, 0, 0), 1000), b("distance", todayAt(7, 0, 1), 500)));
        JsonNode points =
                get(u, "/v1/me/health/series/distance?days=7&interval=week").at("/points");
        assertThat(sum(points)).isEqualTo(1500);
        for (JsonNode p : points) {
            assertThat(OffsetDateTime.parse(p.get("bucket").asString())
                            .atZoneSameInstant(ZONE)
                            .getDayOfWeek())
                    .isEqualTo(DayOfWeek.MONDAY);
        }
    }

    @Test
    void unknownMetricSeriesIs404() {
        assertThat(get(user(), "/v1/me/health/series/blood_type").status()).isEqualTo(404);
    }

    @Test
    void bloodPressurePairs() {
        String u = user();
        ZonedDateTime when = todayAt(8, 40, 0);
        ingest(u, List.of(b("bp_systolic", when, 118), b("bp_diastolic", when, 76)));
        Res bp = get(u, "/v1/me/health/series/blood_pressure?days=7");
        assertThat(values(bp.at("/systolic/points"))).containsExactly(118.0);
        assertThat(values(bp.at("/diastolic/points"))).containsExactly(76.0);
        Res latest = get(u, "/v1/me/health/latest?metrics=blood_pressure,heart_rate");
        assertThat(latest.body().size()).isEqualTo(1);
        JsonNode r = latest.at("/0");
        assertThat(r.get("metric").asString()).isEqualTo("blood_pressure");
        assertThat(r.get("value").asDouble()).isEqualTo(118);
        assertThat(r.get("value2").asDouble()).isEqualTo(76);
        assertThat(r.get("unit").asString()).isEqualTo("mmHg");
        assertThat(r.get("assessment").asString()).isEqualTo("Normal");
    }

    @ParameterizedTest
    @CsvSource({"118,76,Normal", "124,78,Elevated", "132,78,High (stage 1)", "118,92,High (stage 2)"})
    void bpCategories(int sys, int dia, String category) {
        assertThat(HealthService.bpCategory(sys, dia).getKey()).isEqualTo(category);
    }

    // ---------------------------------------------------------------- manual

    @Test
    void manualReading() {
        String u = user();
        Res res = post(u, "/v1/me/health/manual", Map.of("metric", "weight", "value", 72.4));
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.at("/unit").asString()).isEqualTo("kg");
        assertThat(get(u, "/v1/me/health/latest?metrics=weight").at("/0/value").asDouble())
                .isEqualTo(72.4);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"metric\": \"steps\", \"value\": 100}",
                "{\"metric\": \"weight\", \"value\": 1000}",
                "{\"metric\": \"weight\", \"value\": 70, \"x\": 1}"
            })
    void manualRejects(String body) {
        assertThat(post(user(), "/v1/me/health/manual", body).status()).isEqualTo(422);
    }

    // ---------------------------------------------------------------- dashboard

    @Test
    void dashboardWithoutData() {
        Res dash = get(user(), "/v1/me/health/dashboard");
        assertThat(dash.at("/health_score").isNull()).isTrue();
        List<String> metrics = new ArrayList<>();
        dash.at("/metrics").forEach(m -> {
            metrics.add(m.get("metric").asString());
            assertThat(m.get("range").asString()).isEqualTo("no-data");
        });
        assertThat(metrics).containsExactly("heart_rate", "blood_pressure", "oxygen_saturation", "weight", "glucose");
    }

    @Test
    void dashboardWithData() {
        String u = user();
        ZonedDateTime now = ZonedDateTime.now(ZONE).minusMinutes(10);
        List<Map<String, Object>> buckets = new ArrayList<>(List.of(
                b("heart_rate", now, 72),
                b("oxygen_saturation", now, 93),
                b("glucose", now, 92),
                b("bp_systolic", now, 132),
                b("bp_diastolic", now, 78)));
        // readiness: 30-day baselines plus last night's sleep
        for (int d = 1; d < 15; d++) {
            buckets.add(b("resting_heart_rate", todayAt(6, 0, d), 60));
            buckets.add(b("hrv", todayAt(6, 0, d), 50));
        }
        buckets.add(b("resting_heart_rate", todayAt(0, 5, 0), 60));
        buckets.add(b("hrv", todayAt(0, 5, 0), 50));
        ZonedDateTime night = todayAt(23, 0, 1);
        for (int i = 0; i < 96; i++) {
            buckets.add(b("sleep_asleep", night.plusMinutes(5L * i), 5)); // 8 h
        }
        assertThat(ingest(u, buckets).status()).isEqualTo(202);

        Res dash = get(u, "/v1/me/health/dashboard");
        Map<String, JsonNode> cards = new HashMap<>();
        dash.at("/metrics").forEach(m -> cards.put(m.get("metric").asString(), m));
        assertThat(cards.get("heart_rate").get("latest").asDouble()).isEqualTo(72);
        assertThat(cards.get("heart_rate").get("range").asString()).isEqualTo("normal");
        assertThat(cards.get("oxygen_saturation").get("range").asString()).isEqualTo("low");
        assertThat(cards.get("blood_pressure").get("range").asString()).isEqualTo("high");
        assertThat(cards.get("weight").get("range").asString()).isEqualTo("no-data");
        assertThat(dash.at("/health_score").asInt()).isEqualTo(100);
        assertThat(dash.at("/metrics/0/avg_7d").asDouble()).isEqualTo(72);
    }

    // ---------------------------------------------------------------- devices

    @Test
    void devicesRegistry() {
        String u = user();
        Map<String, Object> watch = Map.of(
                "external_id", "watch-1",
                "name", "Apple Watch S9",
                "kind", "wearable",
                "metrics", List.of("heart_rate", "steps", "sleep_deep", "oxygen_saturation"));
        Map<String, Object> cuff = Map.of(
                "external_id", "cuff-1",
                "name", "Omron BP Monitor",
                "kind", "home_monitor",
                "metrics", List.of("bp_systolic", "bp_diastolic", "heart_rate"));
        ingest(u, List.of(b("steps", todayAt(1, 0, 0), 1)), null, List.of(watch, cuff));
        Map<String, JsonNode> devices = new HashMap<>();
        get(u, "/v1/me/devices").body().forEach(d -> devices.put(d.get("name").asString(), d));
        assertThat(devices.get("Apple Watch S9").get("data").toString())
                .isEqualTo("[\"Heart rate\",\"Blood oxygen\",\"Activity\",\"Sleep\"]");
        assertThat(devices.get("Apple Watch S9").get("short_data").toString())
                .isEqualTo("[\"Heart rate\",\"Blood oxygen\",\"Activity\"]");
        assertThat(devices.get("Omron BP Monitor").get("icon").asString()).isEqualTo("monitor");
        assertThat(devices.get("Omron BP Monitor").get("data").toString()).isEqualTo("[\"Pulse\",\"Blood pressure\"]");
        assertThat(devices.values()).allMatch(d -> d.get("status").asString().equals("connected"));

        String gone = devices.get("Omron BP Monitor").get("id").asString();
        assertThat(delete(u, "/v1/me/devices/" + gone).status()).isEqualTo(204);
        assertThat(delete(u, "/v1/me/devices/" + gone).status()).isEqualTo(404);
    }

    @Test
    void syncStatusSummarisesThePhonesUploads() {
        String u = user();
        Res none = get(u, "/v1/me/sync");
        assertThat(none.at("/platforms").size()).isZero();
        assertThat(none.at("/recent").size()).isZero();

        Map<String, Object> watch = Map.of(
                "external_id", "watch-1", "name", "Apple Watch S9", "kind", "wearable", "metrics", List.of("steps"));
        ingest(u, List.of(b("steps", todayAt(1, 0, 0), 10), b("steps", todayAt(1, 5, 0), 20)), null, List.of(watch));
        ingest(u, List.of(b("steps", todayAt(1, 0, 0), 10), b("steps", todayAt(1, 10, 0), 5)));

        Res s = get(u, "/v1/me/sync");
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.at("/platforms").size()).isEqualTo(1);
        assertThat(s.at("/platforms/0/platform").asString()).isEqualTo("healthkit");
        assertThat(s.at("/platforms/0/uploads_week").asInt()).isEqualTo(2);
        assertThat(s.at("/platforms/0/readings_week").asLong()).isEqualTo(4);
        assertThat(s.at("/platforms/0/changed_week").asLong()).isEqualTo(3); // the repeated bucket is unchanged
        assertThat(s.at("/platforms/0/devices").asInt()).isEqualTo(1);
        assertThat(s.at("/platforms/0/last_upload_at").isNull()).isFalse();
        // Newest first.
        assertThat(s.at("/recent/0/inserted").asInt()).isEqualTo(1);
        assertThat(s.at("/recent/0/unchanged").asInt()).isEqualTo(1);
        assertThat(s.at("/recent/1/inserted").asInt()).isEqualTo(2);
        assertThat(get(user(), "/v1/me/sync").at("/recent").size()).isZero();
    }

    @Test
    void phoneSimUploadsAreAcceptedAndRepeatAsUnchanged() {
        String u = user();
        java.time.Instant now = java.time.Instant.now();
        var buckets = com.vitallink.api.tools.PhoneSim.buckets(now, 3);
        assertThat(buckets).hasSize(3 * 12 * 3);
        assertThat(buckets).isEqualTo(com.vitallink.api.tools.PhoneSim.buckets(now, 3)); // same time, same values
        Res first = ingest(u, buckets, null, com.vitallink.api.tools.PhoneSim.devices("healthkit"));
        assertThat(first.status()).as(first.text()).isEqualTo(202);
        assertThat(first.at("/inserted").asInt()).isEqualTo(buckets.size());
        Res again = ingest(u, buckets);
        assertThat(again.at("/unchanged").asInt()).isEqualTo(buckets.size());
        assertThat(get(u, "/v1/me/devices").body().size()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- isolation

    @Test
    void usersNeverSeeEachOthersReadings() {
        String a = user();
        String other = user();
        ingest(a, List.of(b("steps", todayAt(1, 0, 0), 999)));
        assertThat(sum(get(other, "/v1/me/health/series/steps?days=1").at("/points")))
                .isZero();
        assertThat(get(other, "/v1/me/devices").body().size()).isZero();
    }

    static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
