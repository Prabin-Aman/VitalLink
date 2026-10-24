package com.vitallink.api.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** The nightly job: guarded by its secret, adds notifications once, safe to re-run. */
class NightlyJobTest extends ApiTest {

    @Autowired
    NightlyJob job;

    int call(String secret) throws Exception {
        var req = MockMvcRequestBuilders.post("/internal/jobs/nightly");
        if (secret != null) {
            req.header("X-Job-Secret", secret);
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    @Test
    void needsTheSecret() throws Exception {
        assertThat(call(null)).isEqualTo(401);
        assertThat(call("guess")).isEqualTo(401);
    }

    @Test
    void notifiesOnceAboutMissedDosesAppointmentsAndOverdueDevices() throws Exception {
        String u = user();
        UUID id = UUID.fromString(get(u, "/v1/me").at("/id").asString());
        post(
                u,
                "/v1/me/medications",
                Map.of(
                        "name",
                        "Lisinopril",
                        "form",
                        "tablet",
                        "strength",
                        "10 mg",
                        "times",
                        List.of("09:00"),
                        "started_on",
                        ZonedDateTime.now(CHICAGO).minusDays(5).toLocalDate().toString()));
        ZonedDateTime tomorrow =
                ZonedDateTime.now(CHICAGO).plusDays(1).withHour(10).withMinute(30);
        post(
                u,
                "/v1/me/appointments",
                Map.of(
                        "clinician",
                        "Dr. Laura Bennett",
                        "specialty",
                        "Cardiology",
                        "starts_at",
                        tomorrow.toOffsetDateTime().toString(),
                        "mode",
                        "in_person",
                        "location",
                        "Trinity Valley Medical Center",
                        "state",
                        "confirmed"));
        Map<String, Object> device = Map.of(
                "external_id",
                "oxi-1",
                "name",
                "Pulse Oximeter",
                "kind",
                "home_monitor",
                "metrics",
                List.of("oxygen_saturation"));
        post(
                u,
                "/v1/me/health/ingest",
                Map.of(
                        "idempotency_key",
                        UUID.randomUUID().toString(),
                        "platform",
                        "healthkit",
                        "devices",
                        List.of(device),
                        "buckets",
                        List.of(Map.of("metric", "oxygen_saturation", "start", HealthTestBucket.now(), "value", 97))));
        try (var c = sql();
                var st = c.prepareStatement(
                        "UPDATE vitallink.devices SET last_sync_at = now() - interval '6 days' WHERE user_id = ?")) {
            st.setObject(1, id);
            st.executeUpdate();
        }

        int first = job.perUser(new NightlyJob.User(id, "America/Chicago"));
        int again = job.perUser(new NightlyJob.User(id, "America/Chicago"));
        assertThat(first).isEqualTo(3);
        assertThat(again).isZero(); // dedupe keys make re-runs harmless

        List<String> titles = new ArrayList<>();
        get(u, "/v1/me/notifications")
                .body()
                .forEach(n -> titles.add(n.get("title").asString()));
        assertThat(titles)
                .containsExactlyInAnyOrder(
                        "Lisinopril dose missed", "Appointment tomorrow", "Pulse Oximeter hasn't synced");
    }

    @Test
    void fullRunReturnsASummary() throws Exception {
        user();
        var res = mvc.perform(
                        MockMvcRequestBuilders.post("/internal/jobs/nightly").header("X-Job-Secret", "test-job-secret"))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        var summary = json.readTree(res.getContentAsString());
        assertThat(summary.get("users").asInt()).isPositive();
        assertThat(summary.get("users_failed").asInt()).isZero();
    }

    /** A 5-minute bucket start just in the past, ISO with offset. */
    static final class HealthTestBucket {
        static String now() {
            long ts = Math.floorDiv(OffsetDateTime.now().toEpochSecond(), 300) * 300 - 300;
            return java.time.Instant.ofEpochSecond(ts)
                    .atOffset(java.time.ZoneOffset.UTC)
                    .toString();
        }
    }
}
