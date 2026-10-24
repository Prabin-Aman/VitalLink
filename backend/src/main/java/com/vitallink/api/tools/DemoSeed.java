package com.vitallink.api.tools;

import com.vitallink.api.core.Ids;
import com.vitallink.api.core.Settings;
import com.vitallink.api.core.db.DatabaseConfig.Connection;
import com.vitallink.api.core.security.DevTokens;
import com.vitallink.api.health.Catalogue;
import com.vitallink.api.health.Catalogue.Metric;
import com.vitallink.api.nutrition.Foods;
import com.vitallink.api.training.Exercises;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
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
import java.util.Random;
import java.util.UUID;

/**
 * The demo account: 90 days of realistic data across every module (docs/08 §8.7, "Seed data").
 *
 * <pre>
 * ./mvnw -q exec:java -Dexec.mainClass=com.vitallink.api.tools.DemoSeed                       # demo@example.com
 * ./mvnw -q exec:java -Dexec.mainClass=com.vitallink.api.tools.DemoSeed -Dexec.args="--email a@b.c"
 * ./mvnw -q exec:java -Dexec.mainClass=com.vitallink.api.tools.DevToken -Dexec.args="demo@example.com"
 * </pre>
 *
 * The last week reproduces the Figma sample values (steps 9,240 … 8,412, BP 118/76, weight 72.4 kg),
 * ending today. Re-running replaces the demo account's data. Refused in production. The database must be
 * migrated first (start the API once).
 *
 * <p>Planted pattern for the insights: more evening screen time → less deep sleep that night.
 */
public final class DemoSeed {

    static final ZoneId TZ = ZoneId.of("America/Chicago");
    static final int DAYS = 90;
    static final int[] FIGMA_STEPS = {9240, 6810, 11430, 7520, 12890, 4610, 8412}; // last 7 days, today last
    static final int[][] FIGMA_BP = {{121, 77}, {118, 75}, {125, 80}, {116, 74}, {120, 78}, {117, 76}, {118, 76}};

    private DemoSeed() {}

    record Row(int metricId, Instant start, double value, int samples) {}

    static ZonedDateTime at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(TZ);
    }

    /** 5-minute bucket starts in [start, end). */
    static List<Instant> slots(ZonedDateTime start, ZonedDateTime end) {
        List<Instant> out = new ArrayList<>();
        Instant t = Instant.ofEpochSecond(Math.floorDiv(start.toEpochSecond(), 300) * 300);
        while (t.isBefore(end.toInstant())) {
            out.add(t);
            t = t.plusSeconds(300);
        }
        return out;
    }

    static int hour(Instant t) {
        return t.atZone(TZ).getHour();
    }

    /** Builds readings, one per metric per bucket (later writes win), never in the future. */
    static final class Builder {
        final Instant now;
        final Random rng;
        final Map<String, Row> rows = new LinkedHashMap<>();

        Builder(Instant now, Random rng) {
            this.now = now;
            this.rng = rng;
        }

        double uniform(double a, double b) {
            return a + rng.nextDouble() * (b - a);
        }

        void add(String code, Instant when, double value, int samples) {
            if (when.isAfter(now)) return;
            Metric m = Catalogue.get(code);
            double v = Math.min(Math.max(value, m.minValue()), m.maxValue());
            Instant t = Instant.ofEpochSecond(Math.floorDiv(when.getEpochSecond(), 300) * 300);
            rows.put(m.id() + "@" + t, new Row(m.id(), t, Math.round(v * 100) / 100.0, samples));
        }

        void add(String code, ZonedDateTime when, double value) {
            add(code, when.toInstant(), value, 1);
        }

        void day(LocalDate day, int stepsTotal, int[] bp, double weight) {
            // Evening screen time drives tonight's deep sleep (the planted pattern).
            double lateScreen = uniform(20, 140);

            // Activity 07:00–21:00, weighted towards a morning walk and the evening.
            List<Instant> active = slots(at(day, 7, 0), at(day, 21, 0));
            double[] w = new double[active.size()];
            double totalW = 0;
            for (int i = 0; i < w.length; i++) {
                double h = hour(active.get(i)) - 7.5;
                w[i] = 1 + 3 * Math.exp(-(h * h)) + rng.nextDouble();
                totalW += w[i];
            }
            for (int i = 0; i < w.length; i++) {
                double steps = stepsTotal * w[i] / totalW;
                add("steps", active.get(i), steps, 3);
                add("distance", active.get(i), steps * 0.76, 3);
                add("active_energy", active.get(i), steps * 0.045 + uniform(0, 0.4), 3);
                if (rng.nextDouble() < 0.03) add("flights_climbed", active.get(i), 1 + rng.nextInt(3), 1);
            }
            int[] workoutMinutes = {30, 35, 40, 45};
            for (Instant t : slots(at(day, 7, 10), at(day, 7, 10).plusMinutes(workoutMinutes[rng.nextInt(4)]))) {
                add("exercise_minutes", t, 5, 1);
            }
            for (int h = 8; h < 21; h++) {
                if (rng.nextDouble() < 0.8) add("stand_hours", at(day, h, 5), 1);
            }
            // Heart rate every 5 minutes; lower overnight.
            for (Instant t : slots(at(day, 0, 0), at(day, 0, 0).plusDays(1))) {
                int h = hour(t);
                add("heart_rate", t, (h < 6 || h >= 23 ? 56 : 72) + rng.nextGaussian() * 4, 60);
            }
            add("resting_heart_rate", at(day, 6, 0), 58 + rng.nextGaussian() * 1.5);
            add("hrv", at(day, 3, 0), 52 + rng.nextGaussian() * 5);
            add("respiratory_rate", at(day, 3, 0), 14.5 + rng.nextGaussian() * 0.6);
            if (day.getDayOfWeek().getValue() == 6) add("vo2_max", at(day, 7, 30), 40.5 + rng.nextGaussian() * 0.3);
            // Vitals from home devices.
            add("bp_systolic", at(day, 8, 40), bp[0]);
            add("bp_diastolic", at(day, 8, 40), bp[1]);
            add("weight", at(day, 7, 30), weight);
            if (day.getDayOfWeek().getValue() == 1) {
                add("body_fat", at(day, 7, 30), 18.4 + rng.nextGaussian() * 0.2);
                add("lean_mass", at(day, 7, 30), 34.2 + rng.nextGaussian() * 0.1);
            }
            int[] spo2 = {96, 97, 98, 98, 99};
            for (int h = 0; h < 24; h += 2) add("oxygen_saturation", at(day, h, 40), spo2[rng.nextInt(5)]);
            for (Instant t : slots(at(day, 0, 0), at(day, 0, 0).plusDays(1))) { // CGM
                var local = t.atZone(TZ);
                double h = local.getHour() + local.getMinute() / 60.0;
                double meals = 0;
                for (double m : new double[] {8, 12.5, 19}) meals += 35 * Math.exp(-((h - m) * (h - m)) / 0.8);
                add("glucose", t, 88 + meals + rng.nextGaussian() * 3, 1);
            }
            // Screen time: daytime plus the evening amount above.
            for (Instant t : slots(at(day, 9, 0), at(day, 23, 0))) {
                boolean evening = hour(t) >= 22;
                double per = Math.min(5, (evening ? lateScreen / 12 : 160 / 156.0) * uniform(0.5, 1.5));
                add("screen_time", t, per, 1);
                add("screen_time_social", t, per * 0.4, 1);
                add("screen_time_entertainment", t, per * 0.35, 1);
                add("screen_time_productivity", t, per * 0.25, 1);
                if (rng.nextDouble() < 0.25) {
                    add("phone_pickups", t, 1 + rng.nextInt(3), 1);
                    add("notifications_received", t, 1 + rng.nextInt(6), 1);
                }
            }
            // Tonight's sleep, 23:00 → about 06:50; deep sleep falls as evening screen time rises.
            double deepShare = Math.max(0.08, 0.24 - lateScreen / 1000);
            int[] bedMinute = {0, 5, 10, 15};
            ZonedDateTime start = at(day, 23, bedMinute[rng.nextInt(4)]);
            List<Instant> night = slots(start, start.plusMinutes(440 + rng.nextInt(41)));
            for (int i = 0; i < night.size(); i++) {
                Instant t = night.get(i);
                double cycle = (i % 18) / 18.0;
                if (rng.nextDouble() < 0.04) {
                    add("sleep_awake", t, 5, 1);
                    continue;
                }
                add("sleep_asleep", t, 5, 1);
                String stage = cycle < deepShare * (i < 54 ? 1.6 : 0.4)
                        ? "sleep_deep"
                        : cycle > 0.78 ? "sleep_rem" : "sleep_core";
                add(stage, t, 5, 1);
            }
            if (rng.nextDouble() < 0.6) add("mood", at(day, 21, 0), new int[] {3, 4, 4, 5}[rng.nextInt(4)]);
            if (rng.nextDouble() < 0.5) {
                add("mindful_minutes", at(day, 7, 15), 5);
                add("mindful_minutes", at(day, 7, 20), 5);
            }
        }
    }

    // ---------------------------------------------------------------- workouts (the Figma week's pattern)

    record Lift(String exercise, int sets, Integer reps, Double weight) {}

    record Plan(String title, String type, String kind, int minutes, int hour, List<Lift> lifts) {}

    static final List<Lift> UPPER = List.of(
            new Lift("Bench Press", 4, 8, 70.0),
            new Lift("Barbell Row", 4, 8, 65.0),
            new Lift("Overhead Press", 4, 8, 42.5),
            new Lift("Lat Pulldown", 4, 10, 55.0),
            new Lift("Bicep Curl", 4, 12, 14.0),
            new Lift("Tricep Extension", 4, 12, 20.0));
    static final List<Lift> LOWER = List.of(
            new Lift("Back Squat", 5, 5, null),
            new Lift("Romanian Deadlift", 4, 8, 80.0),
            new Lift("Lunge", 4, 10, 24.0),
            new Lift("Calf Raise", 4, 15, 40.0),
            new Lift("Plank", 3, null, null));
    static final Map<Integer, List<Plan>> WEEK = Map.of(
            1,
                    List.of(
                            new Plan("Lower Body Strength", "strength", "strength", 48, 19, LOWER),
                            new Plan("Mobility & Stretch", "yoga", "mobility", 10, 21, List.of())),
            2,
                    List.of(
                            new Plan("Outdoor Run", "run", "cardio", 28, 6, List.of()),
                            new Plan("Mobility & Stretch", "yoga", "mobility", 12, 21, List.of())),
            3,
                    List.of(
                            new Plan("Upper Body Strength", "strength", "strength", 52, 19, UPPER),
                            new Plan("Mobility & Stretch", "yoga", "mobility", 8, 21, List.of())),
            5, List.of(new Plan("Indoor Cycle", "cycle", "cardio", 35, 18, List.of())),
            6,
                    List.of(
                            new Plan("Outdoor Run", "run", "cardio", 42, 7, List.of()),
                            new Plan("Mobility & Stretch", "yoga", "mobility", 15, 20, List.of())),
            7, List.of(new Plan("Mobility & Stretch", "yoga", "mobility", 20, 9, List.of())));

    // ---------------------------------------------------------------- the seed

    public static void main(String[] args) throws SQLException {
        String email = "demo@example.com";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--email")) email = args[++i].toLowerCase();
        }
        Settings settings = Env.settings();
        if (!settings.isDev()) {
            throw new IllegalStateException("The demo seed runs only against a local or test database.");
        }
        Connection db = Env.database();
        UUID subject = DevTokens.subjectFor(email); // the same account DevToken signs in as

        Instant now = Instant.now();
        Random rng = new Random(42);
        Builder b = new Builder(now, rng);
        LocalDate today = now.atZone(TZ).toLocalDate();
        for (int offset = DAYS - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            boolean lastWeek = offset < 7;
            int steps = lastWeek ? FIGMA_STEPS[6 - offset] : 5500 + rng.nextInt(7001);
            int[] bp = lastWeek ? FIGMA_BP[6 - offset] : new int[] {114 + rng.nextInt(13), 72 + rng.nextInt(9)};
            double weight = offset <= 1 ? 72.4 : 72.4 + offset * 0.012 + rng.nextGaussian() * 0.15;
            b.day(day, steps, bp, weight);
        }
        // Waist and other measurements every two weeks, slowly coming down.
        for (int w = 0; w < 6; w++) {
            LocalDate d = today.minusDays(1 + 14L * w);
            b.add("waist", at(d, 7, 30), 81 + w * 0.4);
            b.add("hip", at(d, 7, 30), 96 + w * 0.2);
            b.add("chest", at(d, 7, 30), 99 - w * 0.2);
            b.add("arm", at(d, 7, 30), 34 - w * 0.2);
            b.add("thigh", at(d, 7, 30), 57 - w * 0.2);
        }
        List<Row> rows = new ArrayList<>(b.rows.values());

        try (var conn = DriverManager.getConnection(db.jdbcUrl(), db.user(), db.password())) {
            conn.setAutoCommit(false);
            UUID user = upsertUser(conn, subject, email);
            for (String table : List.of(
                    "health_readings",
                    "devices",
                    "workouts",
                    "training_plans",
                    "meal_entries",
                    "hydration_logs",
                    "nutrition_targets",
                    "goals",
                    "medications",
                    "appointments",
                    "conditions",
                    "emergency_profiles",
                    "emergency_contacts",
                    "lab_panels",
                    "health_records",
                    "notifications")) {
                exec(conn, "DELETE FROM vitallink." + table + " WHERE user_id = ?", user);
            }
            readings(conn, user, rows);
            devices(conn, user, now);
            int workouts = workouts(conn, user, today, now, rng);
            nutrition(conn, user, today, now);
            care(conn, user, today);
            records(conn, user, today);
            goals(conn, user, today);
            conn.commit();
            System.out.printf(
                    "Seeded %s: %,d readings, %d workouts over %d days, 5 devices, meals, medications, "
                            + "appointments, labs and goals.%n",
                    email, rows.size(), workouts, DAYS);
        }
    }

    static void exec(java.sql.Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                st.setObject(i + 1, params[i]);
            }
            st.executeUpdate();
        }
    }

    static OffsetDateTime odt(ZonedDateTime t) {
        return t.toOffsetDateTime();
    }

    static UUID upsertUser(java.sql.Connection c, UUID subject, String email) throws SQLException {
        UUID id;
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO vitallink.users (id, auth_subject, email, email_confirmed_at, created_at)
                VALUES (?, ?, ?, now(), now() - interval '8 months')
                ON CONFLICT (auth_subject) DO UPDATE SET email = EXCLUDED.email RETURNING id
                """)) {
            st.setObject(1, Ids.uuid7());
            st.setObject(2, subject);
            st.setString(3, email);
            var rs = st.executeQuery();
            rs.next();
            id = rs.getObject(1, UUID.class);
        }
        exec(c, """
                INSERT INTO vitallink.profiles (user_id, first_name, last_name, date_of_birth, sex, height_cm, phone, zip,
                                                city, units)
                VALUES (?, 'Alex', 'Carter', '1994-04-17', 'male', 178, '+12145550148', '75201', 'Dallas, TX', 'metric')
                ON CONFLICT (user_id) DO NOTHING
                """, id);
        return id;
    }

    static void readings(java.sql.Connection c, UUID user, List<Row> rows) throws SQLException {
        for (int i = 0; i < rows.size(); i += 5000) {
            List<Row> chunk = rows.subList(i, Math.min(rows.size(), i + 5000));
            try (PreparedStatement st = c.prepareStatement("""
                    INSERT INTO vitallink.health_readings (user_id, metric_id, recorded_at, source, value, samples)
                    SELECT ?, m, to_timestamp(t), 'healthkit', v, s
                    FROM unnest(?::smallint[], ?::bigint[], ?::float8[], ?::smallint[]) AS u(m, t, v, s)
                    """)) {
                st.setObject(1, user);
                st.setArray(
                        2,
                        c.createArrayOf(
                                "int4", chunk.stream().map(Row::metricId).toArray()));
                st.setArray(
                        3,
                        c.createArrayOf(
                                "int8",
                                chunk.stream()
                                        .map(r -> r.start().getEpochSecond())
                                        .toArray()));
                st.setArray(
                        4,
                        c.createArrayOf("float8", chunk.stream().map(Row::value).toArray()));
                st.setArray(
                        5,
                        c.createArrayOf("int4", chunk.stream().map(Row::samples).toArray()));
                st.executeUpdate();
            }
        }
    }

    static void devices(java.sql.Connection c, UUID user, Instant now) throws SQLException {
        Object[][] devices = {
            {
                "demo-watch",
                "Apple Watch S9",
                "wearable",
                List.of(
                        "heart_rate",
                        "resting_heart_rate",
                        "hrv",
                        "steps",
                        "distance",
                        "active_energy",
                        "exercise_minutes",
                        "stand_hours",
                        "flights_climbed",
                        "sleep_asleep",
                        "sleep_deep",
                        "sleep_core",
                        "sleep_rem",
                        "sleep_awake",
                        "oxygen_saturation",
                        "respiratory_rate",
                        "vo2_max"),
                9 * 60
            },
            {
                "demo-cuff",
                "Omron BP Monitor",
                "home_monitor",
                List.of("bp_systolic", "bp_diastolic", "heart_rate"),
                3 * 60
            },
            {"demo-cgm", "Dexcom G7 CGM", "continuous", List.of("glucose"), 3 * 60},
            {"demo-scale", "Withings Scale", "home_monitor", List.of("weight", "body_fat", "lean_mass"), 25 * 3600},
            {"demo-oximeter", "Pulse Oximeter", "home_monitor", List.of("oxygen_saturation", "heart_rate"), 6 * 86400},
        };
        for (Object[] d : devices) {
            @SuppressWarnings("unchecked")
            List<String> codes = (List<String>) d[3];
            try (PreparedStatement st = c.prepareStatement("""
                    INSERT INTO vitallink.devices (id, user_id, platform, external_id, name, kind, metric_ids, first_seen_at,
                                                   last_sync_at)
                    VALUES (?, ?, 'healthkit', ?, ?, CAST(? AS vitallink.device_kind), ?::smallint[],
                            now() - interval '8 months', ?)
                    """)) {
                st.setObject(1, Ids.uuid7());
                st.setObject(2, user);
                st.setString(3, (String) d[0]);
                st.setString(4, (String) d[1]);
                st.setString(5, (String) d[2]);
                st.setArray(
                        6,
                        c.createArrayOf(
                                "int4",
                                codes.stream()
                                        .map(x -> Catalogue.get(x).id())
                                        .distinct()
                                        .sorted()
                                        .toArray()));
                st.setObject(7, now.minusSeconds((Integer) d[4]).atOffset(ZoneOffset.UTC));
                st.executeUpdate();
            }
        }
    }

    static int workouts(java.sql.Connection c, UUID user, LocalDate today, Instant now, Random rng)
            throws SQLException {
        int count = 0;
        int[] startMinute = {0, 5, 10};
        for (int offset = DAYS - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            int weeksAgo = offset / 7;
            for (Plan p : WEEK.getOrDefault(day.getDayOfWeek().getValue(), List.of())) {
                ZonedDateTime start = at(day, p.hour(), startMinute[rng.nextInt(3)]);
                if (start.toInstant().isAfter(now)) continue;
                boolean run = p.type().equals("run");
                Double distance = null;
                if (run) {
                    distance = p.minutes() * 60 / (330 + rng.nextDouble() * 20) * 1000;
                } else if (p.type().equals("cycle")) {
                    distance = p.minutes() * 430.0;
                }
                Integer kcal = p.kind().equals("mobility")
                        ? null
                        : (int) Math.round(p.minutes() * (p.kind().equals("cardio") ? 8.5 : 5.5));
                Integer hr = null;
                if (run) {
                    hr = 150 + rng.nextInt(11);
                } else if (p.type().equals("cycle")) {
                    hr = 132 + rng.nextInt(11);
                }
                Integer rpe = p.kind().equals("strength") ? 7 + rng.nextInt(2) : null;
                UUID id = Ids.uuid7();
                exec(
                        c,
                        """
                        INSERT INTO vitallink.workouts (id, user_id, type, kind, title, started_at, duration_s, distance_m, kcal,
                                                        avg_hr, rpe, focus, source, external_id)
                        VALUES (?, ?, CAST(? AS vitallink.workout_type), CAST(? AS vitallink.session_kind), ?, ?, ?, ?, ?, ?, ?, ?,
                                'healthkit', ?)
                        """,
                        id,
                        user,
                        p.type(),
                        p.kind(),
                        p.title(),
                        odt(start),
                        p.minutes() * 60,
                        distance == null ? null : Math.round(distance * 10) / 10.0,
                        kcal,
                        hr,
                        rpe,
                        p.kind().equals("mobility") ? "Hips, thoracic, hamstrings" : null,
                        "demo-" + id);
                Map<Integer, Integer> numbering = new HashMap<>();
                for (Lift l : p.lifts()) {
                    Double weight = l.weight();
                    if (l.exercise().equals("Back Squat")) {
                        weight = 92.5 - 0.5 * weeksAgo; // climbs 0.5 kg a week: a record last Monday
                    }
                    int exerciseId =
                            Exercises.BY_NAME.get(l.exercise().toLowerCase()).id();
                    for (int s = 0; s < l.sets(); s++) {
                        exec(
                                c,
                                "INSERT INTO vitallink.exercise_sets (workout_id, exercise_id, set_no, reps, weight_kg, duration_s) "
                                        + "VALUES (?, ?, ?, ?, ?, ?)",
                                id,
                                exerciseId,
                                numbering.merge(exerciseId, 1, Integer::sum),
                                l.reps(),
                                weight,
                                l.reps() == null ? 45 : null);
                    }
                }
                count++;
            }
        }
        UUID plan = Ids.uuid7();
        exec(
                c,
                "INSERT INTO vitallink.training_plans (id, user_id, name, status, weekly_target_sessions) "
                        + "VALUES (?, ?, 'Push Pull Legs', 'active', 5)",
                plan,
                user);
        Object[][] sessions = {{"Push Day", 50, 6}, {"Pull Day", 50, 6}, {"Leg Day", 55, 5}};
        for (int i = 0; i < sessions.length; i++) {
            exec(
                    c,
                    "INSERT INTO vitallink.plan_sessions (id, plan_id, title, kind, scheduled_for, exercises, minutes) "
                            + "VALUES (?, ?, ?, 'strength', ?, ?, ?)",
                    Ids.uuid7(),
                    plan,
                    sessions[i][0],
                    odt(at(today.plusDays(1 + 2L * i), 18, 30)),
                    sessions[i][2],
                    sessions[i][1]);
        }
        return count;
    }

    static void meal(java.sql.Connection c, UUID user, String slot, String name, ZonedDateTime when, Object[][] items)
            throws SQLException {
        if (when.toInstant().isAfter(Instant.now())) return;
        UUID meal = Ids.uuid7();
        exec(
                c,
                "INSERT INTO vitallink.meal_entries (id, user_id, slot, name, eaten_at) VALUES (?, ?, CAST(? AS vitallink.meal_slot), ?, ?)",
                meal,
                user,
                slot,
                name,
                odt(when));
        Map<String, Foods.Food> byName = new HashMap<>();
        Foods.ALL.forEach(f -> byName.put(f.name(), f));
        for (int i = 0; i < items.length; i++) {
            Foods.Food f = byName.get((String) items[i][0]);
            double share = ((Number) items[i][1]).doubleValue() / 100;
            exec(
                    c,
                    """
                    INSERT INTO vitallink.meal_items (id, meal_id, food_id, position, label, quantity_g, kcal, protein_g,
                                                      carbs_g, fat_g, fibre_g)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.uuid7(),
                    meal,
                    f.id(),
                    i,
                    f.name(),
                    items[i][1],
                    r1(f.kcal() * share),
                    r1(f.protein() * share),
                    r1(f.carbs() * share),
                    r1(f.fat() * share),
                    r1(f.fibre() * share));
        }
    }

    static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static void nutrition(java.sql.Connection c, UUID user, LocalDate today, Instant now) throws SQLException {
        exec(
                c,
                "INSERT INTO vitallink.nutrition_targets (user_id, kcal, protein_g, weekly_target_kg) VALUES (?, 2400, 150, -0.25)",
                user);
        for (int d = 6; d >= 0; d--) {
            LocalDate day = today.minusDays(d);
            meal(c, user, "breakfast", "Breakfast", at(day, 7, 40), new Object[][] {
                {"Greek yoghurt", 200}, {"Rolled oats", 50}, {"Blueberries", 80}, {"Honey", 15}
            });
            meal(c, user, "lunch", "Lunch", at(day, 12, 45), new Object[][] {
                {"Chicken breast, cooked", 150}, {"Brown rice, cooked", 180}, {"Mixed greens", 60}, {"Avocado", 50}
            });
            meal(c, user, "snack", "Snack", at(day, 16, 0), new Object[][] {{"Whey protein powder", 30}, {"Banana", 118}
            });
            meal(c, user, "dinner", "Dinner", at(day, 19, 30), new Object[][] {
                {"Salmon, cooked", 160}, {"Sweet potato, baked", 200}, {"Broccoli, cooked", 120}, {"Olive oil", 10}
            });
            for (int h : new int[] {8, 11, 14, 17, 20}) {
                ZonedDateTime t = at(day, h, 0);
                if (!t.toInstant().isAfter(now)) {
                    exec(
                            c,
                            "INSERT INTO vitallink.hydration_logs (id, user_id, logged_at, ml) VALUES (?, ?, ?, 400)",
                            Ids.uuid7(),
                            user,
                            odt(t));
                }
            }
        }
    }

    static void care(java.sql.Connection c, UUID user, LocalDate today) throws SQLException {
        Object[][] meds = {
            {"Metformin", "tablet", "500 mg", "Dr. Laura Bennett", 3, null, new String[] {"08:00", "20:00"}},
            {"Atorvastatin", "tablet", "10 mg", "Dr. Laura Bennett", 2, null, new String[] {"14:00"}},
            {"Vitamin D3", "capsule", "2000 IU", "Dr. Marcus Hale", 5, null, new String[] {"20:00"}},
            {"Lisinopril", "tablet", "10 mg", "Dr. Laura Bennett", 1, null, new String[] {"21:00"}},
            {"Ferrous Sulfate", "tablet", "325 mg", "Dr. Marcus Hale", 0, today.plusDays(3), new String[] {"08:00"}},
        };
        Instant now = Instant.now();
        for (Object[] m : meds) {
            UUID id = Ids.uuid7();
            exec(c, """
                    INSERT INTO vitallink.medications (id, user_id, name, form, strength, prescriber, refills_left, refill_due_on,
                                                       started_on)
                    VALUES (?, ?, ?, CAST(? AS vitallink.dose_form), ?, ?, ?, ?, ?)
                    """, id, user, m[0], m[1], m[2], m[3], m[4], m[5], today.minusDays(60));
            for (String t : (String[]) m[6]) {
                exec(
                        c,
                        "INSERT INTO vitallink.medication_schedules (id, medication_id, local_time, days_mask) VALUES (?, ?, ?, 127)",
                        Ids.uuid7(),
                        id,
                        LocalTime.parse(t));
                // Doses over the last two weeks: all taken, except last night's Lisinopril (missed).
                for (int d = 14; d >= 0; d--) {
                    ZonedDateTime dose = at(
                            today.minusDays(d),
                            LocalTime.parse(t).getHour(),
                            LocalTime.parse(t).getMinute());
                    boolean missed = m[0].equals("Lisinopril") && d == 1;
                    if (!missed && dose.toInstant().isBefore(now.minusSeconds(600))) {
                        exec(
                                c,
                                "INSERT INTO vitallink.dose_logs (id, medication_id, scheduled_for, status, logged_at) "
                                        + "VALUES (?, ?, ?, 'taken', ?)",
                                Ids.uuid7(),
                                id,
                                odt(dose),
                                odt(dose.plusMinutes(4)));
                    }
                }
            }
        }
        Object[][] appts = {
            {
                "Dr. Laura Bennett",
                "Cardiology",
                at(today.plusDays(2), 10, 30),
                "in_person",
                "Trinity Valley Medical Center, Dallas",
                "confirmed"
            },
            {
                "Dr. Marcus Hale",
                "Internal Medicine",
                at(today.plusDays(5), 15, 0),
                "telehealth",
                "Telehealth video visit",
                "confirmed"
            },
            {
                "Dr. Priya Raman",
                "Endocrinology",
                at(today.plusDays(15), 9, 15),
                "in_person",
                "Oak Cliff Community Hospital",
                "scheduled"
            },
            {
                "Dr. Marcus Hale",
                "Internal Medicine",
                at(today.minusDays(4), 11, 0),
                "in_person",
                "Oak Cliff Community Hospital",
                "confirmed"
            },
        };
        for (Object[] a : appts) {
            exec(c, """
                    INSERT INTO vitallink.appointments (id, user_id, clinician_name, specialty, starts_at, mode, location, state)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS vitallink.visit_mode), ?, CAST(? AS vitallink.appointment_state))
                    """, Ids.uuid7(), user, a[0], a[1], odt((ZonedDateTime) a[2]), a[3], a[4], a[5]);
        }
        Object[][] conditions = {
            {"Type 2 diabetes", "chronic", null}, {"Hypertension", "chronic", null},
            {"Penicillin", "allergy", "severe"}, {"Shellfish", "allergy", null}
        };
        for (Object[] x : conditions) {
            exec(
                    c,
                    "INSERT INTO vitallink.conditions (id, user_id, label, kind, note) VALUES (?, ?, ?, CAST(? AS vitallink.condition_kind), ?)",
                    Ids.uuid7(),
                    user,
                    x[0],
                    x[1],
                    x[2]);
        }
        exec(c, """
                INSERT INTO vitallink.emergency_profiles (user_id, organ_donor, organ_donor_note, preferred_hospital, notes,
                                                          show_on_lock_screen)
                VALUES (?, 'yes', 'registered in Texas', 'Trinity Valley Medical Center, Dallas',
                        'Wears a continuous glucose monitor on left arm.', true)
                """, user);
        Object[][] contacts = {
            {"Susan Carter", "Mother", "+12145550193", true},
            {"Ray Carter", "Father", "+12145550177", false},
            {"Dr. Laura Bennett", "Cardiologist", "+14695550110", false}
        };
        for (Object[] x : contacts) {
            exec(
                    c,
                    "INSERT INTO vitallink.emergency_contacts (id, user_id, name, relation, phone, is_primary) VALUES (?, ?, ?, ?, ?, ?)",
                    Ids.uuid7(),
                    user,
                    x[0],
                    x[1],
                    x[2],
                    x[3]);
        }
    }

    static UUID record(
            java.sql.Connection c, UUID user, String type, String title, String source, String note, LocalDate date)
            throws SQLException {
        UUID id = Ids.uuid7();
        exec(
                c,
                "INSERT INTO vitallink.health_records (id, user_id, type, title, source, note, record_date) "
                        + "VALUES (?, ?, CAST(? AS vitallink.record_type), ?, ?, ?, ?)",
                id,
                user,
                type,
                title,
                source,
                note,
                date);
        return id;
    }

    static void records(java.sql.Connection c, UUID user, LocalDate today) throws SQLException {
        record(
                c,
                user,
                "prescription",
                "Metformin 500 mg",
                "Dr. Laura Bennett",
                "active prescription",
                today.minusDays(11));
        record(c, user, "imaging", "Chest X-ray", "Oak Cliff Imaging", null, today.minusDays(36));
        record(c, user, "visit", "Annual physical summary", "Dr. Marcus Hale", "visit note", today.minusDays(50));
        record(c, user, "vaccination", "Influenza vaccination", "City Pharmacy", "record card", today.minusDays(376));
        record(c, user, "allergy", "Penicillin allergy", "Dr. Marcus Hale", null, today.minusDays(400));

        Object[][] earlier = {
            {"Hemoglobin", 14.0, "g/dL", 13.0, 17.0, 1, null}, {"White Blood Cells", 7.1, "10³/µL", 4.5, 11.0, 1, null},
            {"Platelets", 238.0, "10³/µL", 150.0, 400.0, 0, null}, {"Hematocrit", 41.6, "%", 38.0, 50.0, 1, null},
            {"Red Blood Cells", 4.9, "10⁶/µL", 4.5, 5.9, 1, null}, {"MCV", 85.0, "fL", 80.0, 100.0, 0, null},
            {"Ferritin", 34.0, "ng/mL", 30.0, 400.0, 0, null}, {"Neutrophils", 71.0, "%", 40.0, 75.0, 0, null}
        };
        Object[][] latest = {
            {"Hemoglobin", 14.2, "g/dL", 13.0, 17.0, 1, null},
            {"White Blood Cells", 6.8, "10³/µL", 4.5, 11.0, 1, null},
            {"Platelets", 243.0, "10³/µL", 150.0, 400.0, 0, null},
            {"Hematocrit", 42.1, "%", 38.0, 50.0, 1, null},
            {"Red Blood Cells", 4.9, "10⁶/µL", 4.5, 5.9, 1, null},
            {"MCV", 86.0, "fL", 80.0, 100.0, 0, null},
            {"Ferritin", 28.0, "ng/mL", 30.0, 400.0, 0, "borderline"},
            {"Neutrophils", 78.0, "%", 40.0, 75.0, 0, "abnormal"}
        };
        lab(c, user, today.minusDays(100), earlier, null);
        lab(c, user, today.minusDays(2), latest, "Ferritin low — recheck in 8 weeks. Started ferrous sulfate.");
        Object[][] lipids = {
            {"Total cholesterol", 182.0, "mg/dL", 125.0, 200.0, 0, null},
            {"LDL", 104.0, "mg/dL", 0.0, 100.0, 0, "borderline"},
            {"HDL", 52.0, "mg/dL", 40.0, 90.0, 0, null},
            {"Triglycerides", 128.0, "mg/dL", 0.0, 150.0, 0, null}
        };
        UUID panel = lab(c, user, today.minusDays(2), lipids, null);
        exec(c, "UPDATE vitallink.lab_panels SET name = 'Lipid Profile', short_name = 'Lipids' WHERE id = ?", panel);
        exec(
                c,
                "UPDATE vitallink.health_records SET title = 'Lipid Profile' WHERE id = (SELECT record_id FROM vitallink.lab_panels WHERE id = ?)",
                panel);
    }

    static UUID lab(java.sql.Connection c, UUID user, LocalDate collected, Object[][] results, String note)
            throws SQLException {
        UUID record = record(c, user, "lab", "Complete Blood Count", "Trinity Valley Laboratory", null, collected);
        UUID panel = Ids.uuid7();
        exec(
                c,
                """
                INSERT INTO vitallink.lab_panels (id, user_id, record_id, name, short_name, facility, laboratory, ordered_by,
                                                  collected_at, reported_at, note, created_at)
                VALUES (?, ?, ?, 'Complete Blood Count', 'CBC', 'Trinity Valley Medical Center', 'Trinity Valley Laboratory',
                        'Dr. Laura Bennett', ?, ?, ?, ?)
                """,
                panel,
                user,
                record,
                odt(at(collected, 8, 30)),
                odt(at(collected.plusDays(1), 7, 0)),
                note,
                odt(at(collected.plusDays(1), 9, 0)));
        for (int i = 0; i < results.length; i++) {
            Object[] r = results[i];
            exec(
                    c,
                    """
                    INSERT INTO vitallink.lab_results (id, panel_id, analyte, value, unit, ref_low, ref_high, decimals,
                                                       reported_interpretation, on_report, position)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vitallink.interpretation), ?, ?)
                    """,
                    Ids.uuid7(),
                    panel,
                    r[0],
                    r[1],
                    r[2],
                    r[3],
                    r[4],
                    r[5],
                    r[6],
                    !List.of("Hematocrit", "Red Blood Cells", "MCV").contains((String) r[0]),
                    i);
        }
        return panel;
    }

    static void goals(java.sql.Connection c, UUID user, LocalDate today) throws SQLException {
        Object[][] goals = {
            {
                "Train 5 times per week",
                "Train 5× per week",
                "training",
                "weekly_count",
                "workouts",
                "at_least",
                5.0,
                null,
                null
            },
            {
                "Walk 10,000 steps daily",
                "10,000 steps daily",
                "activity",
                "daily_total",
                "steps",
                "at_least",
                10000.0,
                null,
                null
            },
            {"Sleep 8 hours", "Sleep 8 hours", "recovery", "daily_total", "sleep", "at_least", 480.0, null, null},
            {
                "Protein 150 g daily",
                "Protein 150 g",
                "nutrition",
                "daily_total",
                "protein_g",
                "at_least",
                150.0,
                null,
                null
            },
            {"Body fat to 17%", "Body fat to 17%", "body", "target_value", "body_fat", "at_most", 17.0, 19.0, null},
            {
                "Resting heart rate under 60",
                "Resting HR under 60",
                "heart",
                "target_value",
                "resting_heart_rate",
                "at_most",
                60.0,
                64.0,
                today.minusDays(24)
            },
            {
                "Run 5 km without stopping",
                "Run 5 km",
                "training",
                "weekly_count",
                "workouts",
                "at_least",
                1.0,
                null,
                today.minusDays(45)
            },
        };
        for (Object[] g : goals) {
            LocalDate achieved = (LocalDate) g[8];
            exec(
                    c,
                    """
                    INSERT INTO vitallink.goals (id, user_id, title, short_title, area, kind, metric, comparator, target, baseline,
                                                 starts_on, lifecycle, achieved_at)
                    VALUES (?, ?, ?, ?, CAST(? AS vitallink.goal_area), CAST(? AS vitallink.goal_kind), ?,
                            CAST(? AS vitallink.comparator), ?, ?, ?, CAST(? AS vitallink.goal_lifecycle), ?)
                    """,
                    Ids.uuid7(),
                    user,
                    g[0],
                    g[1],
                    g[2],
                    g[3],
                    g[4],
                    g[5],
                    g[6],
                    g[7],
                    today.minusDays(60),
                    achieved == null ? "active" : "achieved",
                    achieved == null ? null : odt(at(achieved, 9, 0)));
        }
    }
}
