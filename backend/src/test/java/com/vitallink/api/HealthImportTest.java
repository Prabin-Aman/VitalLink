package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** POST /me/health/import: Apple Health exports and CSVs become the same 5-minute readings as phone sync. */
class HealthImportTest extends ApiTest {

    static final DateTimeFormatter APPLE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z").withZone(ZoneOffset.ofHours(-5));

    /** 10:00 two days ago, on a 5-minute boundary. */
    static final Instant T0 =
            Instant.now().truncatedTo(ChronoUnit.DAYS).minus(2, ChronoUnit.DAYS).plus(15, ChronoUnit.HOURS);

    static String at(Instant t) {
        return APPLE.format(t);
    }

    static String record(String type, String source, String unit, String value, Instant start, Instant end) {
        return "<Record type=\"%s\" sourceName=\"%s\" unit=\"%s\" value=\"%s\" startDate=\"%s\" endDate=\"%s\"/>\n"
                .formatted(type, source, unit, value, at(start), at(end));
    }

    static String export() {
        Instant t5 = T0.plus(5, ChronoUnit.MINUTES);
        Instant t10 = T0.plus(10, ChronoUnit.MINUTES);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE HealthData [
                <!ELEMENT HealthData (ExportDate,Me,(Record|Workout)*)>
                ]>
                <HealthData locale="en_US">
                """
                // The Watch and the iPhone both counted the same walk: per bucket, the larger source wins.
                + record("HKQuantityTypeIdentifierStepCount", "Watch", "count", "100", T0, t10)
                + record("HKQuantityTypeIdentifierStepCount", "iPhone", "count", "80", T0, t5)
                // Averages pool every source.
                + record("HKQuantityTypeIdentifierHeartRate", "Watch", "count/min", "60", T0, T0)
                + record("HKQuantityTypeIdentifierHeartRate", "Strap", "count/min", "80", T0, T0)
                + record("HKQuantityTypeIdentifierBodyMass", "Scale", "lb", "160", T0, T0)
                + record("HKQuantityTypeIdentifierOxygenSaturation", "Watch", "%", "0.97", T0, T0)
                + record(
                        "HKCategoryTypeIdentifierSleepAnalysis",
                        "Watch",
                        "",
                        "HKCategoryValueSleepAnalysisAsleepCore",
                        T0.minus(10, ChronoUnit.HOURS),
                        T0.minus(10, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES))
                + record(
                        "HKCategoryTypeIdentifierSleepAnalysis",
                        "Watch",
                        "",
                        "HKCategoryValueSleepAnalysisInBed",
                        T0.minus(11, ChronoUnit.HOURS),
                        T0.minus(9, ChronoUnit.HOURS))
                // Ignored: a type VitalLink doesn't track; skipped: a unit it can't convert; outside the period.
                + record("HKQuantityTypeIdentifierDietaryWater", "App", "mL", "250", T0, T0)
                + record("HKQuantityTypeIdentifierStepCount", "App", "km", "3", T0, T0)
                + record(
                        "HKQuantityTypeIdentifierStepCount",
                        "Watch",
                        "count",
                        "50",
                        T0.minus(200, ChronoUnit.DAYS),
                        T0.minus(200, ChronoUnit.DAYS))
                + "</HealthData>\n";
    }

    Res upload(String auth, byte[] body, String query) {
        return call(
                MockMvcRequestBuilders.post("/v1/me/health/import" + query)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(body),
                auth,
                null);
    }

    /** metric code @ minutes after T0 → value, with the source, read below the API. */
    Map<String, String> stored(String auth) throws Exception {
        String id = get(auth, "/v1/me").at("/id").asString();
        Map<String, String> out = new HashMap<>();
        try (var c = sql();
                var st = c.prepareStatement("""
                        SELECT m.code, r.recorded_at, r.value, r.source::text
                        FROM vitallink.health_readings r JOIN vitallink.metrics m ON m.id = r.metric_id
                        WHERE r.user_id = ?
                        """)) {
            st.setObject(1, UUID.fromString(id));
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    long mins = (rs.getTimestamp(2).toInstant().getEpochSecond() - T0.getEpochSecond()) / 60;
                    out.put(rs.getString(1) + "@" + mins, rs.getDouble(3) + " " + rs.getString(4));
                }
            }
        }
        return out;
    }

    @Test
    void appleExportBecomesReadingsLikePhoneSync() throws Exception {
        String u = user();
        Res r = upload(u, export().getBytes(StandardCharsets.UTF_8), "");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.at("/format").asString()).isEqualTo("apple");
        assertThat(r.at("/records").asInt()).isEqualTo(9); // tracked types in the period (in-bed and km steps too)
        assertThat(r.at("/skipped").asInt()).isEqualTo(1);
        assertThat(r.at("/problems/0").asString()).contains("unknown unit");

        Map<String, String> s = stored(u);
        assertThat(s.get("steps@0")).isEqualTo("80.0 healthkit"); // iPhone's 80 beats the Watch's 50
        assertThat(s.get("steps@5")).isEqualTo("50.0 healthkit");
        assertThat(s.get("heart_rate@0")).isEqualTo("70.0 healthkit");
        assertThat(s.get("weight@0")).isEqualTo("72.575 healthkit");
        assertThat(s.get("oxygen_saturation@0")).isEqualTo("97.0 healthkit");
        assertThat(s.get("sleep_core@-600")).isEqualTo("5.0 healthkit");
        assertThat(s.get("sleep_asleep@-595")).isEqualTo("5.0 healthkit");
        assertThat(s.keySet()).noneMatch(k -> k.startsWith("sleep_awake")); // in bed isn't sleep

        // The same export again changes nothing.
        Res again = upload(u, export().getBytes(StandardCharsets.UTF_8), "");
        assertThat(again.at("/inserted").asInt()).isZero();
        assertThat(again.at("/unchanged").asInt())
                .isEqualTo(again.at("/readings").asInt());
    }

    @Test
    void theZipFromTheHealthAppWorksToo() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("apple_health_export/export_cda.xml"));
            zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("apple_health_export/export.xml"));
            zip.write(export().getBytes(StandardCharsets.UTF_8));
        }
        Res r = upload(user(), bytes.toByteArray(), "?days=30");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.at("/readings").asInt()).isGreaterThan(5);

        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(empty)) {
            zip.putNextEntry(new ZipEntry("notes.txt"));
            zip.write("hi".getBytes(StandardCharsets.UTF_8));
        }
        Res none = upload(user(), empty.toByteArray(), "");
        assertThat(none.status()).isEqualTo(422);
        assertThat(none.at("/error/message").asString()).contains("export.xml");
    }

    @Test
    void csvImportsWhatItCanAndSaysWhatItSkipped() throws Exception {
        String u = user();
        String day = T0.atZone(CHICAGO).toLocalDate().toString();
        String csv = "﻿start,metric,value\n"
                + T0 + ",weight,72.4\n"
                + day + " 08:40,bp_systolic,121\n"
                + day + " 08:40,bp_diastolic,79\n"
                + day + ",steps,9000\n" // a daily total won't fit a 5-minute bucket
                + "yesterday,weight,72\n"
                + day + " 09:00,blood_type,1\n";
        Res r = upload(u, csv.getBytes(StandardCharsets.UTF_8), "");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.at("/format").asString()).isEqualTo("csv");
        assertThat(r.at("/inserted").asInt()).isEqualTo(3);
        assertThat(r.at("/skipped").asInt()).isEqualTo(3);
        assertThat(r.at("/problems").toString()).contains("unreadable start", "unknown metric", "outside 0–5000");
        assertThat(stored(u).get("weight@0")).isEqualTo("72.4 file_import");
        assertThat(get(u, "/v1/me/health/latest?metrics=blood_pressure")
                        .at("/0/value")
                        .asDouble())
                .isEqualTo(121);
    }

    @Test
    void aSampleWithAnEndYearsAwayIsSkippedNotSpreadOverEveryBucket() {
        String u = user();
        String csv = "start|metric|value|end\n"
                + T0 + "|steps|500|9999-12-31T00:00:00Z\n"
                + T0 + "|sleep|Deep|9999-12-31T00:00:00Z\n"
                + T0 + "|weight|72.4|\n";
        Res r = upload(u, csv.getBytes(StandardCharsets.UTF_8), "");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.at("/inserted").asInt()).isEqualTo(1);
        assertThat(r.at("/skipped").asInt()).isEqualTo(2);
        assertThat(r.at("/problems").toString()).contains("covers more than 2 days");

        String xml = "<HealthData>"
                + "<Record type=\"HKQuantityTypeIdentifierStepCount\" sourceName=\"Watch\" unit=\"count\" value=\"500\""
                + " startDate=\"" + at(T0) + "\" endDate=\"9999-12-31 00:00:00 +0000\"/>"
                + record("HKQuantityTypeIdentifierBodyMass", "Scale", "kg", "72.4", T0, T0)
                + "</HealthData>";
        Res apple = upload(user(), xml.getBytes(StandardCharsets.UTF_8), "");
        assertThat(apple.status()).as(apple.text()).isEqualTo(200);
        assertThat(apple.at("/readings").asInt()).isEqualTo(1);
        assertThat(apple.at("/problems").toString()).contains("covers more than 2 days");
    }

    @Test
    void refusesFilesItCantRead() {
        String u = user();
        Res other = upload(u, "name,age\nAlex,30\n".getBytes(StandardCharsets.UTF_8), "");
        assertThat(other.status()).isEqualTo(422);
        assertThat(other.code()).isEqualTo("unreadable_file");
        Res xml = upload(u, "<?xml version=\"1.0\"?><Other/>".getBytes(StandardCharsets.UTF_8), "");
        assertThat(xml.code()).isEqualTo("unreadable_file");
        Res cut = upload(u, "<HealthData><Record type=".getBytes(StandardCharsets.UTF_8), "");
        assertThat(cut.code()).isEqualTo("unreadable_file");
        Res empty = upload(u, "start,metric,value\n".getBytes(StandardCharsets.UTF_8), "");
        assertThat(empty.code()).isEqualTo("nothing_to_import");
        assertThat(upload(null, "x".getBytes(StandardCharsets.UTF_8), "").status())
                .isEqualTo(401);
    }

    @Test
    void externalEntitiesAreNeverResolved() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE HealthData [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <HealthData><Record type="HKQuantityTypeIdentifierBodyMass" sourceName="&secret;" unit="kg"
                  value="70" startDate="%s" endDate="%s"/></HealthData>
                """.formatted(at(T0), at(T0));
        Res r = upload(user(), xxe.getBytes(StandardCharsets.UTF_8), "");
        // Either refused or imported without the file's contents; never a 500 and never the entity's text.
        assertThat(r.status()).isIn(200, 422);
        assertThat(r.text()).doesNotContain("root:");
    }

    @Test
    void bluetoothIsAnIngestPlatform() {
        Map<String, Object> body = Map.of(
                "idempotency_key", UUID.randomUUID().toString(),
                "platform", "bluetooth",
                "devices",
                        java.util.List.of(Map.of(
                                "external_id",
                                "ble-1",
                                "name",
                                "Polar H10",
                                "kind",
                                "wearable",
                                "metrics",
                                java.util.List.of("heart_rate"))),
                "buckets",
                        java.util.List.of(
                                Map.of("metric", "heart_rate", "start", T0.toString(), "value", 72, "samples", 300)));
        String u = user();
        Res r = post(u, "/v1/me/health/ingest", body);
        assertThat(r.status()).as(r.text()).isEqualTo(202);
        assertThat(get(u, "/v1/me/devices").at("/0/name").asString()).isEqualTo("Polar H10");
    }

    /**
     * A real export is large, and every record's {@code device} attribute is full of escapes like {@code &lt;}. Both
     * broke real files: the 2 MB limit on request bodies, and the XML parser's cap on entity references.
     */
    @Test
    void aLargeExportWithApplesEscapedDeviceTextImports() {
        String u = user();
        StringBuilder xml =
                new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<HealthData locale=\"en_US\">\n");
        Instant start = T0.minus(30, ChronoUnit.DAYS);
        int count = 40_000;
        for (int i = 0; i < count; i++) {
            Instant t = start.plus(i, ChronoUnit.MINUTES);
            xml.append("<Record type=\"HKQuantityTypeIdentifierStepCount\" sourceName=\"iPhone\" ")
                    .append("device=\"&lt;&lt;HKDevice: 0x28&gt;, name:iPhone, model:iPhone&gt;\" unit=\"count\" ")
                    .append("startDate=\"")
                    .append(at(t))
                    .append("\" endDate=\"")
                    .append(at(t.plus(1, ChronoUnit.MINUTES)))
                    .append("\" value=\"10\"/>\n");
        }
        byte[] body = xml.append("</HealthData>\n").toString().getBytes(StandardCharsets.UTF_8);
        assertThat(body.length).as("over the 2 MB limit on ordinary requests").isGreaterThan(2 * 1024 * 1024);
        Res res = upload(u, body, "?days=90");
        assertThat(res.status()).as(res.text()).isEqualTo(200);
        assertThat(res.at("/records").asInt()).isEqualTo(count);
    }
}
