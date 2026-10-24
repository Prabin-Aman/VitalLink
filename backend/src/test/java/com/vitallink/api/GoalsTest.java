package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Goals: computed status and progress, lifecycle, the overview, today's progress and streaks. */
class GoalsTest extends ApiTest {

    /** One bucket per day at 07:00 local, `values[i]` for i days ago. Only past instants are sent. */
    void daily(String u, String metric, double... values) {
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            ZonedDateTime at = todayAt(7, 0, i);
            if (at.isAfter(ZonedDateTime.now(CHICAGO))) {
                at = todayAt(0, 5, i); // early in the morning: today's bucket at 00:05
            }
            buckets.add(Map.of("metric", metric, "start", iso(at), "value", values[i]));
        }
        Map<String, Object> body =
                Map.of("idempotency_key", UUID.randomUUID().toString(), "platform", "healthkit", "buckets", buckets);
        assertThat(post(u, "/v1/me/health/ingest", body).status()).isEqualTo(202);
    }

    Res goal(String u, Map<String, Object> body) {
        return post(u, "/v1/me/goals", body);
    }

    JsonNode tracked(String u, String id) {
        for (JsonNode g : get(u, "/v1/me/goals/overview").at("/goals")) {
            if (g.get("id").asString().equals(id)) {
                return g;
            }
        }
        throw new AssertionError("goal " + id + " not in the overview");
    }

    @Test
    void dailyTotalGoalAveragesTheLastSevenDays() {
        String u = user();
        daily(u, "steps", 4000, 4900, 4000); // ≤ 5,000 a bucket; one bucket a day
        String id = goal(u, Map.of("title", "Walk 4,000 steps daily", "metric", "steps", "target", 4000))
                .at("/id")
                .asString();
        JsonNode g = tracked(u, id);
        assertThat(g.get("status").asString()).isEqualTo("on_track");
        assertThat(g.get("area").asString()).isEqualTo("Activity");
        assertThat(g.get("progress").asDouble()).isEqualTo(1.0);
        assertThat(g.get("detail").asString()).isEqualTo("4,300 average over 7 days");
        assertThat(g.get("short_detail").asString()).isEqualTo("4,300 average");

        String hard = goal(
                        u,
                        Map.of(
                                "title",
                                "Walk 10,000 steps daily",
                                "short_title",
                                "10,000 steps",
                                "metric",
                                "steps",
                                "target",
                                10000))
                .at("/id")
                .asString();
        JsonNode risky = tracked(u, hard);
        assertThat(risky.get("status").asString()).isEqualTo("at_risk");
        assertThat(risky.get("progress").asDouble()).isEqualTo(0.43);
        assertThat(risky.get("short_title").asString()).isEqualTo("10,000 steps");
    }

    @Test
    void goalWithoutDataIsAtRisk() {
        String u = user();
        String id = goal(u, Map.of("title", "Protein 150 g daily", "metric", "protein_g", "target", 150))
                .at("/id")
                .asString();
        JsonNode g = tracked(u, id);
        assertThat(g.get("status").asString()).isEqualTo("at_risk");
        assertThat(g.get("area").asString()).isEqualTo("Nutrition");
        assertThat(g.get("short_detail").asString()).isEqualTo("No data yet");
    }

    @Test
    void weeklyWorkoutGoal() {
        String u = user();
        String id = goal(u, Map.of("title", "Train 3 times per week", "metric", "workouts", "target", 3))
                .at("/id")
                .asString();
        post(
                u,
                "/v1/me/workouts",
                Map.of(
                        "type",
                        "walk",
                        "title",
                        "Walk",
                        "started_at",
                        Instant.now().minusSeconds(60).toString(),
                        "duration_s",
                        600));
        JsonNode g = tracked(u, id);
        assertThat(g.get("area").asString()).isEqualTo("Training");
        assertThat(g.get("detail").asString()).startsWith("1 of 3 sessions · ");
        assertThat(g.get("progress").asDouble()).isEqualTo(0.33);
    }

    @Test
    void targetValueGoalTracksTheLatestReading() {
        String u = user();
        post(
                u,
                "/v1/me/health/manual",
                Map.of(
                        "metric",
                        "body_fat",
                        "value",
                        18.4,
                        "at",
                        Instant.now().minusSeconds(3600).toString()));
        Res created = goal(u, Map.of("title", "Body fat to 17%", "metric", "body_fat", "target", 17));
        assertThat(created.at("/baseline").asDouble()).isEqualTo(18.4);
        assertThat(created.at("/comparator").asString()).isEqualTo("at_most");
        String id = created.at("/id").asString();
        JsonNode before = tracked(u, id);
        assertThat(before.get("status").asString()).isEqualTo("at_risk");
        assertThat(before.get("short_detail").asString()).isEqualTo("18.4% · no change");

        post(u, "/v1/me/health/manual", Map.of("metric", "body_fat", "value", 17.6));
        JsonNode halfway = tracked(u, id);
        assertThat(halfway.get("status").asString()).isEqualTo("on_track");
        assertThat(halfway.get("progress").asDouble()).isEqualTo(0.57);
        assertThat(halfway.get("short_detail").asString()).isEqualTo("17.6% · down 0.8");

        post(
                u,
                "/v1/me/health/manual",
                Map.of(
                        "metric",
                        "body_fat",
                        "value",
                        16.9,
                        "at",
                        Instant.now().plusSeconds(60).toString()));
        assertThat(tracked(u, id).get("status").asString()).isEqualTo("achieved");
    }

    @Test
    void lifecycleAndAchievements() {
        String u = user();
        String id = goal(
                        u,
                        Map.of(
                                "title",
                                "Resting heart rate under 60",
                                "short_title",
                                "Resting HR under 60",
                                "metric",
                                "resting_heart_rate",
                                "target",
                                60,
                                "comparator",
                                "at_most"))
                .at("/id")
                .asString();
        assertThat(patch(u, "/v1/me/goals/" + id, Map.of("lifecycle", "paused"))
                        .at("/lifecycle")
                        .asString())
                .isEqualTo("paused");
        assertThat(tracked(u, id).get("status").asString()).isEqualTo("paused");
        Res achieved = patch(u, "/v1/me/goals/" + id, Map.of("lifecycle", "achieved"));
        assertThat(achieved.at("/achieved_at").isNull()).isFalse();
        Res overview = get(u, "/v1/me/goals/overview");
        assertThat(overview.at("/achieved_this_month").asInt()).isEqualTo(1);
        assertThat(overview.at("/recent/0/title").asString()).isEqualTo("Resting HR under 60");
        assertThat(tracked(u, id).get("status").asString()).isEqualTo("achieved");
        assertThat(patch(u, "/v1/me/goals/" + id, Map.of("lifecycle", "active"))
                        .at("/achieved_at")
                        .isNull())
                .isTrue();
        assertThat(patch(u, "/v1/me/goals/" + id, Map.of("lifecycle", "done")).status())
                .isEqualTo(422);
        patch(u, "/v1/me/goals/" + id, Map.of("lifecycle", "abandoned"));
        assertThat(get(u, "/v1/me/goals/overview").at("/goals").size()).isZero();
        assertThat(get(u, "/v1/me/goals").at("/0/lifecycle").asString()).isEqualTo("abandoned");
    }

    @Test
    void consistencyAndStepStreak() {
        String u = user();
        patch(u, "/v1/me/targets", Map.of("daily_steps_goal", 1000));
        daily(u, "steps", 1200, 1500, 900, 2000); // met today and yesterday, missed 2 days ago, met 3 days ago
        Res o = get(u, "/v1/me/goals/overview");
        assertThat(o.at("/consistency").size()).isEqualTo(5);
        o.at("/consistency").forEach(w -> assertThat(w.size()).isEqualTo(7));
        int todayIndex = LocalDate.now(CHICAGO).getDayOfWeek().getValue() - 1;
        assertThat(o.at("/consistency/4/" + todayIndex).asBoolean()).isTrue();
        assertThat(o.at("/longest_streak/days").asInt()).isEqualTo(2);
        assertThat(o.at("/longest_streak/label").asString()).isEqualTo("step goal");
    }

    @Test
    void todaysProgressAndStreaks() {
        String u = user();
        patch(u, "/v1/me/targets", Map.of("move_kcal_goal", 100, "daily_steps_goal", 1000));
        daily(u, "active_energy", 150, 120, 50);
        daily(u, "steps", 800);
        goal(u, Map.of("title", "Water 2 l daily", "metric", "water_ml", "target", 2000));
        Res today = get(u, "/v1/me/goals/today");
        List<String> labels = new ArrayList<>();
        today.body().forEach(g -> labels.add(g.get("label").asString()));
        assertThat(labels).containsExactly("Daily steps", "Exercise minutes", "Stand hours", "Water 2 l daily");
        assertThat(today.at("/0/current").asDouble()).isEqualTo(800);
        assertThat(today.at("/0/target").asDouble()).isEqualTo(1000);

        Map<String, Integer> streaks = new LinkedHashMap<>();
        get(u, "/v1/me/streaks")
                .body()
                .forEach(s ->
                        streaks.put(s.get("label").asString(), s.get("days").asInt()));
        assertThat(streaks)
                .containsEntry("Move ring closed", 2)
                .containsKey("Longest this year")
                .containsKey("Stood every hour");
        assertThat(streaks.get("Longest this year")).isGreaterThanOrEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"title\": \"Donate blood\", \"metric\": \"blood_donations\", \"target\": 1}",
                "{\"title\": \"Steps\", \"metric\": \"steps\", \"target\": 0}",
                "{\"title\": \"Steps\", \"metric\": \"steps\", \"target\": 1000, \"baseline\": 10}",
                "{\"title\": \"Steps\", \"metric\": \"steps\", \"target\": 1000, \"starts_on\": \"2026-10-10\", \"ends_on\": \"2026-10-01\"}",
                "{\"title\": \"\", \"metric\": \"steps\", \"target\": 1000}",
                "{\"title\": \"Steps\", \"metric\": \"steps\", \"target\": 1000, \"lifecycle\": \"achieved\"}"
            })
    void goalValidation(String body) {
        assertThat(post(user(), "/v1/me/goals", body).status()).isEqualTo(422);
    }

    @Test
    void goalsArePrivate() {
        String u = user();
        String id = goal(u, Map.of("title", "Steps", "metric", "steps", "target", 1000))
                .at("/id")
                .asString();
        String other = user();
        assertThat(get(other, "/v1/me/goals/" + id).status()).isEqualTo(404);
        assertThat(patch(other, "/v1/me/goals/" + id, Map.of("title", "Mine now"))
                        .status())
                .isEqualTo(404);
        assertThat(delete(other, "/v1/me/goals/" + id).status()).isEqualTo(404);
        assertThat(get(other, "/v1/me/goals/overview").at("/goals").size()).isZero();
        assertThat(delete(u, "/v1/me/goals/" + id).status()).isEqualTo(204);
        assertThat(get(u, "/v1/me/goals/metrics").body().size()).isGreaterThan(10);
    }
}
