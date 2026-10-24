package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** My Health: the overview and the seven tabs, from real readings; empty accounts don't break anything. */
class HealthTabsTest extends ApiTest {

    /** Users here live in {@link ApiTest#AFTERNOON}, so fixed morning times are always in the past. */
    static final ZoneId ZONE = AFTERNOON;

    @Override
    protected String user() {
        return afternoonUser();
    }

    protected static ZonedDateTime todayAt(int hour, int minute, int daysAgo) {
        return todayAt(ZONE, hour, minute, daysAgo);
    }

    final List<Map<String, Object>> buckets = new ArrayList<>();

    void add(String metric, ZonedDateTime at, double value) {
        if (!at.isAfter(ZonedDateTime.now(ZONE))) {
            buckets.add(Map.of("metric", metric, "start", HealthTest.bucket(at), "value", value));
        }
    }

    void send(String u) {
        for (int i = 0; i < buckets.size(); i += 5000) {
            Map<String, Object> body = Map.of(
                    "idempotency_key",
                    UUID.randomUUID().toString(),
                    "platform",
                    "health_connect",
                    "buckets",
                    buckets.subList(i, Math.min(buckets.size(), i + 5000)));
            Res r = post(u, "/v1/me/health/ingest", body);
            assertThat(r.status()).as(r.text()).isEqualTo(202);
        }
        buckets.clear();
    }

    /** Two weeks of plausible data, with last night's sleep and evening phone use. */
    String seeded() {
        String u = user();
        patch(u, "/v1/me/account", Map.of("height_cm", 178, "date_of_birth", "1994-04-17", "units", "metric"));
        for (int d = 0; d < 14; d++) {
            add("resting_heart_rate", todayAt(6, 0, d), 60 - (d == 0 ? 3 : 0));
            add("hrv", todayAt(3, 0, d), 50);
            add("oxygen_saturation", todayAt(3, 0, d), 97);
            add("respiratory_rate", todayAt(3, 0, d), 14);
            add("bp_systolic", todayAt(8, 40, d), 118);
            add("bp_diastolic", todayAt(8, 40, d), 76);
            add("weight", todayAt(7, 30, d), 72 + d * 0.05);
            add("mood", todayAt(21, 0, d), 4);
            add("mindful_minutes", todayAt(7, 15, d), 5);
            for (int h = 9; h < 21; h++) add("screen_time", todayAt(h, 0, d), 5);
            for (int m = 0; m < 60; m += 5)
                add("screen_time", todayAt(22, m, d), 4 - (d % 2) * 3); // heavy and light nights
            ZonedDateTime bed = todayAt(23, 0, d + 1);
            for (int i = 0; i < 90; i++) {
                ZonedDateTime t = bed.plusMinutes(5L * i);
                add("sleep_asleep", t, 5);
                add(i % 10 == 0 ? "sleep_deep" : i % 7 == 0 ? "sleep_rem" : "sleep_core", t, 5);
            }
            add("sleep_awake", bed.plusMinutes(450), 5);
            add("steps", todayAt(8, 0, d), 4000);
            add("active_energy", todayAt(8, 0, d), 200);
            add("distance", todayAt(8, 0, d), 3000);
        }
        add("vo2_max", todayAt(7, 0, 200), 38);
        add("vo2_max", todayAt(7, 0, 2), 41.2);
        add("body_fat", todayAt(7, 30, 1), 18.4);
        add("body_fat", todayAt(7, 30, 60), 19.8);
        add("lean_mass", todayAt(7, 30, 1), 34.2);
        add("lean_mass", todayAt(7, 30, 60), 33.5);
        add("waist", todayAt(7, 30, 1), 81);
        add("waist", todayAt(7, 30, 9), 83);
        add("phone_pickups", todayAt(0, 30, 0), 3);
        send(u);
        return u;
    }

    static Map<String, JsonNode> byMetric(JsonNode readings) {
        Map<String, JsonNode> m = new HashMap<>();
        readings.forEach(r -> m.put(r.get("metric").asString(), r));
        return m;
    }

    @Test
    void overview() {
        String u = seeded();
        Res o = get(u, "/v1/me/health/overview");
        assertThat(o.status()).as(o.text()).isEqualTo(200);
        assertThat(o.at("/rings/move/1").asInt()).isEqualTo(500);
        assertThat(o.at("/sections").size()).isEqualTo(4);
        Map<String, JsonNode> r = byMetric(o.at("/readings"));
        assertThat(r.get("resting_hr").get("value").asDouble()).isEqualTo(57);
        assertThat(r.get("blood_pressure").get("value2").asDouble()).isEqualTo(76);
        assertThat(r.get("bmi").get("assessment").asString()).isEqualTo("Healthy");
        assertThat(r.get("walking_distance").get("unit").asString()).isEqualTo("km");
        assertThat(r.get("hrv").get("period").asString()).isEqualTo("overnight");
        assertThat(r).doesNotContainKeys("walking_speed", "wrist_temp", "headphone_audio"); // nothing measures them
        List<String> insights = new ArrayList<>();
        o.at("/insights").forEach(i -> insights.add(i.get("text").asString()));
        assertThat(insights).anyMatch(t -> t.startsWith("Resting heart rate is 3 BPM lower"));
    }

    @Test
    void everyTabRenders() {
        String u = seeded();
        Res vitals = get(u, "/v1/me/health/tabs/vitals");
        assertThat(vitals.at("/bp30/systolic").size()).isBetween(7, 15);
        assertThat(byMetric(vitals.at("/readings")).get("bmi").get("assessment").asString())
                .isEqualTo("Healthy range");

        Res sleep = get(u, "/v1/me/health/tabs/sleep");
        assertThat(sleep.at("/last/asleep_min").asInt()).isEqualTo(450);
        assertThat(sleep.at("/last/wakeups").asInt()).isEqualTo(1);
        assertThat(sleep.at("/week").size()).isEqualTo(7);
        assertThat(sleep.at("/consistency/bedtime").asString()).isEqualTo("11:00 PM");

        Res heart = get(u, "/v1/me/health/tabs/heart");
        assertThat(heart.at("/resting/value").asInt()).isEqualTo(57);
        assertThat(heart.at("/zones").size()).isEqualTo(5);
        assertThat(heart.at("/alerts/0/value").asString()).isEqualTo("None in 30 days");

        Res resp = get(u, "/v1/me/health/tabs/respiratory");
        assertThat(resp.at("/aerobic/current").asDouble()).isEqualTo(41.2);
        assertThat(resp.at("/aerobic/six_months_ago").asDouble()).isEqualTo(38.0);

        Res body = get(u, "/v1/me/health/tabs/body");
        Map<String, JsonNode> b = byMetric(body.at("/readings"));
        assertThat(b.get("waist").get("assessment").asString()).isEqualTo("down 2.0 cm");
        assertThat(body.at("/muscle_change_kg").asDouble()).isEqualTo(0.7);
        assertThat(body.at("/measurements/0/name").asString()).isEqualTo("Waist");

        Res mental = get(u, "/v1/me/health/tabs/mental");
        assertThat(mental.at("/mood").size()).isEqualTo(7);
        assertThat(mental.at("/mindful/goal").asInt()).isEqualTo(70);

        Res digital = get(u, "/v1/me/health/tabs/digital");
        assertThat(digital.at("/average_min").asInt()).isGreaterThan(0);
        assertThat(digital.at("/deep_sleep/heavy_nights").asInt()).isGreaterThan(0);
        assertThat(digital.at("/limits").size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"vitals", "sleep", "heart", "respiratory", "body", "mental", "digital"})
    void emptyAccountsGetEmptyTabs(String tab) {
        Res res = get(user(), "/v1/me/health/tabs/" + tab);
        assertThat(res.status()).as(res.text()).isEqualTo(200);
    }

    @Test
    void emptyOverviewAndUnknownTab() {
        String u = user();
        assertThat(get(u, "/v1/me/health/overview").status()).isEqualTo(200);
        assertThat(get(u, "/v1/me/health/tabs/sleep").at("/last").isNull()).isTrue();
        assertThat(get(u, "/v1/me/health/tabs/blood_bank").status()).isEqualTo(404);
    }

    @Test
    void sharedTabsNeedTheirScope() {
        String ownerEmail = "o-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String owner = tokenFor(ownerEmail);
        String ownerId = get(owner, "/v1/me").at("/id").asString();
        String viewerEmail = "v-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String viewer = tokenFor(viewerEmail);
        get(viewer, "/v1/me");
        post(
                owner,
                "/v1/me/shares",
                Map.of("email", viewerEmail, "label", "Coach", "kind", "other", "scopes", List.of("sleep")));
        assertThat(get(viewer, "/v1/users/" + ownerId + "/health/tabs/sleep").status())
                .isEqualTo(200);
        assertThat(get(viewer, "/v1/users/" + ownerId + "/health/tabs/heart").status())
                .isEqualTo(403);
        assertThat(get(viewer, "/v1/users/" + ownerId + "/health/tabs/digital").status())
                .isEqualTo(403);
    }
}
