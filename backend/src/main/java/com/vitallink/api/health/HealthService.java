package com.vitallink.api.health;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.Num;
import com.vitallink.api.health.Catalogue.Agg;
import com.vitallink.api.health.Catalogue.Metric;
import com.vitallink.api.health.HealthDtos.BloodPressureSeries;
import com.vitallink.api.health.HealthDtos.BucketIn;
import com.vitallink.api.health.HealthDtos.Dashboard;
import com.vitallink.api.health.HealthDtos.DeviceIn;
import com.vitallink.api.health.HealthDtos.DeviceKind;
import com.vitallink.api.health.HealthDtos.DeviceOut;
import com.vitallink.api.health.HealthDtos.ImportOut;
import com.vitallink.api.health.HealthDtos.IngestIn;
import com.vitallink.api.health.HealthDtos.IngestOut;
import com.vitallink.api.health.HealthDtos.Interval;
import com.vitallink.api.health.HealthDtos.ManualIn;
import com.vitallink.api.health.HealthDtos.MetricSummary;
import com.vitallink.api.health.HealthDtos.RangeStatus;
import com.vitallink.api.health.HealthDtos.ReadingOut;
import com.vitallink.api.health.HealthDtos.Series;
import com.vitallink.api.health.HealthDtos.SeriesPoint;
import com.vitallink.api.health.HealthDtos.SyncPlatform;
import com.vitallink.api.health.HealthDtos.SyncStatus;
import com.vitallink.api.health.HealthDtos.SyncUpload;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Health rules: ingest, manual readings, series, latest readings, dashboard, devices.
 *
 * <p>Aggregation happens in Postgres (never by pulling raw rows into Java), grouped by the user's local
 * day, hour, week or month (docs/04 §4.1, docs/08 performance).
 */
@Service
public class HealthService {

    static final int MAX_BUCKETS = 5000;
    static final Duration FUTURE_SLACK = Duration.ofMinutes(5);
    static final OffsetDateTime EARLIEST = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    static final List<String> DASHBOARD_CARDS =
            List.of("heart_rate", Catalogue.BLOOD_PRESSURE, "oxygen_saturation", "weight", "glucose");
    static final Metric SYS = Catalogue.get("bp_systolic");
    static final Metric DIA = Catalogue.get("bp_diastolic");
    static final Map<DeviceKind, Duration> OVERDUE_AFTER = Map.of(
            DeviceKind.phone, Duration.ofHours(24),
            DeviceKind.wearable, Duration.ofHours(24),
            DeviceKind.continuous, Duration.ofHours(24),
            DeviceKind.home_monitor, Duration.ofDays(3));

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public HealthService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    static OffsetDateTime now() {
        return com.vitallink.api.core.Clock.now();
    }

    static Metric metric(String code) {
        Metric m = Catalogue.get(code);
        if (m == null) {
            throw new ApiError(404, "unknown_metric", "There's no metric called '" + code + "'.");
        }
        return m;
    }

    static int decimals(Metric m) {
        return switch (m.unit()) {
            case "bpm", "%", "mg/dL", "mmHg", "count", "kcal", "min" -> 0;
            default -> 1;
        };
    }

    static Double rounded(Metric m, Double value) {
        return Num.round(value, decimals(m));
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ---------------------------------------------------------------- ingest

    private static final String UPSERT = """
            INSERT INTO vitallink.health_readings (user_id, metric_id, recorded_at, source, value, samples)
            SELECT :user_id, u.metric_id, to_timestamp(u.epoch), CAST(:source AS vitallink.reading_source),
                   u.value, u.samples
            FROM unnest(CAST(:metric_ids AS smallint[]), CAST(:epochs AS bigint[]),
                        CAST(:values AS double precision[]), CAST(:samples AS smallint[]))
                 AS u(metric_id, epoch, value, samples)
            ON CONFLICT (user_id, metric_id, recorded_at, source) DO UPDATE
              SET value = EXCLUDED.value, samples = EXCLUDED.samples, updated_at = now()
              WHERE (health_readings.value, health_readings.samples)
                    IS DISTINCT FROM (EXCLUDED.value, EXCLUDED.samples)
            RETURNING (xmax = 0) AS inserted
            """;

    /** One reading to store: metric id, bucket start, value, sample count. */
    public record Row(int metricId, Instant start, double value, int samples) {}

    /** Returns {inserted, updated}. Rows whose stored value didn't change aren't returned by Postgres. */
    public int[] upsert(UUID userId, String source, List<Row> rows) {
        List<Boolean> flags = jdbc.sql(UPSERT)
                .param("user_id", userId)
                .param("source", source)
                .param("metric_ids", rows.stream().map(Row::metricId).toArray(Integer[]::new))
                .param(
                        "epochs",
                        rows.stream().map(r -> r.start().getEpochSecond()).toArray(Long[]::new))
                .param("values", rows.stream().map(Row::value).toArray(Double[]::new))
                .param("samples", rows.stream().map(Row::samples).toArray(Integer[]::new))
                .query((rs, i) -> rs.getBoolean("inserted"))
                .list();
        int inserted = (int) flags.stream().filter(b -> b).count();
        return new int[] {inserted, flags.size() - inserted};
    }

    private static Map<String, Object> problem(int index, String code, String problem) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("index", index);
        p.put("metric", code);
        p.put("problem", problem);
        return p;
    }

    static String checkBucket(BucketIn b, OffsetDateTime now) {
        Metric m = Catalogue.get(b.metric());
        if (m == null) {
            return "unknown metric";
        }
        if (b.start().toEpochSecond() % 300 != 0 || b.start().getNano() != 0) {
            return "start is not on a 5-minute boundary";
        }
        if (b.start().isAfter(now.plus(FUTURE_SLACK))) {
            return "start is in the future";
        }
        if (b.start().isBefore(EARLIEST)) {
            return "start is before 2000";
        }
        if (b.value() < m.minValue() || b.value() > m.maxValue()) {
            return "value outside %s–%s %s".formatted(fmt(m.minValue()), fmt(m.maxValue()), m.unit());
        }
        return null;
    }

    private byte[] digest(IngestIn body) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Idempotent batch upsert (docs/07 §7.2). Same key + same payload replays; a different payload → 409. */
    public IngestOut ingest(CurrentUser me, IngestIn body) {
        if (body.buckets().size() > MAX_BUCKETS) {
            throw new ApiError(413, "batch_too_large", "Send at most " + MAX_BUCKETS + " buckets per batch.");
        }
        byte[] digest = digest(body);

        // Serialise requests that reuse a key, so a retry racing the original can't double-apply.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", "ingest:" + me.id() + ":" + body.idempotencyKey())
                .query()
                .listOfRows();
        Optional<Map<String, Object>> previous =
                jdbc.sql("""
                        SELECT id, payload_sha256, submitted, inserted, updated, unchanged
                        FROM vitallink.ingest_batches WHERE user_id = :u AND idempotency_key = :k
                        """).param("u", me.id()).param("k", body.idempotencyKey()).query().listOfRows().stream()
                        .findFirst();
        if (previous.isPresent()) {
            Map<String, Object> p = previous.get();
            if (!Arrays.equals((byte[]) p.get("payload_sha256"), digest)) {
                throw new ApiError(
                        409, "idempotency_conflict", "This idempotency key was already used for different data.");
            }
            return new IngestOut(
                    ((Number) p.get("id")).longValue(),
                    ((Number) p.get("submitted")).intValue(),
                    ((Number) p.get("inserted")).intValue(),
                    ((Number) p.get("updated")).intValue(),
                    ((Number) p.get("unchanged")).intValue(),
                    true);
        }

        OffsetDateTime now = now();
        List<Map<String, Object>> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < body.buckets().size(); i++) {
            BucketIn b = body.buckets().get(i);
            String problem = checkBucket(b, now);
            String key = b.metric() + "@" + b.start().toInstant();
            if (problem == null && seen.contains(key)) {
                problem = "duplicate bucket in this batch";
            }
            if (problem != null) {
                problems.add(problem(i, b.metric(), problem));
                continue;
            }
            seen.add(key);
            rows.add(new Row(Catalogue.get(b.metric()).id(), b.start().toInstant(), b.value(), b.samples()));
        }
        for (DeviceIn d : body.devices()) {
            for (String code : d.metrics()) {
                if (Catalogue.get(code) == null) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("device", d.externalId());
                    p.put("metric", code);
                    p.put("problem", "unknown metric");
                    problems.add(p);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiError(
                    422,
                    "invalid_buckets",
                    "Some buckets were rejected; nothing was saved.",
                    problems.subList(0, Math.min(20, problems.size())));
        }

        int[] counts = upsert(me.id(), body.platform(), rows);
        for (DeviceIn d : body.devices()) {
            jdbc.sql("""
                            INSERT INTO vitallink.devices (id, user_id, platform, external_id, name, kind, metric_ids)
                            VALUES (:id, :user_id, CAST(:platform AS vitallink.reading_source), :external_id, :name,
                                    CAST(:kind AS vitallink.device_kind), CAST(:metric_ids AS smallint[]))
                            ON CONFLICT (user_id, platform, external_id) DO UPDATE
                              SET name = EXCLUDED.name, kind = EXCLUDED.kind,
                                  metric_ids = EXCLUDED.metric_ids, last_sync_at = now()
                            """)
                    .param("id", Ids.uuid7())
                    .param("user_id", me.id())
                    .param("platform", body.platform())
                    .param("external_id", d.externalId())
                    .param("name", d.name())
                    .param("kind", d.kind().name())
                    .param(
                            "metric_ids",
                            d.metrics().stream()
                                    .map(c -> Catalogue.get(c).id())
                                    .distinct()
                                    .sorted()
                                    .toArray(Integer[]::new))
                    .update();
        }
        int unchanged = rows.size() - counts[0] - counts[1];
        long batchId = jdbc.sql("""
                        INSERT INTO vitallink.ingest_batches
                          (user_id, idempotency_key, payload_sha256, platform, submitted, inserted, updated, unchanged)
                        VALUES (:u, :k, :sha, CAST(:platform AS vitallink.reading_source), :submitted, :inserted,
                                :updated, :unchanged)
                        RETURNING id
                        """)
                .param("u", me.id())
                .param("k", body.idempotencyKey())
                .param("sha", digest)
                .param("platform", body.platform())
                .param("submitted", rows.size())
                .param("inserted", counts[0])
                .param("updated", counts[1])
                .param("unchanged", unchanged)
                .query(Long.class)
                .single();
        return new IngestOut(batchId, rows.size(), counts[0], counts[1], unchanged, false);
    }

    /**
     * Stores a health data file (see {@link HealthImport}): an Apple export as {@code healthkit} readings, so it
     * merges with phone sync instead of doubling it, and a CSV as {@code file_import}.
     */
    public ImportOut importFile(CurrentUser me, InputStream file, int days) throws IOException {
        Instant until = now().toInstant().plus(FUTURE_SLACK);
        Instant since = until.minus(Duration.ofDays(days));
        HealthImport.Parsed p =
                HealthImport.parse(file, since, until, ZoneId.of(me.user().timezone()));
        if (p.records() == 0 && p.skipped() == 0) {
            throw new ApiError(
                    422,
                    "nothing_to_import",
                    "The file has no readings VitalLink tracks in the last " + days + " days.");
        }
        String source = p.kind() == HealthImport.Kind.apple ? "healthkit" : "file_import";
        int inserted = 0;
        int updated = 0;
        for (int i = 0; i < p.rows().size(); i += MAX_BUCKETS) {
            int[] c = upsert(
                    me.id(), source, p.rows().subList(i, Math.min(p.rows().size(), i + MAX_BUCKETS)));
            inserted += c[0];
            updated += c[1];
        }
        return new ImportOut(
                p.kind().name(),
                p.records(),
                p.rows().size(),
                inserted,
                updated,
                p.rows().size() - inserted - updated,
                p.skipped(),
                p.problems(),
                p.from() == null ? null : p.from().atOffset(ZoneOffset.UTC),
                p.to() == null ? null : p.to().atOffset(ZoneOffset.UTC));
    }

    public ReadingOut addManual(CurrentUser me, ManualIn body) {
        Metric m = metric(body.metric());
        if (!m.manualAllowed()) {
            throw new ApiError(422, "not_manual", m.code() + " comes from devices and can't be typed in.");
        }
        OffsetDateTime now = now();
        OffsetDateTime at = body.at() != null ? body.at() : now;
        if (at.isAfter(now.plus(FUTURE_SLACK))) {
            throw ApiError.invalid("The reading is in the future.");
        }
        if (body.value() < m.minValue() || body.value() > m.maxValue()) {
            throw ApiError.invalid(
                    "%s must be %s–%s %s.".formatted(m.code(), fmt(m.minValue()), fmt(m.maxValue()), m.unit()));
        }
        Instant bucket = Instant.ofEpochSecond(Math.floorDiv(at.toEpochSecond(), 300) * 300);
        upsert(me.id(), "manual", List.of(new Row(m.id(), bucket, body.value(), 1)));
        return new ReadingOut(
                m.code(), body.value(), null, m.unit(), bucket.atOffset(ZoneOffset.UTC), assess(m, body.value()));
    }

    // ---------------------------------------------------------------- series

    private static String aggSql(Agg agg) {
        return switch (agg) {
            case sum -> "sum(value)";
            case avg -> "avg(value)";
            case max -> "max(value)";
            case last -> "(array_agg(value ORDER BY recorded_at DESC))[1]";
        };
    }

    public Series series(UUID userId, String tzName, String code, int days, Interval interval) {
        Metric m = metric(code);
        ZoneId tz = ZoneId.of(tzName);
        OffsetDateTime now = now();
        LocalDate today = now.atZoneSameInstant(tz).toLocalDate();
        LocalDateTime startLocal = today.minusDays(days - 1L).atStartOfDay();
        LocalDateTime endLocal = now.atZoneSameInstant(tz).toLocalDateTime();
        // The only interpolation is a fixed aggregate expression from aggSql.
        String sql = """
                WITH buckets AS (
                  SELECT generate_series(date_trunc(:interval, CAST(:start AS timestamp)),
                                         date_trunc(:interval, CAST(:end AS timestamp)),
                                         CAST(:step AS interval)) AS local_bucket
                ), readings AS (
                  SELECT date_trunc(:interval, recorded_at AT TIME ZONE :tz) AS local_bucket,
                         %s AS value, sum(samples) AS samples
                  FROM vitallink.health_readings
                  WHERE user_id = :user_id AND metric_id = :metric_id
                    AND recorded_at >= :start_utc AND recorded_at < :end_utc
                  GROUP BY 1
                )
                SELECT b.local_bucket, r.value, coalesce(r.samples, 0) AS samples
                FROM buckets b LEFT JOIN readings r USING (local_bucket)
                ORDER BY b.local_bucket
                """.formatted(aggSql(m.aggregation()));
        List<SeriesPoint> points = new ArrayList<>();
        jdbc.sql(sql)
                .param("interval", interval.name())
                .param("start", startLocal)
                .param("end", endLocal)
                .param("step", "1 " + interval.name())
                .param("tz", tzName)
                .param("user_id", userId)
                .param("metric_id", m.id())
                .param("start_utc", startLocal.atZone(tz).toOffsetDateTime())
                .param("end_utc", now.plus(FUTURE_SLACK))
                .query((rs, i) -> {
                    LocalDateTime bucket = rs.getObject("local_bucket", LocalDateTime.class);
                    double value = rs.getDouble("value");
                    boolean missing = rs.wasNull();
                    long samples = rs.getLong("samples");
                    if (missing) {
                        if (m.aggregation() != Agg.sum) {
                            return null; // no reading: averages have no value to show, totals are zero
                        }
                        value = 0.0;
                    }
                    points.add(new SeriesPoint(
                            bucket.atZone(tz).toOffsetDateTime(), Num.round(value, decimals(m)), samples));
                    return null;
                })
                .list();
        return new Series(m.code(), m.unit(), interval, points);
    }

    public BloodPressureSeries bloodPressureSeries(UUID userId, String tz, int days, Interval interval) {
        return new BloodPressureSeries(
                series(userId, tz, SYS.code(), days, interval), series(userId, tz, DIA.code(), days, interval));
    }

    // ---------------------------------------------------------------- assessments

    /** American Heart Association categories: (label, range). */
    public static Map.Entry<String, RangeStatus> bpCategory(double sys, double dia) {
        if (sys >= 140 || dia >= 90) return Map.entry("High (stage 2)", RangeStatus.high);
        if (sys >= 130 || dia >= 80) return Map.entry("High (stage 1)", RangeStatus.high);
        if (sys >= 120) return Map.entry("Elevated", RangeStatus.borderline);
        if (sys < 90 || dia < 60) return Map.entry("Low", RangeStatus.low);
        return Map.entry("Normal", RangeStatus.normal);
    }

    static RangeStatus range(Metric m, Double value) {
        if (value == null) return RangeStatus.no_data;
        if (m.normalLow() != null && value < m.normalLow()) return RangeStatus.low;
        if (m.normalHigh() != null && value > m.normalHigh()) return RangeStatus.high;
        return RangeStatus.normal;
    }

    static String assess(Metric m, double value) {
        if (m.normalLow() == null && m.normalHigh() == null) {
            return "";
        }
        return switch (range(m, value)) {
            case low -> "Low";
            case high -> "High";
            default -> "Normal";
        };
    }

    // ---------------------------------------------------------------- latest readings

    record Latest(double value, OffsetDateTime at) {}

    record LatestBp(double sys, double dia, OffsetDateTime at) {}

    Optional<Latest> latestRow(UUID userId, int metricId) {
        return jdbc.sql("""
                        SELECT value, recorded_at FROM vitallink.health_readings
                        WHERE user_id = :u AND metric_id = :m ORDER BY recorded_at DESC LIMIT 1
                        """)
                .param("u", userId)
                .param("m", metricId)
                .query((rs, i) -> new Latest(rs.getDouble("value"), rs.getObject("recorded_at", OffsetDateTime.class)))
                .optional();
    }

    Optional<LatestBp> latestBp(UUID userId) {
        return jdbc.sql("""
                        SELECT s.value AS sys, d.value AS dia, s.recorded_at
                        FROM vitallink.health_readings s
                        JOIN vitallink.health_readings d
                          ON d.user_id = s.user_id AND d.metric_id = :dia
                         AND d.recorded_at = s.recorded_at AND d.source = s.source
                        WHERE s.user_id = :u AND s.metric_id = :sys
                        ORDER BY s.recorded_at DESC LIMIT 1
                        """)
                .param("u", userId)
                .param("sys", SYS.id())
                .param("dia", DIA.id())
                .query((rs, i) -> new LatestBp(
                        rs.getDouble("sys"), rs.getDouble("dia"), rs.getObject("recorded_at", OffsetDateTime.class)))
                .optional();
    }

    static OffsetDateTime local(OffsetDateTime t, ZoneId tz) {
        return t == null ? null : t.atZoneSameInstant(tz).toOffsetDateTime();
    }

    public List<ReadingOut> latest(UUID userId, String tzName, List<String> codes) {
        ZoneId tz = ZoneId.of(tzName);
        List<ReadingOut> out = new ArrayList<>();
        for (String code : codes) {
            if (code.equals(Catalogue.BLOOD_PRESSURE)) {
                latestBp(userId)
                        .ifPresent(bp -> out.add(new ReadingOut(
                                Catalogue.BLOOD_PRESSURE,
                                Num.roundInt(bp.sys()),
                                (double) Num.roundInt(bp.dia()),
                                "mmHg",
                                local(bp.at(), tz),
                                bpCategory(bp.sys(), bp.dia()).getKey())));
                continue;
            }
            Metric m = metric(code);
            latestRow(userId, m.id())
                    .ifPresent(r -> out.add(new ReadingOut(
                            code, rounded(m, r.value()), null, m.unit(), local(r.at(), tz), assess(m, r.value()))));
        }
        return out;
    }

    // ---------------------------------------------------------------- dashboard

    private Double avg(UUID userId, int metricId, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.sql("""
                        SELECT avg(value) FROM vitallink.health_readings
                        WHERE user_id = :u AND metric_id = :m AND recorded_at >= :start AND recorded_at < :end
                        """)
                .param("u", userId)
                .param("m", metricId)
                .param("start", start)
                .param("end", end)
                .query(Double.class)
                .optional()
                .orElse(null);
    }

    /** Sum of a metric between two instants; null without data. */
    Double sum(UUID userId, int metricId, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.sql("""
                        SELECT sum(value) FROM vitallink.health_readings
                        WHERE user_id = :u AND metric_id = :m AND recorded_at >= :start AND recorded_at < :end
                        """)
                .param("u", userId)
                .param("m", metricId)
                .param("start", start)
                .param("end", end)
                .query(Double.class)
                .optional()
                .orElse(null);
    }

    /**
     * Readiness 0–100 for a local day: the mean of whichever of three parts have data (at least two).
     *
     * <ul>
     *   <li>Sleep: last night's minutes asleep (18:00 the evening before → 14:00) against the sleep goal.
     *   <li>Resting heart rate: each bpm above the 30-day baseline costs 10 points.
     *   <li>HRV: the day's average as a share of the 30-day baseline.
     * </ul>
     */
    public Integer readiness(UUID userId, ZoneId tz, LocalDate day, int sleepGoalMin) {
        OffsetDateTime midnight = day.atStartOfDay(tz).toOffsetDateTime();
        List<Double> parts = new ArrayList<>();
        Double asleep = sum(userId, Catalogue.get("sleep_asleep").id(), midnight.minusHours(6), midnight.plusHours(14));
        if (asleep != null && asleep != 0) {
            parts.add(Math.min(100.0, 100 * asleep / sleepGoalMin));
        }
        for (String code : List.of("resting_heart_rate", "hrv")) {
            int id = Catalogue.get(code).id();
            Double today = avg(userId, id, midnight, midnight.plusDays(1));
            Double base = avg(userId, id, midnight.minusDays(30), midnight);
            if (today == null || base == null || base == 0) {
                continue;
            }
            if (code.equals("resting_heart_rate")) {
                parts.add(Math.max(0.0, Math.min(100.0, 100 - 10 * Math.max(0.0, today - base))));
            } else {
                parts.add(Math.max(0.0, Math.min(100.0, 100 * today / base)));
            }
        }
        return parts.size() >= 2
                ? Num.roundInt(parts.stream().mapToDouble(Double::doubleValue).sum() / parts.size())
                : null;
    }

    /** The owner's dashboard. Shared views pass includeScore=false: readiness also uses sleep data. */
    public Dashboard dashboard(UUID userId, String tzName, int sleepGoalMin, boolean includeScore) {
        ZoneId tz = ZoneId.of(tzName);
        OffsetDateTime now = now();
        OffsetDateTime weekAgo = now.minusDays(7);
        List<MetricSummary> cards = new ArrayList<>();
        for (String code : DASHBOARD_CARDS) {
            if (code.equals(Catalogue.BLOOD_PRESSURE)) {
                Optional<LatestBp> bp = latestBp(userId);
                cards.add(new MetricSummary(
                        Catalogue.BLOOD_PRESSURE,
                        "mmHg",
                        null, // two numbers: clients read them from /me/health/latest
                        bp.map(b -> local(b.at(), tz)).orElse(null),
                        null,
                        bp.map(b -> bpCategory(b.sys(), b.dia()).getValue()).orElse(RangeStatus.no_data)));
                continue;
            }
            Metric m = Catalogue.get(code);
            Optional<Latest> row = latestRow(userId, m.id());
            Double value = row.map(Latest::value).orElse(null);
            cards.add(new MetricSummary(
                    code,
                    m.unit(),
                    rounded(m, value),
                    row.map(r -> local(r.at(), tz)).orElse(null),
                    rounded(m, avg(userId, m.id(), weekAgo, now.plusYears(100))),
                    range(m, value)));
        }
        LocalDate today = now.atZoneSameInstant(tz).toLocalDate();
        Integer score = null;
        Integer before = null;
        if (includeScore) {
            score = readiness(userId, tz, today, sleepGoalMin);
            before = readiness(userId, tz, today.minusDays(7), sleepGoalMin);
        }
        return new Dashboard(local(now, tz), score, score != null && before != null ? score - before : null, cards);
    }

    // ---------------------------------------------------------------- devices

    record DeviceRow(UUID id, String name, DeviceKind kind, Integer[] metricIds, OffsetDateTime lastSyncAt) {}

    static DeviceOut deviceOut(DeviceRow d, OffsetDateTime now, ZoneId tz) {
        List<Metric> metrics = Arrays.stream(d.metricIds())
                .map(Catalogue.BY_ID::get)
                .filter(m -> m != null)
                .sorted(Comparator.comparingInt(Metric::id))
                .toList();
        Set<String> codes = new HashSet<>();
        List<String> labels = new ArrayList<>();
        for (Metric m : metrics) {
            codes.add(m.code());
            // A cuff or oximeter measures pulse, not continuous heart rate.
            String label = m.code().equals("heart_rate") && d.kind() == DeviceKind.home_monitor ? "Pulse" : m.label();
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        String icon;
        if (d.kind() == DeviceKind.wearable) icon = "devices";
        else if (d.kind() == DeviceKind.continuous) icon = "droplet";
        else if (codes.contains("weight")) icon = "scale";
        else if (codes.contains("bp_systolic")) icon = "monitor";
        else icon = "health";
        boolean overdue = Duration.between(d.lastSyncAt(), now).compareTo(OVERDUE_AFTER.get(d.kind())) > 0;
        return new DeviceOut(
                d.id(),
                d.name(),
                d.kind(),
                icon,
                local(d.lastSyncAt(), tz),
                overdue ? "overdue" : "connected",
                labels,
                d.kind() == DeviceKind.wearable && labels.size() > 3 ? labels.subList(0, 3) : null);
    }

    List<DeviceRow> deviceRows(UUID userId) {
        return jdbc.sql("""
                        SELECT id, name, kind, metric_ids::int[] AS metric_ids, last_sync_at
                        FROM vitallink.devices WHERE user_id = :u ORDER BY last_sync_at DESC
                        """)
                .param("u", userId)
                .query((rs, i) -> new DeviceRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        DeviceKind.valueOf(rs.getString("kind")),
                        (Integer[]) rs.getArray("metric_ids").getArray(),
                        rs.getObject("last_sync_at", OffsetDateTime.class)))
                .list();
    }

    public List<DeviceOut> devices(UUID userId, String tzName) {
        OffsetDateTime now = now();
        ZoneId tz = ZoneId.of(tzName);
        return deviceRows(userId).stream().map(d -> deviceOut(d, now, tz)).toList();
    }

    /** What the phone app has uploaded: per platform over 7 days, and the latest batches. */
    public SyncStatus syncStatus(UUID userId) {
        List<SyncPlatform> platforms = jdbc.sql("""
                        SELECT b.platform::text AS platform, max(b.received_at) AS last_at,
                               count(*) FILTER (WHERE b.received_at > now() - interval '7 days') AS uploads,
                               coalesce(sum(b.submitted) FILTER (WHERE b.received_at > now() - interval '7 days'), 0)
                                 AS readings,
                               coalesce(sum(b.inserted + b.updated)
                                 FILTER (WHERE b.received_at > now() - interval '7 days'), 0) AS changed,
                               (SELECT count(*) FROM vitallink.devices d
                                WHERE d.user_id = :u AND d.platform = b.platform) AS devices
                        FROM vitallink.ingest_batches b
                        WHERE b.user_id = :u
                        GROUP BY b.platform ORDER BY last_at DESC
                        """)
                .param("u", userId)
                .query((rs, i) -> new SyncPlatform(
                        rs.getString("platform"),
                        rs.getObject("last_at", OffsetDateTime.class),
                        rs.getInt("uploads"),
                        rs.getLong("readings"),
                        rs.getLong("changed"),
                        rs.getInt("devices")))
                .list();
        List<SyncUpload> recent = jdbc.sql("""
                        SELECT id, received_at, platform::text AS platform, submitted, inserted, updated, unchanged
                        FROM vitallink.ingest_batches WHERE user_id = :u
                        ORDER BY received_at DESC, id DESC LIMIT 10
                        """)
                .param("u", userId)
                .query((rs, i) -> new SyncUpload(
                        rs.getLong("id"),
                        rs.getObject("received_at", OffsetDateTime.class),
                        rs.getString("platform"),
                        rs.getInt("submitted"),
                        rs.getInt("inserted"),
                        rs.getInt("updated"),
                        rs.getInt("unchanged")))
                .list();
        return new SyncStatus(platforms, recent);
    }

    /** Forget the device. Its readings stay: they're the user's data, not the device's. */
    public void forgetDevice(CurrentUser me, UUID deviceId) {
        int n = jdbc.sql("DELETE FROM vitallink.devices WHERE id = :id AND user_id = :u")
                .param("id", deviceId)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such device.");
        }
    }

    // ---------------------------------------------------------------- for other modules

    /** Sum of a metric between two instants (e.g. active energy this week), 0 without data. */
    public double totalBetween(UUID userId, String code, OffsetDateTime start, OffsetDateTime end) {
        Double v = sum(userId, Catalogue.get(code).id(), start, end);
        return v == null ? 0.0 : v;
    }

    /** Daily totals (sum metrics) or averages for each local day in [from, to], zero/absent days omitted. */
    public Map<LocalDate, Double> daily(UUID userId, String tzName, String code, LocalDate from, LocalDate to) {
        Metric m = Catalogue.get(code);
        ZoneId tz = ZoneId.of(tzName);
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT (recorded_at AT TIME ZONE :tz)::date AS day, %s AS value
                        FROM vitallink.health_readings
                        WHERE user_id = :u AND metric_id = :m AND recorded_at >= :start AND recorded_at < :end
                        GROUP BY 1 ORDER BY 1
                        """.formatted(aggSql(m.aggregation())))
                .param("tz", tzName)
                .param("u", userId)
                .param("m", m.id())
                .param("start", from.atStartOfDay(tz).toOffsetDateTime())
                .param("end", to.plusDays(1).atStartOfDay(tz).toOffsetDateTime())
                .query((rs, i) -> out.put(rs.getObject("day", LocalDate.class), rs.getDouble("value")))
                .list();
        return out;
    }

    /**
     * Minutes asleep per night, keyed by the date the night ends on (18:00 the evening before → 18:00),
     * for nights in [from, to].
     */
    public Map<LocalDate, Double> nightlySleep(UUID userId, String tzName, LocalDate from, LocalDate to) {
        ZoneId tz = ZoneId.of(tzName);
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT ((recorded_at AT TIME ZONE :tz) + interval '6 hours')::date AS night, sum(value) AS value
                        FROM vitallink.health_readings
                        WHERE user_id = :u AND metric_id = :m AND recorded_at >= :start AND recorded_at < :end
                        GROUP BY 1 ORDER BY 1
                        """)
                .param("tz", tzName)
                .param("u", userId)
                .param("m", Catalogue.get("sleep_asleep").id())
                .param("start", from.atStartOfDay(tz).minusHours(6).toOffsetDateTime())
                .param("end", to.plusDays(1).atStartOfDay(tz).minusHours(6).toOffsetDateTime())
                .query((rs, i) -> out.put(rs.getObject("night", LocalDate.class), rs.getDouble("value")))
                .list();
        return out;
    }

    /** Average of a metric between two instants, null without data. */
    public Double averageBetween(UUID userId, String code, OffsetDateTime start, OffsetDateTime end) {
        return avg(userId, Catalogue.get(code).id(), start, end);
    }

    /** The most recently synced wearable, used as a workout data source label. */
    public String wearableName(UUID userId) {
        return jdbc.sql("""
                        SELECT name FROM vitallink.devices WHERE user_id = :u AND kind = 'wearable'
                        ORDER BY last_sync_at DESC LIMIT 1
                        """).param("u", userId).query(String.class).optional().orElse(null);
    }

    public Double latestValue(UUID userId, String code) {
        return latestRow(userId, Catalogue.get(code).id()).map(Latest::value).orElse(null);
    }

    /** The latest value and when it was recorded. */
    public Optional<Map.Entry<Double, OffsetDateTime>> latestWithTime(UUID userId, String code) {
        return latestRow(userId, Catalogue.get(code).id()).map(l -> Map.entry(l.value(), l.at()));
    }

    /** Device counts for the privacy centre: (total, syncing, overdue). */
    public int[] deviceCounts(UUID userId) {
        OffsetDateTime now = now();
        List<DeviceRow> rows = deviceRows(userId);
        int overdue = (int) rows.stream()
                .filter(d -> Duration.between(d.lastSyncAt(), now).compareTo(OVERDUE_AFTER.get(d.kind())) > 0)
                .count();
        return new int[] {rows.size(), rows.size() - overdue, overdue};
    }
}
