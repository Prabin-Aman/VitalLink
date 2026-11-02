package com.vitallink.api.training;

import com.vitallink.api.account.AccountService;
import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.Naming;
import com.vitallink.api.core.Num;
import com.vitallink.api.health.HealthService;
import com.vitallink.api.training.Exercises.Exercise;
import com.vitallink.api.training.TrainingDtos.ExerciseOut;
import com.vitallink.api.training.TrainingDtos.MuscleGroup;
import com.vitallink.api.training.TrainingDtos.MuscleGroupSets;
import com.vitallink.api.training.TrainingDtos.Muscles;
import com.vitallink.api.training.TrainingDtos.PR;
import com.vitallink.api.training.TrainingDtos.PersonalRecord;
import com.vitallink.api.training.TrainingDtos.PlanIn;
import com.vitallink.api.training.TrainingDtos.PlanOut;
import com.vitallink.api.training.TrainingDtos.PlanSessionIn;
import com.vitallink.api.training.TrainingDtos.PlannedSession;
import com.vitallink.api.training.TrainingDtos.SessionKind;
import com.vitallink.api.training.TrainingDtos.SetIn;
import com.vitallink.api.training.TrainingDtos.SetOut;
import com.vitallink.api.training.TrainingDtos.Training;
import com.vitallink.api.training.TrainingDtos.TrainingSession;
import com.vitallink.api.training.TrainingDtos.TrainingWeek;
import com.vitallink.api.training.TrainingDtos.WorkoutDetail;
import com.vitallink.api.training.TrainingDtos.WorkoutIn;
import com.vitallink.api.training.TrainingDtos.WorkoutOut;
import com.vitallink.api.training.TrainingDtos.WorkoutType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Training rules: log workouts with sets, plans, and the weekly training view (docs/05 Training). */
@Service
public class TrainingService {

    static final int DEFAULT_WEEKLY_TARGET = 5;
    static final int MUSCLE_DAYS = 14;
    static final Set<WorkoutType> PACED = Set.of(WorkoutType.run, WorkoutType.walk);

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final HealthService health;

    public TrainingService(JdbcClient jdbc, AccountService accounts, HealthService health) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.health = health;
    }

    static OffsetDateTime now() {
        return com.vitallink.api.core.Clock.now();
    }

    // ---------------------------------------------------------------- rows

    record SetRow(long id, int exerciseId, int setNo, Integer reps, BigDecimal weightKg, Integer durationS) {
        Exercise exercise() {
            return Exercises.BY_ID.get(exerciseId);
        }
    }

    record WorkoutRow(
            UUID id,
            WorkoutType type,
            SessionKind kind,
            String title,
            OffsetDateTime startedAt,
            int durationS,
            BigDecimal distanceM,
            Integer kcal,
            Integer avgHr,
            Integer rpe,
            Integer[] zoneSeconds,
            String focus,
            String source,
            List<SetRow> sets) {

        Double distance() {
            return distanceM == null ? null : distanceM.doubleValue();
        }
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static final String WORKOUT_COLUMNS =
            "id, type, kind, title, started_at, duration_s, distance_m, kcal, avg_hr, rpe, zone_seconds, focus, source";

    private WorkoutRow workoutRow(ResultSet rs, List<SetRow> sets) throws SQLException {
        java.sql.Array zones = rs.getArray("zone_seconds");
        return new WorkoutRow(
                rs.getObject("id", UUID.class),
                WorkoutType.valueOf(rs.getString("type")),
                SessionKind.valueOf(rs.getString("kind")),
                rs.getString("title"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getInt("duration_s"),
                rs.getBigDecimal("distance_m"),
                intOrNull(rs, "kcal"),
                intOrNull(rs, "avg_hr"),
                intOrNull(rs, "rpe"),
                zones == null ? null : (Integer[]) zones.getArray(),
                rs.getString("focus"),
                rs.getString("source"),
                sets);
    }

    /** Workouts matching {@code where} (with :u bound), oldest first, each with its sets. */
    private List<WorkoutRow> load(String where, Map<String, Object> params) {
        Map<UUID, List<SetRow>> sets = new HashMap<>();
        jdbc.sql(
                        "SELECT s.* FROM vitallink.exercise_sets s WHERE s.workout_id IN (SELECT id FROM vitallink.workouts WHERE "
                                + where + ") ORDER BY s.id")
                .params(params)
                .query((rs, i) -> sets.computeIfAbsent(rs.getObject("workout_id", UUID.class), k -> new ArrayList<>())
                        .add(new SetRow(
                                rs.getLong("id"),
                                rs.getInt("exercise_id"),
                                rs.getInt("set_no"),
                                intOrNull(rs, "reps"),
                                rs.getBigDecimal("weight_kg"),
                                intOrNull(rs, "duration_s"))))
                .list();
        return jdbc.sql("SELECT " + WORKOUT_COLUMNS + " FROM vitallink.workouts WHERE " + where
                        + " ORDER BY started_at, id")
                .params(params)
                .query((rs, i) -> workoutRow(rs, sets.getOrDefault(rs.getObject("id", UUID.class), List.of())))
                .list();
    }

    static Double volume(WorkoutRow w) {
        List<SetRow> weighted = w.sets().stream()
                .filter(s -> s.reps() != null && s.reps() > 0 && s.weightKg() != null)
                .toList();
        if (weighted.isEmpty()) {
            return null;
        }
        return Num.round(
                weighted.stream()
                        .mapToDouble(s -> s.weightKg().doubleValue() * s.reps())
                        .sum(),
                1);
    }

    /** Dominant heart-rate zone 1–5: from zone times, else average HR as a share of max, else by kind. */
    static int zone(WorkoutRow w, int maxHr) {
        if (w.zoneSeconds() != null && Arrays.stream(w.zoneSeconds()).anyMatch(z -> z != 0)) {
            int best = 0;
            for (int i = 1; i < 5; i++) {
                if (w.zoneSeconds()[i] > w.zoneSeconds()[best]) {
                    best = i;
                }
            }
            return best + 1;
        }
        if (w.avgHr() != null) {
            double share = (double) w.avgHr() / maxHr;
            return share < 0.6 ? 1 : share < 0.7 ? 2 : share < 0.8 ? 3 : share < 0.9 ? 4 : 5;
        }
        return w.kind() == SessionKind.mobility ? 1 : 3;
    }

    static OffsetDateTime local(OffsetDateTime t, ZoneId tz) {
        return t.atZoneSameInstant(tz).toOffsetDateTime();
    }

    static WorkoutOut workoutOut(WorkoutRow w, ZoneId tz) {
        return new WorkoutOut(
                w.id(),
                w.type(),
                w.title(),
                local(w.startedAt(), tz),
                w.durationS(),
                w.distance(),
                w.kcal(),
                w.avgHr());
    }

    static WorkoutDetail detail(WorkoutRow w, ZoneId tz) {
        List<SetOut> sets = w.sets().stream()
                .map(s -> new SetOut(
                        s.exercise().name(),
                        s.setNo(),
                        s.reps(),
                        s.weightKg() == null ? null : s.weightKg().doubleValue(),
                        s.durationS()))
                .toList();
        return new WorkoutDetail(
                w.id(),
                w.type(),
                w.title(),
                local(w.startedAt(), tz),
                w.durationS(),
                w.distance(),
                w.kcal(),
                w.avgHr(),
                w.kind(),
                w.rpe(),
                w.zoneSeconds() == null ? null : List.of(w.zoneSeconds()),
                w.focus(),
                w.source(),
                sets);
    }

    // ---------------------------------------------------------------- workouts

    /** Create, or update a synced workout with the same platform id. Returns the workout and whether it's new. */
    public Map.Entry<WorkoutDetail, Boolean> saveWorkout(CurrentUser me, WorkoutIn body) {
        List<String> unknown = new TreeSet<>(body.sets().stream()
                        .map(SetIn::exercise)
                        .filter(n -> !Exercises.BY_NAME.containsKey(n.toLowerCase()))
                        .collect(Collectors.toSet()))
                .stream().toList();
        if (!unknown.isEmpty()) {
            throw new ApiError(422, "unknown_exercise", "Unknown exercise: " + unknown.getFirst() + ".", unknown);
        }
        if (body.planSessionId() != null) {
            boolean owns = jdbc.sql("""
                            SELECT 1 FROM vitallink.plan_sessions s JOIN vitallink.training_plans p ON p.id = s.plan_id
                            WHERE s.id = :id AND p.user_id = :u
                            """)
                            .param("id", body.planSessionId())
                            .param("u", me.id())
                            .query()
                            .listOfRows()
                            .size()
                    == 1;
            if (!owns) {
                throw ApiError.notFound("No such planned session.");
            }
        }

        UUID id = null;
        if (body.externalId() != null) {
            id = jdbc.sql("""
                            SELECT id FROM vitallink.workouts
                            WHERE user_id = :u AND source = CAST(:source AS vitallink.reading_source)
                              AND external_id = :ext
                            """)
                    .param("u", me.id())
                    .param("source", body.source())
                    .param("ext", body.externalId())
                    .query(UUID.class)
                    .optional()
                    .orElse(null);
        }
        boolean created = id == null;
        Map<String, Object> p = new HashMap<>();
        p.put("u", me.id());
        p.put("type", body.type().name());
        p.put("kind", (body.kind() != null ? body.kind() : defaultKind(body.type())).name());
        p.put("title", body.title());
        p.put("started_at", body.startedAt());
        p.put("duration_s", body.durationS());
        p.put("distance_m", body.distanceM());
        p.put("kcal", body.kcal());
        p.put("avg_hr", body.avgHr());
        p.put("rpe", body.rpe());
        p.put("zones", body.zoneSeconds() == null ? null : body.zoneSeconds().toArray(Integer[]::new));
        p.put("focus", body.focus());
        p.put("plan_session_id", body.planSessionId());
        if (created) {
            id = Ids.uuid7();
            p.put("id", id);
            p.put("source", body.source());
            p.put("ext", body.externalId());
            jdbc.sql("""
                            INSERT INTO vitallink.workouts (id, user_id, type, kind, title, started_at, duration_s,
                              distance_m, kcal, avg_hr, rpe, zone_seconds, focus, source, external_id, plan_session_id)
                            VALUES (:id, :u, CAST(:type AS vitallink.workout_type), CAST(:kind AS vitallink.session_kind),
                              :title, :started_at, :duration_s, :distance_m, :kcal, :avg_hr, :rpe,
                              CAST(:zones AS integer[]), :focus, CAST(:source AS vitallink.reading_source), :ext,
                              :plan_session_id)
                            """).params(p).update();
        } else {
            p.put("id", id);
            jdbc.sql("""
                            UPDATE vitallink.workouts SET type = CAST(:type AS vitallink.workout_type),
                              kind = CAST(:kind AS vitallink.session_kind), title = :title, started_at = :started_at,
                              duration_s = :duration_s, distance_m = :distance_m, kcal = :kcal, avg_hr = :avg_hr,
                              rpe = :rpe, zone_seconds = CAST(:zones AS integer[]), focus = :focus,
                              plan_session_id = :plan_session_id
                            WHERE id = :id AND user_id = :u
                            """).params(p).update();
            jdbc.sql("DELETE FROM vitallink.exercise_sets WHERE workout_id = :id")
                    .param("id", id)
                    .update();
        }

        Map<Integer, Integer> numbering = new HashMap<>();
        for (SetIn s : body.sets()) {
            int exerciseId = Exercises.BY_NAME.get(s.exercise().toLowerCase()).id();
            int setNo = numbering.merge(exerciseId, 1, Integer::sum);
            jdbc.sql("""
                            INSERT INTO vitallink.exercise_sets (workout_id, exercise_id, set_no, reps, weight_kg, duration_s)
                            VALUES (:w, :e, :n, :reps, :weight, :duration)
                            """)
                    .param("w", id)
                    .param("e", exerciseId)
                    .param("n", setNo)
                    .param("reps", s.reps())
                    .param("weight", s.weightKg())
                    .param("duration", s.durationS())
                    .update();
        }
        return Map.entry(getWorkout(me, id), created);
    }

    static SessionKind defaultKind(WorkoutType type) {
        return switch (type) {
            case strength -> SessionKind.strength;
            case yoga -> SessionKind.mobility;
            default -> SessionKind.cardio;
        };
    }

    public WorkoutDetail getWorkout(CurrentUser me, UUID workoutId) {
        List<WorkoutRow> rows = load("id = :id AND user_id = :u", Map.of("id", workoutId, "u", me.id()));
        if (rows.isEmpty()) {
            throw ApiError.notFound("No such workout.");
        }
        return detail(rows.getFirst(), me.zone());
    }

    public void deleteWorkout(CurrentUser me, UUID workoutId) {
        int n = jdbc.sql("DELETE FROM vitallink.workouts WHERE id = :id AND user_id = :u")
                .param("id", workoutId)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such workout.");
        }
    }

    /** Workouts started in the last {@code days} local days, newest first. */
    public List<WorkoutOut> listWorkouts(UUID userId, String tzName, int days) {
        ZoneId tz = ZoneId.of(tzName);
        OffsetDateTime since =
                LocalDate.now(tz).minusDays(days - 1L).atStartOfDay(tz).toOffsetDateTime();
        List<WorkoutRow> rows =
                new ArrayList<>(load("user_id = :u AND started_at >= :since", Map.of("u", userId, "since", since)));
        rows.sort(Comparator.comparing(WorkoutRow::startedAt).reversed());
        return rows.stream().map(w -> workoutOut(w, tz)).toList();
    }

    // ---------------------------------------------------------------- the training view

    /** (current, longest). The current streak may end yesterday: today isn't over yet. */
    static int[] streaks(Set<LocalDate> days, LocalDate today) {
        int current = 0;
        LocalDate day = days.contains(today) ? today : today.minusDays(1);
        while (days.contains(day)) {
            current++;
            day = day.minusDays(1);
        }
        int longest = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate d : new TreeSet<>(days)) {
            run = previous != null && ChronoUnit.DAYS.between(previous, d) == 1 ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = d;
        }
        return new int[] {current, longest};
    }

    static String muscleNote(List<MuscleGroupSets> groups) {
        if (groups.isEmpty()) {
            return "Log strength sets to see how your training is spread across muscle groups.";
        }
        int top = groups.getFirst().sets();
        // Under 40% of the most-trained group counts as trailing.
        List<String> trailing = groups.stream()
                .filter(g -> g.sets() < top * 0.4)
                .map(g -> g.group().toLowerCase())
                .toList();
        if (trailing.isEmpty()) {
            return "Your training is well balanced across muscle groups.";
        }
        String names = trailing.size() == 1
                ? capitalize(trailing.getFirst())
                : capitalize(String.join(", ", trailing.subList(0, trailing.size() - 1))) + " and "
                        + trailing.getLast();
        String verb = trailing.size() == 1 ? "is" : "are";
        return names + " " + verb + " trailing. Two accessory sets would even this out.";
    }

    private static String capitalize(String s) {
        return s.isEmpty()
                ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    record Best(double weight, int reps, OffsetDateTime at, String name) {}

    /**
     * Every personal record (Workouts › Personal Records): the heaviest set per exercise over all history,
     * newest first, then the fastest 5 km and 10 km, timed from runs at least that long at their average pace.
     */
    public List<PersonalRecord> records(UUID userId, String tzName) {
        ZoneId tz = ZoneId.of(tzName);
        List<WorkoutRow> history = load("user_id = :u", Map.of("u", userId));
        Map<Integer, Best> best = new LinkedHashMap<>();
        for (WorkoutRow w : history) {
            for (SetRow s : w.sets()) {
                if (s.weightKg() == null || s.reps() == null || s.reps() == 0) {
                    continue;
                }
                double weight = s.weightKg().doubleValue();
                Best previous = best.get(s.exerciseId());
                if (previous == null || weight > previous.weight()) {
                    best.put(
                            s.exerciseId(),
                            new Best(
                                    weight,
                                    s.reps(),
                                    w.startedAt(),
                                    s.exercise().name()));
                }
            }
        }
        List<PersonalRecord> out = new ArrayList<>(best.values().stream()
                .sorted(Comparator.comparing(Best::at).reversed())
                .map(b -> new PersonalRecord(b.name(), local(b.at(), tz), b.weight(), b.reps(), null))
                .toList());
        for (int metres : new int[] {5000, 10000}) {
            history.stream()
                    .filter(w -> w.type() == WorkoutType.run && w.distance() != null && w.distance() >= metres)
                    .min(Comparator.comparingDouble(w -> w.durationS() / w.distance()))
                    .ifPresent(fastest -> out.add(new PersonalRecord(
                            metres / 1000 + " km run",
                            local(fastest.startedAt(), tz),
                            null,
                            null,
                            Num.roundInt(fastest.durationS() * metres / fastest.distance()))));
        }
        return out;
    }

    public Training training(UUID userId, String tzName) {
        ZoneId tz = ZoneId.of(tzName);
        OffsetDateTime now = now();
        LocalDate today = now.atZoneSameInstant(tz).toLocalDate();
        OffsetDateTime weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L)
                .atStartOfDay(tz)
                .toOffsetDateTime();
        OffsetDateTime weekEnd = weekStart.plusDays(7);

        List<WorkoutRow> history =
                load("user_id = :u AND started_at >= :since", Map.of("u", userId, "since", now.minusDays(365)));
        int total = jdbc.sql("SELECT count(*) FROM vitallink.workouts WHERE user_id = :u")
                .param("u", userId)
                .query(Integer.class)
                .single();

        LocalDate dob = accounts.profile(userId).dateOfBirth();
        int maxHr = dob != null ? 220 - (int) (ChronoUnit.DAYS.between(dob, today) / 365) : 190;

        // Personal records: walk history in time order, keeping the best weight per exercise.
        Map<Integer, Best> best = new LinkedHashMap<>();
        Map<UUID, PR> sessionPr = new HashMap<>();
        for (WorkoutRow w : history) {
            Map<Integer, Best> topThis = new LinkedHashMap<>();
            for (SetRow s : w.sets()) {
                if (s.weightKg() == null || s.reps() == null || s.reps() == 0) {
                    continue;
                }
                double weight = s.weightKg().doubleValue();
                Best top = topThis.get(s.exerciseId());
                if (top == null || weight > top.weight()) {
                    topThis.put(
                            s.exerciseId(),
                            new Best(
                                    weight,
                                    s.reps(),
                                    w.startedAt(),
                                    s.exercise().name()));
                }
            }
            for (var e : topThis.entrySet()) {
                Best previous = best.get(e.getKey());
                Best now2 = e.getValue();
                if (previous == null || now2.weight() > previous.weight()) {
                    if (previous != null
                            && (!sessionPr.containsKey(w.id())
                                    || now2.weight() > sessionPr.get(w.id()).weightKg())) {
                        sessionPr.put(w.id(), new PR(now2.name(), now2.weight()));
                    }
                    best.put(e.getKey(), now2);
                }
            }
        }
        List<PersonalRecord> records = new ArrayList<>(best.values().stream()
                .sorted(Comparator.comparing(Best::at).reversed())
                .limit(3)
                .map(b -> new PersonalRecord(b.name(), local(b.at(), tz), b.weight(), b.reps(), null))
                .toList());
        history.stream()
                .filter(w -> w.type() == WorkoutType.run && w.distance() != null && w.distance() >= 5000)
                .min(Comparator.comparingDouble(w -> w.durationS() / w.distance()))
                .ifPresent(fastest -> records.add(new PersonalRecord(
                        "5 km run",
                        local(fastest.startedAt(), tz),
                        null,
                        null,
                        Num.roundInt(fastest.durationS() * 5000 / fastest.distance()))));

        List<WorkoutRow> recent = history.stream()
                .filter(w -> !w.startedAt().isBefore(now.minusDays(MUSCLE_DAYS)))
                .toList();
        List<TrainingSession> sessions = new ArrayList<>();
        for (WorkoutRow w : recent.reversed()) {
            Double distance = w.distance();
            long distinct = w.sets().stream().map(SetRow::exerciseId).distinct().count();
            sessions.add(new TrainingSession(
                    w.id(),
                    w.type(),
                    w.kind(),
                    w.title(),
                    local(w.startedAt(), tz),
                    w.durationS(),
                    distinct == 0 ? null : (int) distinct,
                    w.sets().isEmpty() ? null : w.sets().size(),
                    volume(w),
                    distance,
                    PACED.contains(w.type()) && distance != null && distance != 0
                            ? Num.roundInt(w.durationS() / (distance / 1000))
                            : null,
                    w.kcal(),
                    w.avgHr(),
                    w.rpe(),
                    zone(w, maxHr),
                    w.focus(),
                    sessionPr.get(w.id())));
        }

        Map<MuscleGroup, Integer> perGroup = new LinkedHashMap<>();
        for (WorkoutRow w : recent) {
            for (SetRow s : w.sets()) {
                perGroup.merge(s.exercise().muscle(), 1, Integer::sum);
            }
        }
        List<MuscleGroupSets> groups = perGroup.entrySet().stream()
                .map(e -> new MuscleGroupSets(Naming.label(e.getKey().name()), e.getValue()))
                .sorted(Comparator.comparingInt(MuscleGroupSets::sets).reversed())
                .toList();

        List<WorkoutRow> thisWeek = history.stream()
                .filter(w -> !w.startedAt().isBefore(weekStart) && w.startedAt().isBefore(weekEnd))
                .toList();
        List<Map<SessionKind, Integer>> load = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            load.add(new EnumMap<>(SessionKind.class));
        }
        for (WorkoutRow w : thisWeek) {
            Map<SessionKind, Integer> day =
                    load.get(w.startedAt().atZoneSameInstant(tz).getDayOfWeek().getValue() - 1);
            day.merge(w.kind(), Num.roundInt(w.durationS() / 60.0), Integer::sum);
        }
        Set<LocalDate> days = new HashSet<>();
        history.forEach(w -> days.add(w.startedAt().atZoneSameInstant(tz).toLocalDate()));
        int[] streak = streaks(days, today);

        Map<String, Object> plan = activePlan(userId);
        PlannedSession next = null;
        if (plan != null) {
            next = jdbc.sql("""
                            SELECT * FROM vitallink.plan_sessions WHERE plan_id = :p AND scheduled_for > :now
                            ORDER BY scheduled_for LIMIT 1
                            """)
                    .param("p", plan.get("id"))
                    .param("now", now)
                    .query((rs, i) -> planned(rs, tz))
                    .optional()
                    .orElse(null);
        }
        String source = health.wearableName(userId);
        return new Training(
                total,
                new TrainingWeek(
                        thisWeek.size(),
                        plan != null ? ((Number) plan.get("weekly_target_sessions")).intValue() : DEFAULT_WEEKLY_TARGET,
                        Num.roundInt(thisWeek.stream()
                                        .mapToInt(WorkoutRow::durationS)
                                        .sum()
                                / 60.0),
                        Num.round(
                                thisWeek.stream()
                                        .mapToDouble(w -> volume(w) == null ? 0 : volume(w))
                                        .sum(),
                                1),
                        Num.roundInt(health.totalBetween(userId, "active_energy", weekStart, weekEnd)),
                        streak[0],
                        streak[1],
                        source != null ? source : "Manual entries",
                        load),
                sessions,
                new Muscles(MUSCLE_DAYS, groups, muscleNote(groups)),
                records,
                next);
    }

    private Map<String, Object> activePlan(UUID userId) {
        return jdbc
                .sql("SELECT * FROM vitallink.training_plans WHERE user_id = :u AND status = 'active'")
                .param("u", userId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(null);
    }

    /** Workouts started between two instants (for goals and streaks in other modules). */
    public List<OffsetDateTime> workoutTimes(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT started_at FROM vitallink.workouts
                        WHERE user_id = :u AND started_at >= :from AND started_at < :to ORDER BY started_at
                        """)
                .param("u", userId)
                .param("from", from)
                .param("to", to)
                .query(OffsetDateTime.class)
                .list();
    }

    // ---------------------------------------------------------------- plans and catalogue

    static PlannedSession planned(ResultSet rs, ZoneId tz) throws SQLException {
        return new PlannedSession(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                capitalize(rs.getString("kind")),
                local(rs.getObject("scheduled_for", OffsetDateTime.class), tz),
                rs.getInt("exercises"),
                rs.getInt("minutes"));
    }

    /** The new plan becomes active; the previous active plan is archived. */
    public PlanOut createPlan(CurrentUser me, PlanIn body) {
        // Serialise plan writes for this user: two at once would both archive nothing and then both insert.
        jdbc.sql("SELECT id FROM vitallink.users WHERE id = :u FOR UPDATE")
                .param("u", me.id())
                .query()
                .listOfRows();
        jdbc.sql("UPDATE vitallink.training_plans SET status = 'archived' WHERE user_id = :u AND status = 'active'")
                .param("u", me.id())
                .update();
        UUID planId = Ids.uuid7();
        jdbc.sql("""
                        INSERT INTO vitallink.training_plans (id, user_id, name, status, weekly_target_sessions)
                        VALUES (:id, :u, :name, 'active', :target)
                        """)
                .param("id", planId)
                .param("u", me.id())
                .param("name", body.name())
                .param("target", body.weeklyTargetSessions())
                .update();
        for (PlanSessionIn s : body.sessions()) {
            jdbc.sql("""
                            INSERT INTO vitallink.plan_sessions (id, plan_id, title, kind, scheduled_for, exercises, minutes)
                            VALUES (:id, :p, :title, CAST(:kind AS vitallink.session_kind), :at, :exercises, :minutes)
                            """)
                    .param("id", Ids.uuid7())
                    .param("p", planId)
                    .param("title", s.title())
                    .param("kind", s.kind().name())
                    .param("at", s.scheduledFor())
                    .param("exercises", s.exercises())
                    .param("minutes", s.minutes())
                    .update();
        }
        return plans(me).stream().filter(p -> p.id().equals(planId)).findFirst().orElseThrow();
    }

    public List<PlanOut> plans(CurrentUser me) {
        ZoneId tz = me.zone();
        Map<UUID, List<PlannedSession>> sessions = new HashMap<>();
        jdbc.sql("""
                        SELECT s.* FROM vitallink.plan_sessions s JOIN vitallink.training_plans p ON p.id = s.plan_id
                        WHERE p.user_id = :u ORDER BY s.scheduled_for
                        """)
                .param("u", me.id())
                .query((rs, i) -> sessions.computeIfAbsent(rs.getObject("plan_id", UUID.class), k -> new ArrayList<>())
                        .add(planned(rs, tz)))
                .list();
        return jdbc.sql("SELECT * FROM vitallink.training_plans WHERE user_id = :u ORDER BY created_at DESC, id DESC")
                .param("u", me.id())
                .query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new PlanOut(
                            id,
                            rs.getString("name"),
                            rs.getString("status"),
                            rs.getInt("weekly_target_sessions"),
                            sessions.getOrDefault(id, List.of()));
                })
                .list();
    }

    public List<ExerciseOut> exercises() {
        return Exercises.ALL.stream()
                .map(e -> new ExerciseOut(e.id(), e.name(), e.muscle()))
                .toList();
    }
}
