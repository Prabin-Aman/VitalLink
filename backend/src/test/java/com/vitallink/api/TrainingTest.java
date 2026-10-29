package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import com.vitallink.api.training.Exercises;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Training: workouts with sets, synced upserts, the weekly view, personal records, plans. */
class TrainingTest extends ApiTest {

    static String at(int daysAgo, int hour) {
        return iso(todayAt(hour, 0, daysAgo));
    }

    static Map<String, Object> strength(int daysAgo, double squat) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("type", "strength");
        w.put("title", "Lower Body Strength");
        w.put("started_at", at(daysAgo, 7));
        w.put("duration_s", 3000);
        Map<String, Object> set = Map.of("exercise", "Back Squat", "reps", 5, "weight_kg", squat);
        w.put("sets", List.of(set, set, set, Map.of("exercise", "Plank", "duration_s", 60)));
        return w;
    }

    static Map<String, Object> with(Map<String, Object> base, Map<String, Object> change) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.putAll(change);
        return m;
    }

    Res save(String u, Object body) {
        return post(u, "/v1/me/workouts", body);
    }

    @Test
    void exerciseCatalogueMatchesDatabase() throws Exception {
        Set<String> rows = new HashSet<>();
        try (var c = sql();
                var rs = c.createStatement().executeQuery("SELECT * FROM vitallink.exercises")) {
            while (rs.next()) {
                rows.add(rs.getInt("id") + "|" + rs.getString("name") + "|" + rs.getString("primary_muscle"));
            }
        }
        Set<String> code = new HashSet<>();
        Exercises.ALL.forEach(
                e -> code.add(e.id() + "|" + e.name() + "|" + e.muscle().name()));
        assertThat(rows).isEqualTo(code);
    }

    @Test
    void logStrengthWorkout() {
        String u = user();
        Res res = save(u, strength(0, 80));
        assertThat(res.status()).as(res.text()).isEqualTo(201);
        assertThat(res.at("/kind").asString()).isEqualTo("strength");
        List<String> sets = new ArrayList<>();
        res.at("/sets")
                .forEach(s -> sets.add(
                        s.get("exercise").asString() + " " + s.get("set_no").asInt()));
        assertThat(sets).containsExactly("Back Squat 1", "Back Squat 2", "Back Squat 3", "Plank 1");
        assertThat(get(u, "/v1/me/workouts/" + res.at("/id").asString()).body()).isEqualTo(res.body());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"sets\": [{\"exercise\": \"Blood Donation\", \"reps\": 1}]}",
                "{\"sets\": [{\"exercise\": \"Back Squat\"}]}", // neither reps nor time
                "{\"source\": \"healthkit\"}", // synced without a platform id
                "{\"external_id\": \"abc\"}", // manual with a platform id
                "{\"duration_s\": 0}",
                "{\"avg_hr\": 400}",
                "{\"zone_seconds\": [1, 2, 3]}",
                "{\"user_id\": \"0192a3f4-7c1e-7d2a-9b3c-5e6f7a8b9c0d\"}"
            })
    void rejects(String change) {
        @SuppressWarnings("unchecked")
        Map<String, Object> c = json.readValue(change, Map.class);
        assertThat(save(user(), with(strength(0, 80), c)).status()).isEqualTo(422);
    }

    @Test
    void syncedWorkoutIsUpdatedNotDuplicated() {
        String u = user();
        Map<String, Object> run = Map.of(
                "type", "run",
                "title", "Outdoor Run",
                "started_at", at(1, 6),
                "duration_s", 1800,
                "distance_m", 5000,
                "source", "healthkit",
                "external_id", "hk-123");
        Res first = save(u, run);
        Res again = save(u, with(run, Map.of("distance_m", 5100, "kcal", 340)));
        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.at("/id")).isEqualTo(first.at("/id"));
        Res listed = get(u, "/v1/me/workouts?days=7");
        assertThat(listed.body().size()).isEqualTo(1);
        assertThat(listed.at("/0/distance_m").asDouble()).isEqualTo(5100.0);
        assertThat(listed.at("/0/kcal").asInt()).isEqualTo(340);
    }

    @Test
    void personalRecordsCoverAllHistory() {
        String u = user();
        save(u, strength(400, 90)); // older than the training view's year, still a record
        save(u, strength(3, 80));
        save(u, strength(1, 85));
        save(u, Map.of("type", "run", "title", "10K", "started_at", at(2, 6), "duration_s", 3000, "distance_m", 10000));
        save(u, Map.of("type", "run", "title", "Easy", "started_at", at(4, 6), "duration_s", 1800, "distance_m", 5000));
        Res r = get(u, "/v1/me/training/records");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/0/exercise").asString()).isEqualTo("Back Squat");
        assertThat(r.at("/0/weight_kg").asDouble()).isEqualTo(90);
        assertThat(r.at("/0/reps").asInt()).isEqualTo(5);
        // 10 km in 50:00 is a 5:00/km pace, faster than the 6:00/km 5 km run, so it sets both run records.
        assertThat(r.at("/1/exercise").asString()).isEqualTo("5 km run");
        assertThat(r.at("/1/time_s").asInt()).isEqualTo(1500);
        assertThat(r.at("/2/exercise").asString()).isEqualTo("10 km run");
        assertThat(r.at("/2/time_s").asInt()).isEqualTo(3000);
        assertThat(r.body().size()).isEqualTo(3);
        assertThat(get(user(), "/v1/me/training/records").body().size()).isZero();
    }

    @Test
    void trainingView() {
        String u = user();
        save(u, strength(3, 80));
        save(u, strength(1, 85)); // heavier squat → PR
        save(
                u,
                Map.of(
                        "type",
                        "run",
                        "title",
                        "Run",
                        "started_at",
                        at(0, 6),
                        "duration_s",
                        1500,
                        "distance_m",
                        5000,
                        "avg_hr",
                        160));
        Res t = get(u, "/v1/me/training");

        assertThat(t.at("/total_sessions").asInt()).isEqualTo(3);
        Map<String, JsonNode> sessions = new HashMap<>();
        t.at("/sessions")
                .forEach(s -> sessions.put(s.get("started_at").asString().substring(0, 10), s));
        JsonNode squatDay = sessions.get(at(1, 7).substring(0, 10));
        assertThat(squatDay.get("pr").toString()).isEqualTo("{\"exercise\":\"Back Squat\",\"weight_kg\":85.0}");
        assertThat(squatDay.get("volume_kg").asDouble()).isEqualTo(1275.0);
        assertThat(squatDay.get("exercises").asInt()).isEqualTo(2);
        assertThat(squatDay.get("sets").asInt()).isEqualTo(4);
        assertThat(sessions.get(at(3, 7).substring(0, 10)).get("pr").isNull())
                .isTrue(); // the first time isn't a record
        JsonNode run = sessions.get(at(0, 6).substring(0, 10));
        assertThat(run.get("pace_s_per_km").asInt()).isEqualTo(300);
        assertThat(run.get("zone").asInt()).isIn(4, 5);

        assertThat(t.at("/records/0/exercise").asString()).isEqualTo("Back Squat");
        assertThat(t.at("/records/0/weight_kg").asDouble()).isEqualTo(85.0);
        JsonNode last = t.at("/records/" + (t.at("/records").size() - 1));
        assertThat(last.get("exercise").asString()).isEqualTo("5 km run");
        assertThat(last.get("time_s").asInt()).isEqualTo(1500);
        assertThat(t.at("/week/streak_days").asInt()).isEqualTo(2); // today and yesterday; three days ago breaks it
        assertThat(t.at("/week/target_sessions").asInt()).isEqualTo(5);
        assertThat(t.at("/muscles/groups/0/group").asString()).isEqualTo("Legs");
        assertThat(t.at("/muscles/groups/1/group").asString()).isEqualTo("Core");
        assertThat(t.at("/muscles/note").asString())
                .isEqualTo("Core is trailing. Two accessory sets would even this out.");
        assertThat(t.at("/week/load").size()).isEqualTo(7);
        assertThat(t.at("/next").isNull()).isTrue();
    }

    @Test
    void plans() {
        String u = user();
        Map<String, Object> session = Map.of(
                "title", "Push Day", "kind", "strength", "scheduled_for", at(-1, 18), "exercises", 6, "minutes", 50);
        Res first = post(
                u,
                "/v1/me/training/plans",
                Map.of("name", "Push Pull Legs", "weekly_target_sessions", 4, "sessions", List.of(session)));
        assertThat(first.status()).as(first.text()).isEqualTo(201);
        Res t = get(u, "/v1/me/training");
        assertThat(t.at("/week/target_sessions").asInt()).isEqualTo(4);
        assertThat(t.at("/next/title").asString()).isEqualTo("Push Day");
        assertThat(t.at("/next/kind").asString()).isEqualTo("Strength");
        assertThat(t.at("/next/minutes").asInt()).isEqualTo(50);

        post(u, "/v1/me/training/plans", Map.of("name", "Base", "weekly_target_sessions", 3));
        Res plans = get(u, "/v1/me/training/plans");
        assertThat(plans.at("/0/name").asString() + "/" + plans.at("/0/status").asString())
                .isEqualTo("Base/active");
        assertThat(plans.at("/1/name").asString() + "/" + plans.at("/1/status").asString())
                .isEqualTo("Push Pull Legs/archived");
    }

    @Test
    void deleteAndIsolation() {
        String u = user();
        String id = save(u, strength(0, 80)).at("/id").asString();
        String other = token();
        assertThat(get(other, "/v1/me/workouts/" + id).status()).isEqualTo(404);
        assertThat(delete(other, "/v1/me/workouts/" + id).status()).isEqualTo(404);
        assertThat(delete(u, "/v1/me/workouts/" + id).status()).isEqualTo(204);
        assertThat(get(u, "/v1/me/workouts").body().size()).isZero();
    }

    @Test
    void cannotLinkAnotherUsersPlanSession() {
        String u = user();
        String other = user();
        Res plan = post(
                other,
                "/v1/me/training/plans",
                Map.of(
                        "name",
                        "Theirs",
                        "weekly_target_sessions",
                        3,
                        "sessions",
                        List.of(Map.of(
                                "title", "Leg Day", "kind", "strength", "scheduled_for", at(-1, 7), "minutes", 40))));
        String theirs = plan.at("/sessions/0/id").asString();
        assertThat(save(u, with(strength(0, 80), Map.of("plan_session_id", theirs)))
                        .status())
                .isEqualTo(404);
        assertThat(save(other, with(strength(0, 80), Map.of("plan_session_id", theirs)))
                        .status())
                .isEqualTo(201);
    }

    @Test
    void exercisesListed() {
        Res res = get(user(), "/v1/exercises");
        List<String> names = new ArrayList<>();
        res.body().forEach(e -> names.add(e.get("name").asString()));
        assertThat(names).contains("Back Squat").hasSize(Exercises.ALL.size());
    }
}
