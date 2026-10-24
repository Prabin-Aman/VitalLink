package com.vitallink.api.goals;

import com.vitallink.api.account.AccountDtos.Targets;
import com.vitallink.api.account.AccountDtos.Units;
import com.vitallink.api.account.AccountService;
import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Clock;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.Streaks;
import com.vitallink.api.core.web.Patch;
import com.vitallink.api.goals.GoalDtos.Achievement;
import com.vitallink.api.goals.GoalDtos.Area;
import com.vitallink.api.goals.GoalDtos.Comparator;
import com.vitallink.api.goals.GoalDtos.GoalIn;
import com.vitallink.api.goals.GoalDtos.GoalOut;
import com.vitallink.api.goals.GoalDtos.GoalProgress;
import com.vitallink.api.goals.GoalDtos.GoalsOverview;
import com.vitallink.api.goals.GoalDtos.Kind;
import com.vitallink.api.goals.GoalDtos.Lifecycle;
import com.vitallink.api.goals.GoalDtos.Streak;
import com.vitallink.api.goals.GoalDtos.StreakOut;
import com.vitallink.api.goals.GoalDtos.TrackedGoal;
import com.vitallink.api.goals.GoalMetrics.GoalMetric;
import com.vitallink.api.goals.GoalMetrics.Source;
import com.vitallink.api.health.HealthService;
import com.vitallink.api.nutrition.NutritionService;
import com.vitallink.api.training.TrainingService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Goals: CRUD and lifecycle, computed progress and status, the goals overview, and streaks.
 *
 * <p>Status rules (computed on every read, so they never go stale):
 *
 * <ul>
 *   <li>daily_total: the average of the last 7 days that have data, against the target. On track at 85% or
 *       more of the target (or at or under it, for "at most" goals).
 *   <li>weekly_count: this week's count (Monday first) against the target, on track while it keeps pace
 *       with the days gone.
 *   <li>target_value: the latest reading. Met = achieved; otherwise on track once halfway from the
 *       baseline, or keeping pace with the end date when there is one.
 * </ul>
 */
@Service
public class GoalService {

    static final double ON_TRACK = 0.85;
    static final Map<Area, String> AREA_LABEL = Map.of(
            Area.activity, "Activity",
            Area.training, "Training",
            Area.nutrition, "Nutrition",
            Area.recovery, "Recovery",
            Area.body, "Body composition",
            Area.heart, "Heart",
            Area.mind, "Mind");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.US);

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final HealthService health;
    private final NutritionService nutrition;
    private final TrainingService training;

    public GoalService(
            JdbcClient jdbc,
            AccountService accounts,
            HealthService health,
            NutritionService nutrition,
            TrainingService training) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.health = health;
        this.nutrition = nutrition;
        this.training = training;
    }

    // ---------------------------------------------------------------- rows

    record GoalRow(
            UUID id,
            String title,
            String shortTitle,
            Area area,
            Kind kind,
            String metric,
            Comparator comparator,
            double target,
            Double baseline,
            LocalDate startsOn,
            LocalDate endsOn,
            Lifecycle lifecycle,
            OffsetDateTime achievedAt) {

        GoalOut out(ZoneId tz) {
            return new GoalOut(
                    id,
                    title,
                    shortTitle,
                    area,
                    kind,
                    metric,
                    comparator,
                    target,
                    baseline,
                    startsOn,
                    endsOn,
                    lifecycle,
                    achievedAt == null ? null : achievedAt.atZoneSameInstant(tz).toOffsetDateTime());
        }
    }

    private static GoalRow row(ResultSet rs, int i) throws SQLException {
        BigDecimal baseline = rs.getBigDecimal("baseline");
        return new GoalRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("short_title"),
                Area.valueOf(rs.getString("area")),
                Kind.valueOf(rs.getString("kind")),
                rs.getString("metric"),
                Comparator.valueOf(rs.getString("comparator")),
                rs.getBigDecimal("target").doubleValue(),
                baseline == null ? null : baseline.doubleValue(),
                rs.getObject("starts_on", LocalDate.class),
                rs.getObject("ends_on", LocalDate.class),
                Lifecycle.valueOf(rs.getString("lifecycle")),
                rs.getObject("achieved_at", OffsetDateTime.class));
    }

    private List<GoalRow> goals(UUID userId) {
        return jdbc.sql("SELECT * FROM vitallink.goals WHERE user_id = :u ORDER BY created_at, id")
                .param("u", userId)
                .query(GoalService::row)
                .list();
    }

    private GoalRow goal(CurrentUser me, UUID id) {
        return jdbc.sql("SELECT * FROM vitallink.goals WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", me.id())
                .query(GoalService::row)
                .optional()
                .orElseThrow(() -> ApiError.notFound("No such goal."));
    }

    // ---------------------------------------------------------------- values

    /** Daily values of a goal metric for local days in [from, to] (days without data are absent). */
    Map<LocalDate, Double> daily(UUID userId, String tz, GoalMetric m, LocalDate from, LocalDate to) {
        return switch (m.source()) {
            case health -> health.daily(userId, tz, m.code(), from, to);
            case sleep -> health.nightlySleep(userId, tz, from, to);
            case nutrition ->
                m.code().equals("water_ml")
                        ? nutrition.dailyWater(userId, tz, from, to)
                        : nutrition.dailyMacro(userId, tz, m.code(), from, to);
            case workouts -> throw new IllegalStateException("workouts are counted per week");
        };
    }

    int workoutsThisWeek(UUID userId, ZoneId tz, LocalDate today) {
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        return training.workoutTimes(
                        userId,
                        monday.atStartOfDay(tz).toOffsetDateTime(),
                        monday.plusDays(7).atStartOfDay(tz).toOffsetDateTime())
                .size();
    }

    // ---------------------------------------------------------------- formatting

    static String thousands(double v) {
        return String.format(Locale.US, "%,d", Math.round(v));
    }

    static String one(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    /** A value in the goal metric's unit, converted to the user's units where they differ. */
    static String format(String unit, double v, Units units) {
        boolean imperial = units == Units.imperial;
        return switch (unit) {
            case "count", "sessions" -> thousands(v);
            case "min" -> thousands(v) + " min";
            case "sleep_min" -> {
                long total = Math.round(v);
                yield (total / 60) + "h " + (total % 60) + "m";
            }
            case "kcal" -> thousands(v) + " kcal";
            case "h" -> Math.round(v) + " h";
            case "m" -> imperial ? one(v / 1609.344) + " mi" : one(v / 1000) + " km";
            case "g" -> Math.round(v) + " g";
            case "ml" -> thousands(v) + " ml";
            case "bpm" -> Math.round(v) + " BPM";
            case "ms" -> Math.round(v) + " ms";
            case "kg" -> imperial ? one(v * 2.20462) + " lb" : one(v) + " kg";
            case "cm" -> imperial ? one(v / 2.54) + " in" : one(v) + " cm";
            case "%" -> one(v) + "%";
            default -> one(v);
        };
    }

    // ---------------------------------------------------------------- tracking

    /** Everything one read needs, loaded once. */
    record Ctx(UUID userId, String tzName, ZoneId tz, LocalDate today, OffsetDateTime now, Units units) {}

    Ctx ctx(UUID userId, String tzName) {
        ZoneId tz = ZoneId.of(tzName);
        OffsetDateTime now = Clock.now();
        return new Ctx(
                userId,
                tzName,
                tz,
                now.atZoneSameInstant(tz).toLocalDate(),
                now,
                accounts.profile(userId).units());
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    TrackedGoal track(GoalRow g, Ctx c) {
        GoalMetric m = GoalMetrics.BY_CODE.get(g.metric());
        String area = AREA_LABEL.get(g.area());
        double progress;
        String status;
        String detail;
        String shortDetail;
        switch (g.kind()) {
            case daily_total -> {
                Map<LocalDate, Double> days =
                        daily(c.userId(), c.tzName(), m, c.today().minusDays(6), c.today());
                if (days.isEmpty()) {
                    progress = 0;
                    status = "at_risk";
                    detail = "No data in the last 7 days";
                    shortDetail = "No data yet";
                    break;
                }
                double avg = days.values().stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(0);
                boolean atMost = g.comparator() == Comparator.at_most;
                progress = atMost ? (avg <= g.target() ? 1 : g.target() / avg) : clamp(avg / g.target());
                status = progress >= ON_TRACK ? "on_track" : "at_risk";
                String average = format(m.unit(), avg, c.units());
                detail = average + " average over 7 days";
                double gap = Math.abs(g.target() - avg);
                if (status.equals("at_risk") && m.source() == Source.sleep && !atMost) {
                    detail = average + " average · " + Math.round(gap) + " min short";
                }
                shortDetail = average + " average";
            }
            case weekly_count -> {
                int n = workoutsThisWeek(c.userId(), c.tz(), c.today());
                int target = (int) Math.round(g.target());
                int elapsed = c.today().getDayOfWeek().getValue();
                int left = 7 - elapsed;
                progress = clamp((double) n / target);
                status = n >= target || n >= Math.floor(target * elapsed / 7.0) ? "on_track" : "at_risk";
                String daysLeft = left == 0 ? "last day" : left == 1 ? "1 day left" : left + " days left";
                detail = n + " of " + target + " sessions · " + daysLeft;
                shortDetail = n + " of " + target + " · " + daysLeft;
            }
            default -> {
                var latest = health.latestWithTime(c.userId(), g.metric());
                if (latest.isEmpty()) {
                    progress = 0;
                    status = "at_risk";
                    detail = "No readings yet";
                    shortDetail = "No readings yet";
                    break;
                }
                double current = latest.get().getKey();
                boolean atMost = g.comparator() == Comparator.at_most;
                boolean met = atMost ? current <= g.target() : current >= g.target();
                double base = g.baseline() != null ? g.baseline() : current;
                if (met) {
                    progress = 1;
                } else if (base == g.target()) {
                    progress = 0;
                } else {
                    progress = clamp(
                            atMost ? (base - current) / (base - g.target()) : (current - base) / (g.target() - base));
                }
                double expected = 0.5;
                if (g.endsOn() != null) {
                    long total = ChronoUnit.DAYS.between(g.startsOn(), g.endsOn());
                    long gone = ChronoUnit.DAYS.between(g.startsOn(), c.today());
                    expected = clamp((double) gone / total) - 0.1;
                }
                status = met ? "achieved" : progress >= expected ? "on_track" : "at_risk";
                String now = format(m.unit(), current, c.units());
                double delta = current - base;
                String change = delta == 0
                        ? "no change"
                        : (delta < 0 ? "down " : "up ")
                                + format(m.unit(), Math.abs(delta), c.units())
                                        .replaceAll("[^0-9.,]+$", "")
                                        .strip();
                if (met) {
                    detail = now + " · goal met";
                    shortDetail = now + " · met";
                } else {
                    detail = now + " · " + change + " since " + g.startsOn().format(DAY_MONTH);
                    shortDetail = now + " · " + change;
                }
            }
        }
        if (g.lifecycle() == Lifecycle.paused) {
            status = "paused";
        } else if (g.lifecycle() == Lifecycle.achieved) {
            status = "achieved";
            progress = 1;
        }
        return new TrackedGoal(
                g.id(),
                g.title(),
                g.shortTitle(),
                area,
                status,
                Math.round(progress * 100) / 100.0,
                detail,
                shortDetail);
    }

    // ---------------------------------------------------------------- reads

    public List<GoalOut> list(CurrentUser me) {
        return goals(me.id()).stream().map(g -> g.out(me.zone())).toList();
    }

    public GoalOut get(CurrentUser me, UUID id) {
        return goal(me, id).out(me.zone());
    }

    /** Days on which the step goal was met, over the last {@code days} days. */
    Set<LocalDate> stepGoalDays(Ctx c, int days, int goal) {
        Set<LocalDate> met = new HashSet<>();
        health.daily(c.userId(), c.tzName(), "steps", c.today().minusDays(days - 1L), c.today())
                .forEach((d, v) -> {
                    if (v >= goal) {
                        met.add(d);
                    }
                });
        return met;
    }

    public GoalsOverview overview(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        List<GoalRow> all = goals(userId);
        List<TrackedGoal> tracked = all.stream()
                .filter(g -> g.lifecycle() == Lifecycle.active
                        || g.lifecycle() == Lifecycle.paused
                        || (g.lifecycle() == Lifecycle.achieved
                                && g.achievedAt().isAfter(c.now().minusDays(30))))
                .map(g -> track(g, c))
                .toList();
        LocalDate monthStart = c.today().withDayOfMonth(1);
        int achievedThisMonth = (int) all.stream()
                .filter(g -> g.achievedAt() != null
                        && !g.achievedAt()
                                .atZoneSameInstant(c.tz())
                                .toLocalDate()
                                .isBefore(monthStart))
                .count();
        int goal = accounts.targets(userId).dailyStepsGoal();
        Set<LocalDate> met = stepGoalDays(c, 365, goal);
        // Last 5 weeks, Monday first, oldest week first; days still to come count as not met.
        LocalDate monday =
                c.today().minusDays(c.today().getDayOfWeek().getValue() - 1L).minusWeeks(4);
        List<List<Boolean>> consistency = new ArrayList<>();
        for (int w = 0; w < 5; w++) {
            List<Boolean> week = new ArrayList<>();
            for (int d = 0; d < 7; d++) {
                week.add(met.contains(monday.plusDays(w * 7L + d)));
            }
            consistency.add(week);
        }
        List<Achievement> recent = all.stream()
                .filter(g -> g.achievedAt() != null)
                .sorted(java.util.Comparator.comparing(GoalRow::achievedAt).reversed())
                .limit(5)
                .map(g -> new Achievement(
                        g.shortTitle(), g.achievedAt().atZoneSameInstant(c.tz()).toOffsetDateTime()))
                .toList();
        return new GoalsOverview(
                tracked, achievedThisMonth, new Streak(Streaks.longest(met), "step goal"), consistency, recent);
    }

    /** Today's progress on the daily targets, then on active goals with a number for today. */
    public List<GoalProgress> today(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        Targets t = accounts.targets(userId);
        OffsetDateTime start = c.today().atStartOfDay(c.tz()).toOffsetDateTime();
        OffsetDateTime end = c.today().plusDays(1).atStartOfDay(c.tz()).toOffsetDateTime();
        List<GoalProgress> out = new ArrayList<>(List.of(
                new GoalProgress(
                        "steps",
                        "steps",
                        "Daily steps",
                        Math.round(health.totalBetween(userId, "steps", start, end)),
                        t.dailyStepsGoal(),
                        ""),
                new GoalProgress(
                        "exercise_minutes",
                        "exercise_minutes",
                        "Exercise minutes",
                        Math.round(health.totalBetween(userId, "exercise_minutes", start, end)),
                        t.exerciseMinGoal(),
                        ""),
                new GoalProgress(
                        "stand_hours",
                        "stand_hours",
                        "Stand hours",
                        Math.round(health.totalBetween(userId, "stand_hours", start, end)),
                        t.standHoursGoal(),
                        "")));
        for (GoalRow g : goals(userId)) {
            if (g.lifecycle() != Lifecycle.active
                    || Set.of("steps", "exercise_minutes", "stand_hours").contains(g.metric())) {
                continue;
            }
            GoalMetric m = GoalMetrics.BY_CODE.get(g.metric());
            if (g.kind() == Kind.weekly_count) {
                out.add(new GoalProgress(
                        g.id().toString(),
                        g.metric(),
                        g.title(),
                        workoutsThisWeek(userId, c.tz(), c.today()),
                        g.target(),
                        "sessions"));
            } else if (g.kind() == Kind.daily_total) {
                double value = daily(userId, tzName, m, c.today(), c.today()).getOrDefault(c.today(), 0.0);
                out.add(new GoalProgress(
                        g.id().toString(),
                        g.metric(),
                        g.title(),
                        Math.round(value * 10) / 10.0,
                        g.target(),
                        m.unit().equals("sleep_min") ? "min" : m.unit()));
            }
        }
        return out;
    }

    /** Current move-ring streak, the longest this year, and the current stand streak. */
    public List<StreakOut> streaks(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        Targets t = accounts.targets(userId);
        LocalDate yearStart = c.today().withDayOfYear(1);
        LocalDate from = yearStart.isBefore(c.today().minusDays(60))
                ? yearStart
                : c.today().minusDays(60);
        Set<LocalDate> moved = new HashSet<>();
        health.daily(userId, tzName, "active_energy", from, c.today()).forEach((d, v) -> {
            if (v >= t.moveKcalGoal()) moved.add(d);
        });
        Set<LocalDate> stood = new HashSet<>();
        health.daily(userId, tzName, "stand_hours", c.today().minusDays(60), c.today())
                .forEach((d, v) -> {
                    if (v >= t.standHoursGoal()) stood.add(d);
                });
        Set<LocalDate> thisYear = new HashSet<>();
        moved.stream().filter(d -> !d.isBefore(yearStart)).forEach(thisYear::add);
        return List.of(
                new StreakOut("Move ring closed", Streaks.current(moved, c.today())),
                new StreakOut("Longest this year", Streaks.longest(thisYear)),
                new StreakOut("Stood every hour", Streaks.current(stood, c.today())));
    }

    // ---------------------------------------------------------------- writes

    public GoalOut create(CurrentUser me, GoalIn body) {
        GoalMetric m = GoalMetrics.BY_CODE.get(body.metric());
        if (m == null) {
            throw new ApiError(
                    422,
                    "unknown_metric",
                    "Goals can't track '" + body.metric() + "'.",
                    GoalMetrics.ALL.stream().map(GoalMetric::code).toList());
        }
        LocalDate starts = body.startsOn() != null ? body.startsOn() : LocalDate.now(me.zone());
        if (body.endsOn() != null && !body.endsOn().isAfter(starts)) {
            throw ApiError.invalid("The end date must be after the start.");
        }
        Double baseline = null;
        if (m.kind() == Kind.target_value) {
            baseline = body.baseline() != null ? body.baseline() : health.latestValue(me.id(), m.code());
        } else if (body.baseline() != null) {
            throw Patch.invalid("baseline", "only goals that reach a reading have a baseline");
        }
        Comparator comparator = body.comparator() != null
                ? body.comparator()
                : baseline != null && baseline > body.target() ? Comparator.at_most : Comparator.at_least;
        UUID id = Ids.uuid7();
        String shortTitle = body.shortTitle() != null
                ? body.shortTitle()
                : body.title().length() <= 40
                        ? body.title()
                        : body.title().substring(0, 40).strip();
        jdbc.sql("""
                        INSERT INTO vitallink.goals (id, user_id, title, short_title, area, kind, metric, comparator,
                                                     target, baseline, starts_on, ends_on)
                        VALUES (:id, :u, :title, :short, CAST(:area AS vitallink.goal_area),
                                CAST(:kind AS vitallink.goal_kind), :metric, CAST(:cmp AS vitallink.comparator),
                                :target, :baseline, :starts, :ends)
                        """)
                .param("id", id)
                .param("u", me.id())
                .param("title", body.title())
                .param("short", shortTitle)
                .param("area", (body.area() != null ? body.area() : m.area()).name())
                .param("kind", m.kind().name())
                .param("metric", m.code())
                .param("cmp", comparator.name())
                .param("target", body.target())
                .param("baseline", baseline)
                .param("starts", starts)
                .param("ends", body.endsOn())
                .update();
        return get(me, id);
    }

    private static final Set<String> FIELDS = Set.of("title", "short_title", "target", "ends_on", "lifecycle", "area");

    /** Edit, pause, resume, mark achieved or abandon a goal. */
    public GoalOut update(CurrentUser me, UUID id, Map<String, Object> body) {
        GoalRow g = goal(me, id);
        Patch p = Patch.of(body, FIELDS);
        Map<String, Object> changes = new LinkedHashMap<>();
        List<String> sets = new ArrayList<>();
        if (p.has("title")) {
            changes.put("title", nonNull(p.string("title", 1, 80, null), "title"));
            sets.add("title = :title");
        }
        if (p.has("short_title")) {
            changes.put("short_title", nonNull(p.string("short_title", 1, 40, null), "short_title"));
            sets.add("short_title = :short_title");
        }
        if (p.has("target")) {
            double target = nonNull(p.number("target", 0, 99999999), "target");
            if (target <= 0) {
                throw Patch.invalid("target", "must be more than 0");
            }
            changes.put("target", target);
            sets.add("target = :target");
        }
        if (p.has("ends_on")) {
            LocalDate ends = p.date("ends_on");
            if (ends != null && !ends.isAfter(g.startsOn())) {
                throw Patch.invalid("ends_on", "must be after the start date");
            }
            changes.put("ends_on", ends);
            sets.add("ends_on = :ends_on");
        }
        if (p.has("area")) {
            changes.put("area", nonNull(p.enumValue("area", Area.class), "area").name());
            sets.add("area = CAST(:area AS vitallink.goal_area)");
        }
        if (p.has("lifecycle")) {
            Lifecycle next = nonNull(p.enumValue("lifecycle", Lifecycle.class), "lifecycle");
            changes.put("lifecycle", next.name());
            sets.add("lifecycle = CAST(:lifecycle AS vitallink.goal_lifecycle)");
            changes.put(
                    "achieved_at",
                    next == Lifecycle.achieved ? (g.achievedAt() != null ? g.achievedAt() : Clock.now()) : null);
            sets.add("achieved_at = :achieved_at");
        }
        if (!sets.isEmpty()) {
            jdbc.sql("UPDATE vitallink.goals SET " + String.join(", ", sets) + ", updated_at = now() "
                            + "WHERE id = :id AND user_id = :u")
                    .params(changes)
                    .param("id", id)
                    .param("u", me.id())
                    .update();
        }
        return get(me, id);
    }

    private static <T> T nonNull(T v, String field) {
        if (v == null) {
            throw Patch.invalid(field, "can't be empty");
        }
        return v;
    }

    public void delete(CurrentUser me, UUID id) {
        int n = jdbc.sql("DELETE FROM vitallink.goals WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such goal.");
        }
    }

    // ---------------------------------------------------------------- nightly job

    /** Marks active goals whose target reading has been reached as achieved. Returns their titles. */
    public List<String> markAchieved(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        List<String> done = new ArrayList<>();
        for (GoalRow g : goals(userId)) {
            if (g.lifecycle() == Lifecycle.active
                    && g.kind() == Kind.target_value
                    && track(g, c).status().equals("achieved")) {
                jdbc.sql("UPDATE vitallink.goals SET lifecycle = 'achieved', achieved_at = now(), updated_at = now() "
                                + "WHERE id = :id AND lifecycle = 'active'")
                        .param("id", g.id())
                        .update();
                done.add(g.shortTitle());
            }
        }
        return done;
    }

    /** Active goals currently at risk (nightly job notifications). */
    public List<TrackedGoal> atRisk(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        return goals(userId).stream()
                .filter(g -> g.lifecycle() == Lifecycle.active)
                .map(g -> track(g, c))
                .filter(t -> t.status().equals("at_risk"))
                .toList();
    }
}
