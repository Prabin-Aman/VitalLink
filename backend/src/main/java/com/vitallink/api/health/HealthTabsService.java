package com.vitallink.api.health;

import com.vitallink.api.account.AccountDtos.Targets;
import com.vitallink.api.account.AccountDtos.Units;
import com.vitallink.api.account.AccountService;
import com.vitallink.api.account.AccountService.Profile;
import com.vitallink.api.core.Clock;
import com.vitallink.api.core.Num;
import com.vitallink.api.core.Streaks;
import com.vitallink.api.health.Catalogue.Metric;
import com.vitallink.api.health.HealthDtos.DeviceOut;
import com.vitallink.api.health.HealthTabDtos.Aerobic;
import com.vitallink.api.health.HealthTabDtos.Alert;
import com.vitallink.api.health.HealthTabDtos.BodyFatGoal;
import com.vitallink.api.health.HealthTabDtos.BodyTab;
import com.vitallink.api.health.HealthTabDtos.Bp30;
import com.vitallink.api.health.HealthTabDtos.Category;
import com.vitallink.api.health.HealthTabDtos.DeepSleep;
import com.vitallink.api.health.HealthTabDtos.DeviceData;
import com.vitallink.api.health.HealthTabDtos.DigitalDay;
import com.vitallink.api.health.HealthTabDtos.DigitalTab;
import com.vitallink.api.health.HealthTabDtos.HealthOverview;
import com.vitallink.api.health.HealthTabDtos.HeartTab;
import com.vitallink.api.health.HealthTabDtos.Insight;
import com.vitallink.api.health.HealthTabDtos.LastNight;
import com.vitallink.api.health.HealthTabDtos.Measurement;
import com.vitallink.api.health.HealthTabDtos.MentalTab;
import com.vitallink.api.health.HealthTabDtos.Mindful;
import com.vitallink.api.health.HealthTabDtos.RespiratoryTab;
import com.vitallink.api.health.HealthTabDtos.Resting;
import com.vitallink.api.health.HealthTabDtos.Rings;
import com.vitallink.api.health.HealthTabDtos.Section;
import com.vitallink.api.health.HealthTabDtos.SleepConsistency;
import com.vitallink.api.health.HealthTabDtos.SleepNight;
import com.vitallink.api.health.HealthTabDtos.SleepTab;
import com.vitallink.api.health.HealthTabDtos.Source;
import com.vitallink.api.health.HealthTabDtos.TabReading;
import com.vitallink.api.health.HealthTabDtos.VitalsTab;
import com.vitallink.api.health.HealthTabDtos.Weekly;
import com.vitallink.api.health.HealthTabDtos.Zone;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * My Health: the overview and the seven tabs, computed from the user's readings, workouts and goals. Every
 * aggregate runs in Postgres; this class shapes the results. Anything without data is left out.
 *
 * <p>Nights: a night "ends on" a date and runs 18:00 the evening before → 14:00 that day.
 */
@Service
public class HealthTabsService {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private final JdbcClient jdbc;
    private final HealthService health;
    private final AccountService accounts;

    public HealthTabsService(JdbcClient jdbc, HealthService health, AccountService accounts) {
        this.jdbc = jdbc;
        this.health = health;
        this.accounts = accounts;
    }

    /** Everything one request needs, loaded once. */
    record Ctx(
            UUID userId,
            String tzName,
            ZoneId tz,
            OffsetDateTime now,
            LocalDate today,
            Profile profile,
            Targets targets) {
        boolean imperial() {
            return profile.units() == Units.imperial;
        }

        OffsetDateTime midnight(LocalDate d) {
            return d.atStartOfDay(tz).toOffsetDateTime();
        }

        OffsetDateTime local(OffsetDateTime t) {
            return t == null ? null : t.atZoneSameInstant(tz).toOffsetDateTime();
        }
    }

    Ctx ctx(UUID userId, String tzName) {
        ZoneId tz = ZoneId.of(tzName);
        OffsetDateTime now = Clock.now();
        Profile p = accounts.profile(userId);
        return new Ctx(userId, tzName, tz, now, now.atZoneSameInstant(tz).toLocalDate(), p, p.targets());
    }

    // ---------------------------------------------------------------- small queries

    record Bucket(String code, OffsetDateTime at, double value) {}

    /** Raw buckets of some metrics in [from, to), oldest first. */
    List<Bucket> buckets(UUID userId, List<String> codes, OffsetDateTime from, OffsetDateTime to) {
        List<Integer> ids = codes.stream().map(c -> Catalogue.get(c).id()).toList();
        return jdbc.sql("""
                        SELECT m.code, r.recorded_at, r.value FROM vitallink.health_readings r
                        JOIN vitallink.metrics m ON m.id = r.metric_id
                        WHERE r.user_id = :u AND r.metric_id IN (:ids) AND r.recorded_at >= :from AND r.recorded_at < :to
                        ORDER BY r.recorded_at, m.code
                        """)
                .param("u", userId)
                .param("ids", ids)
                .param("from", from)
                .param("to", to)
                .query((rs, i) -> new Bucket(
                        rs.getString("code"), rs.getObject("recorded_at", OffsetDateTime.class), rs.getDouble("value")))
                .list();
    }

    double today(Ctx c, String code) {
        return health.totalBetween(
                c.userId(), code, c.midnight(c.today()), c.midnight(c.today().plusDays(1)));
    }

    Optional<Map.Entry<Double, OffsetDateTime>> latest(Ctx c, String code) {
        return health.latestWithTime(c.userId(), code);
    }

    Double avg(Ctx c, String code, OffsetDateTime from, OffsetDateTime to) {
        return health.averageBetween(c.userId(), code, from, to);
    }

    /** The newest reading time of a metric today, for "12 min ago" on activity totals. */
    OffsetDateTime lastToday(Ctx c, String code) {
        return latest(c, code)
                .map(Map.Entry::getValue)
                .filter(t -> !t.isBefore(c.midnight(c.today())))
                .map(c::local)
                .orElse(null);
    }

    static String thousands(double v) {
        return String.format(Locale.US, "%,d", Math.round(v));
    }

    /** "Above average", "Below average" or "Average" against the daily mean of the 30 days before today. */
    String versusAverage(Ctx c, String code, double todayValue) {
        Map<LocalDate, Double> days = health.daily(
                c.userId(), c.tzName(), code, c.today().minusDays(30), c.today().minusDays(1));
        double mean = days.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
        if (mean == 0) return "Today";
        if (todayValue >= mean * 1.1) return "Above average";
        if (todayValue <= mean * 0.9) return "Below average";
        return "Average";
    }

    String rangeLabel(String code, double v) {
        Metric m = Catalogue.get(code);
        String a = HealthService.assess(m, v);
        return a.isEmpty() ? "Latest" : a;
    }

    /** Minutes asleep and in each stage for the night ending on {@code day}. */
    record Night(
            LocalDate day,
            int asleep,
            int deep,
            int core,
            int rem,
            int awake,
            OffsetDateTime start,
            OffsetDateTime end,
            int wakeups) {}

    Night night(Ctx c, LocalDate day) {
        List<Bucket> b = buckets(
                c.userId(),
                List.of("sleep_asleep", "sleep_deep", "sleep_core", "sleep_rem", "sleep_awake"),
                c.midnight(day).minusHours(6),
                c.midnight(day).plusHours(14));
        return night(day, b);
    }

    static Night night(LocalDate day, List<Bucket> b) {
        int asleep = 0, deep = 0, core = 0, rem = 0, awake = 0, wakeups = 0;
        OffsetDateTime start = null, end = null, lastAwake = null;
        for (Bucket x : b) {
            int v = (int) Math.round(x.value());
            switch (x.code()) {
                case "sleep_asleep" -> {
                    asleep += v;
                    if (start == null) start = x.at();
                    end = x.at().plusMinutes(5);
                }
                case "sleep_deep" -> deep += v;
                case "sleep_core" -> core += v;
                case "sleep_rem" -> rem += v;
                case "sleep_awake" -> {
                    awake += v;
                    if (lastAwake == null || !lastAwake.plusMinutes(5).equals(x.at())) wakeups++;
                    lastAwake = x.at();
                }
                default -> {}
            }
        }
        return new Night(day, asleep, deep, core, rem, awake, start, end, wakeups);
    }

    /** Sleep score 0–100: duration against the goal (50%), deep sleep share (30%), little time awake (20%). */
    static int score(Night n, int goal) {
        if (n.asleep() == 0) return 0;
        double duration = Math.min(1, (double) n.asleep() / goal);
        double deep = Math.min(1, n.deep() / (0.2 * n.asleep()));
        double calm = 1 - Math.min(1, n.awake() / 60.0);
        return (int) Math.round(100 * (0.5 * duration + 0.3 * deep + 0.2 * calm));
    }

    /** Nightly totals for the nights ending on [from, to]: metric code → night → minutes. */
    Map<String, Map<LocalDate, Double>> nightly(Ctx c, List<String> codes, LocalDate from, LocalDate to) {
        Map<String, Map<LocalDate, Double>> out = new HashMap<>();
        jdbc.sql("""
                        SELECT m.code, ((r.recorded_at AT TIME ZONE :tz) + interval '6 hours')::date AS night,
                               sum(r.value) AS v
                        FROM vitallink.health_readings r JOIN vitallink.metrics m ON m.id = r.metric_id
                        WHERE r.user_id = :u AND m.code IN (:codes) AND r.recorded_at >= :from AND r.recorded_at < :to
                        GROUP BY 1, 2
                        """)
                .param("tz", c.tzName())
                .param("u", c.userId())
                .param("codes", codes)
                .param("from", c.midnight(from).minusHours(6))
                .param("to", c.midnight(to.plusDays(1)).minusHours(6))
                .query((rs, i) -> out.computeIfAbsent(rs.getString("code"), k -> new HashMap<>())
                        .put(rs.getObject("night", LocalDate.class), rs.getDouble("v")))
                .list();
        return out;
    }

    /** Screen time after 10 PM per night (22:00 → 04:00, keyed by the night's end date). */
    Map<LocalDate, Double> lateUse(Ctx c, LocalDate from, LocalDate to) {
        Map<LocalDate, Double> out = new TreeMap<>();
        jdbc.sql("""
                        SELECT ((r.recorded_at AT TIME ZONE :tz) + interval '2 hours')::date AS night, sum(r.value) AS v
                        FROM vitallink.health_readings r
                        WHERE r.user_id = :u AND r.metric_id = :m AND r.recorded_at >= :from AND r.recorded_at < :to
                          AND (((r.recorded_at AT TIME ZONE :tz)::time >= '22:00') OR ((r.recorded_at AT TIME ZONE :tz)::time < '04:00'))
                        GROUP BY 1
                        """)
                .param("tz", c.tzName())
                .param("u", c.userId())
                .param("m", Catalogue.get("screen_time").id())
                .param("from", c.midnight(from).minusHours(2))
                .param("to", c.midnight(to.plusDays(1)).minusHours(2))
                .query((rs, i) -> out.put(rs.getObject("night", LocalDate.class), rs.getDouble("v")))
                .list();
        return out;
    }

    /** Deep sleep on the heaviest vs lightest late-phone-use nights of the last 14 (the planted pattern). */
    DeepSleep deepSleepVsLateUse(Ctx c) {
        LocalDate from = c.today().minusDays(13);
        Map<LocalDate, Double> late = lateUse(c, from, c.today());
        Map<LocalDate, Double> deep =
                nightly(c, List.of("sleep_deep"), from, c.today()).getOrDefault("sleep_deep", Map.of());
        List<LocalDate> nights = late.keySet().stream()
                .filter(deep::containsKey)
                .sorted(Comparator.comparing(late::get))
                .toList();
        if (nights.size() < 4) {
            return null;
        }
        int half = nights.size() / 2;
        List<LocalDate> light = nights.subList(0, half);
        List<LocalDate> heavy = nights.subList(nights.size() - half, nights.size());
        return new DeepSleep(
                (int) Math.round(heavy.stream().mapToDouble(deep::get).average().orElse(0)),
                (int) Math.round(light.stream().mapToDouble(deep::get).average().orElse(0)),
                heavy.size(),
                light.size());
    }

    // ---------------------------------------------------------------- overview

    static final List<Section> SECTIONS = List.of(
            new Section(
                    "Activity & movement",
                    null,
                    List.of("steps", "walking_distance", "flights", "active_energy", "exercise_minutes")),
            new Section("Heart & circulation", null, List.of("resting_hr", "hrv", "vo2max", "blood_pressure")),
            new Section("Respiratory & body", null, List.of("spo2", "respiratory_rate", "weight", "bmi", "body_fat")),
            new Section(
                    "Sleep & digital wellbeing",
                    null,
                    List.of("sleep", "deep_sleep", "screen_time", "pickups", "mindful_minutes")));

    /** Screen key → catalogue codes it's built from (for "which devices feed this section"). */
    static final Map<String, List<String>> SOURCES = Map.ofEntries(
            Map.entry("steps", List.of("steps")),
            Map.entry("walking_distance", List.of("distance")),
            Map.entry("flights", List.of("flights_climbed")),
            Map.entry("active_energy", List.of("active_energy")),
            Map.entry("exercise_minutes", List.of("exercise_minutes")),
            Map.entry("resting_hr", List.of("resting_heart_rate")),
            Map.entry("hrv", List.of("hrv")),
            Map.entry("vo2max", List.of("vo2_max")),
            Map.entry("blood_pressure", List.of("bp_systolic")),
            Map.entry("spo2", List.of("oxygen_saturation")),
            Map.entry("respiratory_rate", List.of("respiratory_rate")),
            Map.entry("weight", List.of("weight")),
            Map.entry("bmi", List.of("weight")),
            Map.entry("body_fat", List.of("body_fat")),
            Map.entry("sleep", List.of("sleep_asleep")),
            Map.entry("deep_sleep", List.of("sleep_deep")),
            Map.entry("screen_time", List.of("screen_time")),
            Map.entry("pickups", List.of("phone_pickups")),
            Map.entry("mindful_minutes", List.of("mindful_minutes")));

    record DeviceInfo(String name, Set<String> codes, DeviceOut out) {}

    List<DeviceInfo> devices(Ctx c) {
        Map<UUID, Set<String>> codes = new HashMap<>();
        for (var d : health.deviceRows(c.userId())) {
            Set<String> set = new HashSet<>();
            for (Integer id : d.metricIds()) {
                Metric m = Catalogue.BY_ID.get(id);
                if (m != null) set.add(m.code());
            }
            codes.put(d.id(), set);
        }
        return health.devices(c.userId(), c.tzName()).stream()
                .map(d -> new DeviceInfo(d.name(), codes.getOrDefault(d.id(), Set.of()), d))
                .toList();
    }

    String sectionSources(List<DeviceInfo> devices, List<String> keys) {
        Set<String> needed = keys.stream()
                .flatMap(k -> SOURCES.getOrDefault(k, List.of()).stream())
                .collect(Collectors.toSet());
        String names = devices.stream()
                .filter(d -> d.codes().stream().anyMatch(needed::contains))
                .map(DeviceInfo::name)
                .distinct()
                .collect(Collectors.joining(" · "));
        return names.isEmpty() ? "Manual entries" : names;
    }

    TabReading distance(Ctx c) {
        double metres = today(c, "distance");
        double v = c.imperial() ? metres / 1609.344 : metres / 1000;
        return TabReading.at(
                "walking_distance",
                Num.round(v, 1),
                c.imperial() ? "mi" : "km",
                lastToday(c, "distance"),
                versusAverage(c, "distance", metres));
    }

    TabReading weight(Ctx c, String assessment) {
        var w = latest(c, "weight").orElse(null);
        if (w == null) return null;
        double v = c.imperial() ? w.getKey() * 2.20462 : w.getKey();
        return TabReading.at("weight", Num.round(v, 1), c.imperial() ? "lb" : "kg", c.local(w.getValue()), assessment);
    }

    String weightTrend(Ctx c) {
        var w = latest(c, "weight").orElse(null);
        Double before = avg(c, "weight", c.now().minusDays(35), c.now().minusDays(28));
        if (w == null || before == null) return "Latest";
        double d = w.getKey() - before;
        if (Math.abs(d) < 0.5) return "Steady";
        double shown = c.imperial() ? Math.abs(d) * 2.20462 : Math.abs(d);
        return (d < 0 ? "Down " : "Up ") + Num.round(shown, 1) + (c.imperial() ? " lb" : " kg");
    }

    TabReading bmi(Ctx c) {
        var w = latest(c, "weight").orElse(null);
        BigDecimal h = c.profile().heightCm();
        if (w == null || h == null) return null;
        double m = h.doubleValue() / 100;
        double bmi = Num.round(w.getKey() / (m * m), 1);
        String label = bmi < 18.5 ? "Underweight" : bmi < 25 ? "Healthy" : bmi < 30 ? "Overweight" : "Obese";
        return TabReading.at("bmi", bmi, "", c.local(w.getValue()), label);
    }

    TabReading bloodPressure(Ctx c) {
        return health.latestBp(c.userId())
                .map(b -> new TabReading(
                        "blood_pressure",
                        Num.roundInt(b.sys()),
                        (double) Num.roundInt(b.dia()),
                        "mmHg",
                        c.local(b.at()),
                        null,
                        HealthService.bpCategory(b.sys(), b.dia()).getKey(),
                        null))
                .orElse(null);
    }

    TabReading spot(Ctx c, String key, String code, String unit) {
        return latest(c, code)
                .map(l -> TabReading.at(
                        key,
                        HealthService.rounded(Catalogue.get(code), l.getKey()),
                        unit,
                        c.local(l.getValue()),
                        rangeLabel(code, l.getKey())))
                .orElse(null);
    }

    /** Last night's average of a metric (00:00–06:00), e.g. HRV or respiratory rate. */
    Double overnight(Ctx c, String code) {
        return avg(c, code, c.midnight(c.today()), c.midnight(c.today()).plusHours(6));
    }

    TabReading hrv(Ctx c) {
        Double night = overnight(c, "hrv");
        var last = latest(c, "hrv").orElse(null);
        if (night == null && last == null) return null;
        double v = night != null ? night : last.getKey();
        Double base = avg(c, "hrv", c.now().minusDays(30), c.midnight(c.today()));
        String label =
                base == null || Math.abs(v - base) <= base * 0.15 ? "Normal" : v < base ? "Below usual" : "Above usual";
        return night != null
                ? TabReading.period("hrv", Math.round(v), "ms", "overnight", label)
                : TabReading.at("hrv", Math.round(v), "ms", c.local(last.getValue()), label);
    }

    TabReading respiratoryRate(Ctx c) {
        Double night = overnight(c, "respiratory_rate");
        if (night != null) {
            return TabReading.period(
                    "respiratory_rate",
                    Num.round(night, 0),
                    "br/min",
                    "overnight",
                    rangeLabel("respiratory_rate", night));
        }
        return spot(c, "respiratory_rate", "respiratory_rate", "br/min");
    }

    static String vo2Category(double v) {
        return v >= 45 ? "Well above average" : v >= 38 ? "Above average" : v >= 30 ? "Average" : "Below average";
    }

    TabReading vo2(Ctx c) {
        return latest(c, "vo2_max")
                .map(l -> TabReading.at(
                        "vo2max", Num.round(l.getKey(), 1), "VO₂max", c.local(l.getValue()), vo2Category(l.getKey())))
                .orElse(null);
    }

    TabReading restingHr(Ctx c) {
        return spot(c, "resting_hr", "resting_heart_rate", "BPM");
    }

    TabReading bodyFat(Ctx c, String assessment) {
        return latest(c, "body_fat")
                .map(l -> TabReading.at("body_fat", Num.round(l.getKey(), 1), "%", c.local(l.getValue()), assessment))
                .orElse(null);
    }

    TabReading sleepReading(Ctx c, Night n) {
        if (n.asleep() == 0) return null;
        int goalH = c.targets().sleepGoalMin() / 60;
        return TabReading.period(
                "sleep",
                n.asleep(),
                "duration",
                "last_night",
                n.asleep() >= c.targets().sleepGoalMin() ? "Goal met" : "Goal " + goalH + "h");
    }

    TabReading deepSleepReading(Ctx c, Night n) {
        if (n.asleep() == 0) return null;
        Map<LocalDate, Double> deep = nightly(
                        c,
                        List.of("sleep_deep"),
                        c.today().minusDays(14),
                        c.today().minusDays(1))
                .getOrDefault("sleep_deep", Map.of());
        double usual = deep.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
        String label = usual == 0
                ? "Last night"
                : n.deep() < usual * 0.9 ? "Below usual" : n.deep() > usual * 1.1 ? "Above usual" : "Usual";
        return TabReading.period("deep_sleep", n.deep(), "duration", "last_night", label);
    }

    /** "Up 9" / "Down 22 min" against the daily average of the previous 7 days. */
    String versusWeek(Ctx c, String code, double todayValue, String unit) {
        Map<LocalDate, Double> days = health.daily(
                c.userId(), c.tzName(), code, c.today().minusDays(7), c.today().minusDays(1));
        if (days.isEmpty()) return "Today";
        double mean = days.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
        long d = Math.round(todayValue - mean);
        if (d == 0) return "Same as usual";
        return (d < 0 ? "Down " : "Up ") + Math.abs(d) + unit;
    }

    List<Insight> insights(Ctx c) {
        List<Insight> out = new ArrayList<>();
        DeepSleep ds = deepSleepVsLateUse(c);
        if (ds != null && ds.lightMin() > 0 && ds.heavyMin() < ds.lightMin() * 0.9) {
            int pct = (int) Math.round(100.0 * (ds.lightMin() - ds.heavyMin()) / ds.lightMin());
            out.add(new Insight(
                    "Deep sleep is " + pct + "% lower on nights with more phone use after 10 PM.", "watch"));
        }
        Set<LocalDate> moved = new HashSet<>();
        health.daily(c.userId(), c.tzName(), "active_energy", c.today().minusDays(60), c.today())
                .forEach((d, v) -> {
                    if (v >= c.targets().moveKcalGoal()) moved.add(d);
                });
        int streak = Streaks.current(moved, c.today());
        if (streak >= 3) {
            out.add(new Insight("You have closed your Move ring " + streak + " days running.", "positive"));
        }
        var rest = latest(c, "resting_heart_rate").orElse(null);
        Double base = avg(c, "resting_heart_rate", c.now().minusDays(90), c.now());
        if (rest != null && base != null) {
            long d = Math.round(rest.getKey() - base);
            if (Math.abs(d) >= 2) {
                out.add(new Insight(
                        "Resting heart rate is " + Math.abs(d) + " BPM " + (d < 0 ? "lower" : "higher")
                                + " than your 90-day average.",
                        d < 0 ? "positive" : "watch"));
            }
        }
        Map<LocalDate, Double> asleep =
                health.nightlySleep(c.userId(), c.tzName(), c.today().minusDays(6), c.today());
        long short_ = asleep.values().stream()
                .filter(v -> v < c.targets().sleepGoalMin() - 30)
                .count();
        if (asleep.size() >= 5 && short_ >= 4) {
            out.add(new Insight(
                    "You slept more than 30 minutes under your goal on " + short_ + " of the last 7 nights.", "watch"));
        }
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    public HealthOverview overview(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        List<DeviceInfo> devices = devices(c);
        Night last = night(c, c.today());
        List<TabReading> readings = new ArrayList<>();
        double steps = today(c, "steps");
        readings.add(TabReading.at(
                "steps",
                Math.round(steps),
                "steps",
                lastToday(c, "steps"),
                "Goal " + thousands(c.targets().dailyStepsGoal())));
        readings.add(distance(c));
        double flights = today(c, "flights_climbed");
        readings.add(TabReading.at(
                "flights",
                Math.round(flights),
                "floors",
                lastToday(c, "flights_climbed"),
                versusAverage(c, "flights_climbed", flights)));
        readings.add(TabReading.at(
                "active_energy",
                Math.round(today(c, "active_energy")),
                "kcal",
                lastToday(c, "active_energy"),
                "Goal " + c.targets().moveKcalGoal()));
        readings.add(TabReading.at(
                "exercise_minutes",
                Math.round(today(c, "exercise_minutes")),
                "min",
                lastToday(c, "exercise_minutes"),
                "Goal " + c.targets().exerciseMinGoal()));
        readings.add(restingHr(c));
        readings.add(hrv(c));
        readings.add(vo2(c));
        readings.add(bloodPressure(c));
        readings.add(spot(c, "spo2", "oxygen_saturation", "%"));
        readings.add(respiratoryRate(c));
        readings.add(weight(c, weightTrend(c)));
        readings.add(bmi(c));
        readings.add(bodyFat(c, "Latest"));
        readings.add(sleepReading(c, last));
        readings.add(deepSleepReading(c, last));
        double screen = today(c, "screen_time");
        if (screen > 0) {
            readings.add(TabReading.period(
                    "screen_time",
                    Math.round(screen),
                    "duration",
                    "now",
                    versusWeek(c, "screen_time", screen, " min")));
        }
        double pickups = today(c, "phone_pickups");
        if (pickups > 0) {
            readings.add(TabReading.period(
                    "pickups", Math.round(pickups), "today", "now", versusWeek(c, "phone_pickups", pickups, "")));
        }
        double mindful = today(c, "mindful_minutes");
        if (mindful > 0) {
            readings.add(TabReading.at(
                    "mindful_minutes", Math.round(mindful), "min", lastToday(c, "mindful_minutes"), "Today"));
        }
        readings.removeIf(r -> r == null);

        DeviceInfo wearable = devices.stream()
                .filter(d -> d.out().kind() == HealthDtos.DeviceKind.wearable)
                .findFirst()
                .orElse(null);
        Rings rings = new Rings(
                new int[] {
                    (int) Math.round(today(c, "active_energy")), c.targets().moveKcalGoal()
                },
                new int[] {
                    (int) Math.round(today(c, "exercise_minutes")), c.targets().exerciseMinGoal()
                },
                new int[] {
                    (int) Math.round(today(c, "stand_hours")), c.targets().standHoursGoal()
                },
                wearable != null ? wearable.name() : "Manual entries",
                wearable != null ? wearable.out().lastSyncAt() : c.local(c.now()));
        List<Section> sections = SECTIONS.stream()
                .map(s -> new Section(s.title(), sectionSources(devices, s.metrics()), s.metrics()))
                .toList();
        List<Source> sources = devices.stream()
                .map(d -> {
                    boolean ok = d.out().status().equals("connected");
                    long days = ChronoUnit.DAYS.between(
                            d.out().lastSyncAt().toInstant(), c.now().toInstant());
                    String data = ok
                            ? String.join(", ", d.out().data())
                            : "Sync overdue · " + days + (days == 1 ? " day" : " days");
                    return new Source(d.name(), data, ok);
                })
                .toList();
        return new HealthOverview(rings, sections, readings, sources, insights(c));
    }

    // ---------------------------------------------------------------- vitals

    public VitalsTab vitals(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        List<TabReading> readings = new ArrayList<>();
        readings.add(spot(c, "heart_rate", "heart_rate", "BPM"));
        readings.add(bloodPressure(c));
        readings.add(spot(c, "spo2", "oxygen_saturation", "%"));
        readings.add(spot(c, "temperature", "body_temperature", "°C"));
        readings.add(respiratoryRate(c));
        readings.add(spot(c, "glucose", "glucose", "mg/dL"));
        readings.add(weight(c, weightTrend(c)));
        TabReading bmi = bmi(c);
        readings.add(
                bmi == null
                        ? null
                        : new TabReading(
                                "bmi",
                                bmi.value(),
                                null,
                                "",
                                bmi.at(),
                                null,
                                bmi.assessment().equals("Healthy") ? "Healthy range" : bmi.assessment(),
                                null));
        readings.removeIf(r -> r == null);
        OffsetDateTime measured = readings.stream()
                .map(TabReading::at)
                .filter(t -> t != null)
                .max(Comparator.naturalOrder())
                .orElse(c.local(c.now()));

        Map<LocalDate, Double> sys =
                health.daily(userId, tzName, "bp_systolic", c.today().minusDays(29), c.today());
        Map<LocalDate, Double> dia =
                health.daily(userId, tzName, "bp_diastolic", c.today().minusDays(29), c.today());
        List<Integer> s = new ArrayList<>();
        List<Integer> d = new ArrayList<>();
        for (int i = 28; i >= 0; i -= 2) { // every other day for 30 days, oldest first, ending today
            LocalDate day = c.today().minusDays(i);
            Double a = sys.get(day), b = dia.get(day);
            if (a == null || b == null) {
                a = sys.get(day.minusDays(1));
                b = dia.get(day.minusDays(1));
            }
            if (a != null && b != null) {
                s.add(Num.roundInt(a));
                d.add(Num.roundInt(b));
            }
        }
        List<DeviceData> sources = devices(c).stream()
                .filter(x -> x.codes().stream()
                        .anyMatch(
                                code -> Catalogue.get(code).scope() == com.vitallink.api.core.audit.Audit.Scope.vitals))
                .map(x -> new DeviceData(x.name(), String.join(", ", x.out().data())))
                .toList();
        return new VitalsTab(measured, readings, new Bp30(s, d), sources);
    }

    // ---------------------------------------------------------------- sleep

    static String clock(int minutesFrom18) {
        return LocalTime.of(18, 0).plusMinutes(minutesFrom18).format(CLOCK);
    }

    public SleepTab sleep(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        int goal = c.targets().sleepGoalMin();
        List<Bucket> all = buckets(
                userId,
                List.of("sleep_asleep", "sleep_deep", "sleep_core", "sleep_rem", "sleep_awake"),
                c.midnight(c.today().minusDays(13)).minusHours(6),
                c.midnight(c.today()).plusHours(14));
        Map<LocalDate, List<Bucket>> byNight = new TreeMap<>();
        for (Bucket b : all) {
            LocalDate night = b.at().atZoneSameInstant(c.tz()).plusHours(6).toLocalDate();
            byNight.computeIfAbsent(night, k -> new ArrayList<>()).add(b);
        }
        Map<LocalDate, Night> nights = new TreeMap<>();
        byNight.forEach((day, b) -> nights.put(day, night(day, b)));

        Night n = nights.get(c.today());
        LastNight last = n == null || n.asleep() == 0
                ? null
                : new LastNight(
                        n.asleep(),
                        c.local(n.start()),
                        c.local(n.end()),
                        goal,
                        n.deep(),
                        n.rem(),
                        n.awake(),
                        n.wakeups(),
                        score(n, goal));

        LocalDate monday = c.today().minusDays(c.today().getDayOfWeek().getValue() - 1L);
        List<SleepNight> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Night x = nights.get(monday.plusDays(i));
            week.add(x == null ? new SleepNight(0, 0, 0, 0) : new SleepNight(x.deep(), x.core(), x.rem(), x.awake()));
        }

        // Bedtimes and wake times as minutes after 18:00, so nights that cross midnight average correctly.
        Map<LocalDate, Integer> bed = new TreeMap<>();
        Map<LocalDate, Integer> wake = new TreeMap<>();
        nights.forEach((day, x) -> {
            if (x.start() != null) {
                OffsetDateTime eighteen = c.midnight(day).minusHours(6);
                bed.put(day, (int) ChronoUnit.MINUTES.between(eighteen, x.start()));
                wake.put(day, (int) ChronoUnit.MINUTES.between(eighteen, x.end()));
            }
        });
        SleepConsistency consistency = null;
        if (bed.size() >= 3) {
            double meanBed =
                    bed.values().stream().mapToInt(Integer::intValue).average().orElse(0);
            double meanWake =
                    wake.values().stream().mapToInt(Integer::intValue).average().orElse(0);
            double variance = Math.sqrt(bed.values().stream()
                    .mapToDouble(v -> (v - meanBed) * (v - meanBed))
                    .average()
                    .orElse(0));
            LocalDate steadiest = bed.entrySet().stream()
                    .min(Comparator.comparingDouble(e -> Math.abs(e.getValue() - meanBed)))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
            consistency = new SleepConsistency(
                    clock((int) Math.round(meanBed)),
                    clock((int) Math.round(meanWake)),
                    (int) Math.round(variance),
                    steadiest.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US));
        }

        List<Insight> insights = new ArrayList<>();
        DeepSleep ds = deepSleepVsLateUse(c);
        if (ds != null && ds.lightMin() > 0 && ds.heavyMin() < ds.lightMin() * 0.9) {
            int pct = (int) Math.round(100.0 * (ds.lightMin() - ds.heavyMin()) / ds.lightMin());
            insights.add(new Insight(
                    "Deep sleep is " + pct + "% lower on nights with more phone use after 10 PM.", "watch"));
        }
        double thisWeek = bed.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(c.today().minusDays(6)))
                .mapToInt(Map.Entry::getValue)
                .average()
                .orElse(Double.NaN);
        double lastWeek = bed.entrySet().stream()
                .filter(e -> e.getKey().isBefore(c.today().minusDays(6)))
                .mapToInt(Map.Entry::getValue)
                .average()
                .orElse(Double.NaN);
        if (!Double.isNaN(thisWeek) && !Double.isNaN(lastWeek) && Math.abs(thisWeek - lastWeek) >= 20) {
            long d = Math.round(Math.abs(thisWeek - lastWeek));
            insights.add(new Insight(
                    "Your bedtime moved " + d + " minutes " + (thisWeek > lastWeek ? "later" : "earlier")
                            + " on average this week.",
                    thisWeek > lastWeek ? "watch" : "positive"));
        }
        if (last != null && last.score() >= 80) {
            insights.add(new Insight(
                    "Last night scored " + last.score() + ": good duration and plenty of deep sleep.", "positive"));
        }
        return new SleepTab(last, week, consistency, insights);
    }

    // ---------------------------------------------------------------- heart

    public HeartTab heart(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        var rest = latest(c, "resting_heart_rate").orElse(null);
        Resting resting = null;
        if (rest != null) {
            Double avg90 = avg(c, "resting_heart_rate", c.now().minusDays(90), c.now());
            Map<LocalDate, Double> daily =
                    health.daily(userId, tzName, "resting_heart_rate", c.today().minusDays(29), c.today());
            List<Double> trend = new ArrayList<>();
            int i = 0;
            for (var e : daily.entrySet()) {
                if (i++ % 2 == (daily.size() - 1) % 2)
                    trend.add(Num.round(e.getValue(), 1)); // every other day, ending on the latest
            }
            resting = new Resting(
                    Num.roundInt(rest.getKey()), avg90 == null ? 0 : (int) Math.round(rest.getKey() - avg90), trend);
        }
        List<TabReading> readings = new ArrayList<>(List.of());
        readings.add(restingHr(c));
        readings.add(hrv(c));
        readings.add(vo2(c));
        readings.removeIf(r -> r == null);

        LocalDate dob = c.profile().dateOfBirth();
        int maxHr = dob != null ? 220 - (int) (ChronoUnit.DAYS.between(dob, c.today()) / 365) : 190;
        int[] bounds = {
            (int) Math.round(maxHr * 0.6),
            (int) Math.round(maxHr * 0.7),
            (int) Math.round(maxHr * 0.8),
            (int) Math.round(maxHr * 0.9)
        };
        double[] minutes = new double[5];
        jdbc.sql(
                        "SELECT duration_s, avg_hr, zone_seconds FROM vitallink.workouts WHERE user_id = :u AND started_at >= :since")
                .param("u", userId)
                .param("since", c.now().minusDays(30))
                .query((rs, n) -> {
                    java.sql.Array z = rs.getArray("zone_seconds");
                    if (z != null) {
                        Integer[] secs = (Integer[]) z.getArray();
                        for (int k = 0; k < 5; k++) minutes[k] += secs[k] / 60.0;
                    } else {
                        int hr = rs.getInt("avg_hr");
                        if (!rs.wasNull()) {
                            int zone = hr < bounds[0]
                                    ? 0
                                    : hr < bounds[1] ? 1 : hr < bounds[2] ? 2 : hr < bounds[3] ? 3 : 4;
                            minutes[zone] += rs.getInt("duration_s") / 60.0;
                        }
                    }
                    return null;
                })
                .list();
        String[] names = {"Recovery", "Easy", "Moderate", "Hard", "Peak"};
        String[] ranges = {
            "< " + bounds[0] + " BPM",
            bounds[0] + "–" + bounds[1] + " BPM",
            bounds[1] + "–" + bounds[2] + " BPM",
            bounds[2] + "–" + bounds[3] + " BPM",
            "> " + bounds[3] + " BPM"
        };
        List<Zone> zones = new ArrayList<>();
        for (int k = 0; k < 5; k++) {
            zones.add(new Zone(k + 1, names[k], ranges[k], (int) Math.round(minutes[k])));
        }
        OffsetDateTime month = c.now().minusDays(30);
        int high = (int)
                health.totalBetween(userId, "hr_high_event", month, c.now().plusMinutes(5));
        int low =
                (int) health.totalBetween(userId, "hr_low_event", month, c.now().plusMinutes(5));
        int irregular = (int) health.totalBetween(
                userId, "irregular_rhythm_event", month, c.now().plusMinutes(5));
        List<Alert> alerts = List.of(
                new Alert("High heart rate alerts", high == 0 ? "None in 30 days" : high + " in 30 days"),
                new Alert("Low heart rate alerts", low == 0 ? "None in 30 days" : low + " in 30 days"),
                new Alert(
                        "Irregular rhythm", irregular == 0 ? "Not detected" : irregular + " notifications in 30 days"));
        return new HeartTab(resting, readings, zones, alerts);
    }

    // ---------------------------------------------------------------- respiratory

    public RespiratoryTab respiratory(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        List<TabReading> readings = new ArrayList<>();
        readings.add(spot(c, "spo2", "oxygen_saturation", "%"));
        readings.add(respiratoryRate(c));
        readings.add(vo2(c));
        List<Bucket> night = buckets(
                userId,
                List.of("oxygen_saturation"),
                c.midnight(c.today()).minusHours(6),
                c.midnight(c.today()).plusHours(8));
        night.stream()
                .mapToDouble(Bucket::value)
                .min()
                .ifPresent(low -> readings.add(TabReading.period(
                        "spo2_low",
                        Math.round(low),
                        "%",
                        "overnight",
                        low >= 95 ? "within normal" : low >= 90 ? "slightly low" : "low")));
        readings.removeIf(r -> r == null);

        List<Integer> overnight = new ArrayList<>();
        Map<LocalDate, List<Double>> perNight = new TreeMap<>();
        buckets(
                        userId,
                        List.of("oxygen_saturation"),
                        c.midnight(c.today().minusDays(14)),
                        c.midnight(c.today()).plusHours(8))
                .forEach(b -> {
                    var local = b.at().atZoneSameInstant(c.tz());
                    if (local.getHour() < 8)
                        perNight.computeIfAbsent(local.toLocalDate(), k -> new ArrayList<>())
                                .add(b.value());
                });
        perNight.values()
                .forEach(v -> overnight.add((int) Math.round(
                        v.stream().mapToDouble(Double::doubleValue).average().orElse(0))));

        Aerobic aerobic = latest(c, "vo2_max")
                .map(now -> {
                    Double before = jdbc.sql("""
                            SELECT value FROM vitallink.health_readings WHERE user_id = :u AND metric_id = :m
                              AND recorded_at <= :at ORDER BY recorded_at DESC LIMIT 1
                            """)
                            .param("u", userId)
                            .param("m", Catalogue.get("vo2_max").id())
                            .param("at", c.now().minusDays(180))
                            .query(Double.class)
                            .optional()
                            .orElseGet(() -> jdbc.sql(
                                            "SELECT value FROM vitallink.health_readings WHERE user_id = :u AND metric_id = :m ORDER BY recorded_at LIMIT 1")
                                    .param("u", userId)
                                    .param("m", Catalogue.get("vo2_max").id())
                                    .query(Double.class)
                                    .single());
                    return new Aerobic(Num.round(now.getKey(), 1), Num.round(before, 1), vo2Category(now.getKey()));
                })
                .orElse(null);

        OffsetDateTime month = c.now().minusDays(30);
        long lowOxygen = buckets(
                        userId, List.of("oxygen_saturation"), month, c.now().plusMinutes(5))
                .stream()
                .filter(b -> b.value() < 90)
                .count();
        Map<LocalDate, Double> rr =
                health.daily(userId, tzName, "respiratory_rate", c.today().minusDays(29), c.today());
        long elevated = rr.values().stream().filter(v -> v > 20).count();
        List<Alert> alerts = List.of(
                new Alert(
                        "Low oxygen alerts",
                        lowOxygen == 0 ? "None in 30 days" : lowOxygen + " readings below 90% in 30 days"),
                new Alert(
                        "Elevated breathing rate",
                        elevated == 0 ? "Not detected" : elevated + " days above 20 breaths/min"));
        return new RespiratoryTab(readings, overnight, aerobic, alerts);
    }

    // ---------------------------------------------------------------- body

    record Point(double value, OffsetDateTime at) {}

    List<Point> history(Ctx c, String code, int limit) {
        return jdbc.sql("""
                        SELECT value, recorded_at FROM vitallink.health_readings WHERE user_id = :u AND metric_id = :m
                        ORDER BY recorded_at DESC LIMIT :limit
                        """)
                .param("u", c.userId())
                .param("m", Catalogue.get(code).id())
                .param("limit", limit)
                .query((rs, i) -> new Point(rs.getDouble("value"), rs.getObject("recorded_at", OffsetDateTime.class)))
                .list();
    }

    /** Weekly averages (Monday weeks) of a metric over the last 8 weeks, oldest first, weeks without data skipped. */
    List<Double> weekly(Ctx c, String code, boolean toPounds) {
        LocalDate monday =
                c.today().minusDays(c.today().getDayOfWeek().getValue() - 1L).minusWeeks(7);
        Map<LocalDate, List<Double>> weeks = new TreeMap<>();
        health.daily(c.userId(), c.tzName(), code, monday, c.today())
                .forEach((d, v) -> weeks.computeIfAbsent(d.with(DayOfWeek.MONDAY), k -> new ArrayList<>())
                        .add(v));
        return weeks.values().stream()
                .map(v -> v.stream().mapToDouble(Double::doubleValue).average().orElse(0) * (toPounds ? 2.20462 : 1))
                .map(v -> Num.round(v, 1))
                .toList();
    }

    public BodyTab body(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        boolean imp = c.imperial();
        List<TabReading> readings = new ArrayList<>();
        List<Point> weights = history(c, "weight", 400);
        if (!weights.isEmpty()) {
            Point now = weights.getFirst();
            Point eight = weights.stream()
                    .filter(p -> !p.at().isAfter(c.now().minusWeeks(8)))
                    .findFirst()
                    .orElse(weights.getLast());
            double d = now.value() - eight.value();
            double shown = imp ? Math.abs(d) * 2.20462 : Math.abs(d);
            long weeks = Math.max(1, ChronoUnit.WEEKS.between(eight.at(), now.at()));
            String trend = Math.abs(d) < 0.2
                    ? "steady"
                    : (d < 0 ? "down " : "up ") + Num.round(shown, 1) + " in " + weeks + " weeks";
            readings.add(weight(c, trend));
        }
        TabReading bmi = bmi(c);
        readings.add(
                bmi == null
                        ? null
                        : new TabReading(
                                "bmi",
                                bmi.value(),
                                null,
                                "",
                                bmi.at(),
                                null,
                                bmi.assessment().toLowerCase() + " range",
                                null));
        BodyFatGoal goal = bodyFatGoal(c);
        readings.add(bodyFat(c, goal != null ? "goal " + Num.round(goal.target(), 1) + "%" : "latest"));
        List<Point> lean = history(c, "lean_mass", 400);
        double muscleChange = 0;
        if (!lean.isEmpty()) {
            Point now = lean.getFirst();
            Point eight = lean.stream()
                    .filter(p -> !p.at().isAfter(c.now().minusWeeks(8)))
                    .findFirst()
                    .orElse(lean.getLast());
            muscleChange = Num.round(now.value() - eight.value(), 1);
            double v = imp ? now.value() * 2.20462 : now.value();
            readings.add(TabReading.at(
                    "muscle_mass",
                    Num.round(v, 1),
                    imp ? "lb" : "kg",
                    c.local(now.at()),
                    muscleChange == 0 ? "steady" : (muscleChange > 0 ? "up " : "down ") + Math.abs(muscleChange)));
        }
        List<Measurement> measurements = new ArrayList<>();
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("waist", "Waist");
        parts.put("chest", "Chest");
        parts.put("hip", "Hips");
        parts.put("arm", "Arm");
        parts.put("thigh", "Thigh");
        for (var e : parts.entrySet()) {
            List<Point> h = history(c, e.getKey(), 2);
            if (h.isEmpty()) continue;
            double f = imp ? 1 / 2.54 : 1;
            Point cur = h.getFirst();
            Point prev = h.size() > 1 ? h.get(1) : cur;
            measurements.add(new Measurement(
                    e.getValue(),
                    Num.round(cur.value() * f, 1),
                    Num.round(prev.value() * f, 1),
                    imp ? "in" : "cm",
                    c.local(cur.at())));
            if (e.getKey().equals("waist")) {
                double d = Num.round((cur.value() - prev.value()) * f, 1);
                readings.add(TabReading.at(
                        "waist",
                        Num.round(cur.value() * f, 1),
                        imp ? "in" : "cm",
                        c.local(cur.at()),
                        d == 0 ? "steady" : (d < 0 ? "down " : "up ") + Math.abs(d) + (imp ? " in" : " cm")));
            }
        }
        readings.removeIf(r -> r == null);
        return new BodyTab(
                readings,
                new Weekly(weekly(c, "weight", imp), weekly(c, "body_fat", false)),
                muscleChange,
                measurements,
                goal);
    }

    BodyFatGoal bodyFatGoal(Ctx c) {
        var goal = jdbc.sql("""
                        SELECT target, baseline FROM vitallink.goals WHERE user_id = :u AND metric = 'body_fat'
                          AND lifecycle = 'active' ORDER BY created_at DESC LIMIT 1
                        """)
                .param("u", c.userId())
                .query((rs, i) -> new double[] {
                    rs.getDouble("target"), rs.getBigDecimal("baseline") == null ? Double.NaN : rs.getDouble("baseline")
                })
                .optional()
                .orElse(null);
        var now = latest(c, "body_fat").orElse(null);
        if (goal == null || now == null) return null;
        double start = Double.isNaN(goal[1]) ? now.getKey() : goal[1];
        List<Point> h = history(c, "body_fat", 400);
        Point eight = h.stream()
                .filter(p -> !p.at().isAfter(c.now().minusWeeks(8)))
                .findFirst()
                .orElse(h.getLast());
        double weeks = Math.max(1, ChronoUnit.DAYS.between(eight.at(), now.getValue()) / 7.0);
        double rate = (eight.value() - now.getKey()) / weeks; // points lost per week
        Integer toGo = rate > 0 && now.getKey() > goal[0] ? (int) Math.ceil((now.getKey() - goal[0]) / rate) : null;
        return new BodyFatGoal(Num.round(now.getKey(), 1), Num.round(start, 1), Num.round(goal[0], 1), toGo);
    }

    // ---------------------------------------------------------------- mental

    static String mood(double score) {
        return score >= 4.5 ? "great" : score >= 3.5 ? "good" : score >= 2.5 ? "okay" : "low";
    }

    public MentalTab mental(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        LocalDate monday = c.today().minusDays(c.today().getDayOfWeek().getValue() - 1L);
        Map<LocalDate, Double> moods = health.daily(userId, tzName, "mood", monday, monday.plusDays(6));
        List<String> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Double m = moods.get(monday.plusDays(i));
            week.add(m == null ? null : mood(m));
        }
        List<TabReading> readings = new ArrayList<>();
        latest(c, "mood")
                .filter(m -> !m.getValue().isBefore(c.midnight(c.today())))
                .ifPresent(m -> readings.add(TabReading.period(
                        "mood",
                        m.getKey(),
                        "",
                        "today",
                        Character.toUpperCase(mood(m.getKey()).charAt(0))
                                + mood(m.getKey()).substring(1))));

        List<Bucket> mindfulBuckets = buckets(
                userId,
                List.of("mindful_minutes"),
                c.midnight(monday),
                c.midnight(c.today().plusDays(1)));
        int sessions = 0, longest = 0, run = 0;
        OffsetDateTime previous = null;
        double total = 0;
        for (Bucket b : mindfulBuckets) {
            total += b.value();
            if (previous != null && previous.plusMinutes(5).equals(b.at())) {
                run += (int) Math.round(b.value());
            } else {
                sessions++;
                run = (int) Math.round(b.value());
            }
            longest = Math.max(longest, run);
            previous = b.at();
        }
        Integer dailyGoal = jdbc.sql("""
                        SELECT target FROM vitallink.goals WHERE user_id = :u AND metric = 'mindful_minutes' AND lifecycle = 'active'
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .param("u", userId)
                .query(BigDecimal.class)
                .optional()
                .map(BigDecimal::intValue)
                .orElse(10);
        int weekGoal = dailyGoal * 7;
        readings.add(
                TabReading.period("mindful_minutes", Math.round(total), "min", "today", "goal " + weekGoal + " min"));
        TabReading hrv = hrv(c);
        if (hrv != null) {
            readings.add(new TabReading(
                    "hrv",
                    hrv.value(),
                    null,
                    "ms",
                    hrv.at(),
                    hrv.period(),
                    hrv.assessment().equals("Normal")
                            ? "stable"
                            : hrv.assessment().toLowerCase(),
                    null));
        }
        Night n = night(c, c.today());
        if (n.asleep() > 0) {
            int s = score(n, c.targets().sleepGoalMin());
            readings.add(TabReading.period(
                    "sleep_quality", s, "/ 100", "last_night", s >= 80 ? "good" : s >= 60 ? "fair" : "low"));
        }
        List<Insight> insights = new ArrayList<>();
        Map<LocalDate, Double> recentMood =
                health.daily(userId, tzName, "mood", c.today().minusDays(27), c.today());
        Map<LocalDate, Double> asleep =
                health.nightlySleep(userId, tzName, c.today().minusDays(27), c.today());
        List<Double> afterShort = new ArrayList<>(), afterLong = new ArrayList<>();
        recentMood.forEach((d, m) -> {
            Double s = asleep.get(d);
            if (s != null) (s < 390 ? afterShort : afterLong).add(m);
        });
        if (afterShort.size() >= 2 && afterLong.size() >= 2) {
            double a = afterShort.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);
            double b = afterLong.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);
            if (b - a >= 0.5) {
                insights.add(new Insight(
                        "Your mood is lower on days after less than 6 hours 30 minutes of sleep.", "watch"));
            }
        }
        if (hrv != null && hrv.assessment().equals("Normal")) {
            insights.add(new Insight(
                    "HRV has stayed stable, which usually tracks with a manageable stress load.", "positive"));
        }
        if (total >= weekGoal) {
            insights.add(new Insight("You have met your mindfulness goal for this week.", "positive"));
        }
        return new MentalTab(
                week, readings, new Mindful(sessions, (int) Math.round(total), weekGoal, longest), insights);
    }

    // ---------------------------------------------------------------- digital wellbeing (Android: decision D8)

    public DigitalTab digital(UUID userId, String tzName) {
        Ctx c = ctx(userId, tzName);
        Map<LocalDate, Double> screen =
                health.daily(userId, tzName, "screen_time", c.today().minusDays(13), c.today());
        double recent = screen.entrySet().stream()
                .filter(e -> e.getKey().isAfter(c.today().minusDays(7)))
                .mapToDouble(Map.Entry::getValue)
                .average()
                .orElse(0);
        double before = screen.entrySet().stream()
                .filter(e -> !e.getKey().isAfter(c.today().minusDays(7)))
                .mapToDouble(Map.Entry::getValue)
                .average()
                .orElse(recent);
        LocalDate monday = c.today().minusDays(c.today().getDayOfWeek().getValue() - 1L);
        Map<LocalDate, Double> late = lateUse(c, monday, monday.plusDays(7));
        List<DigitalDay> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            double lateMin = late.getOrDefault(d.plusDays(1), 0.0); // the night starting on this evening
            double dayMin = Math.max(0, screen.getOrDefault(d, 0.0) - lateMin);
            week.add(new DigitalDay(Num.round(dayMin / 60, 1), Num.round(lateMin / 60, 1)));
        }
        List<TabReading> readings = new ArrayList<>();
        double pickups = today(c, "phone_pickups");
        if (pickups > 0 || !screen.isEmpty()) {
            String vs = versusWeek(c, "phone_pickups", pickups, "");
            readings.add(TabReading.period(
                            "pickups", Math.round(pickups), "today", "today", vs.toLowerCase() + " from average")
                    .withShort(vs.toLowerCase()));
            double notifications = today(c, "notifications_received");
            readings.add(TabReading.period("notifications", Math.round(notifications), "today", "today", "today"));
            Map<LocalDate, Double> lateRecent = lateUse(c, c.today().minusDays(13), c.today());
            double lastNight = lateRecent.getOrDefault(c.today(), 0.0);
            double thisWeekLate = lateRecent.entrySet().stream()
                    .filter(e -> e.getKey().isAfter(c.today().minusDays(7)))
                    .mapToDouble(Map.Entry::getValue)
                    .average()
                    .orElse(0);
            double prevLate = lateRecent.entrySet().stream()
                    .filter(e -> !e.getKey().isAfter(c.today().minusDays(7)))
                    .mapToDouble(Map.Entry::getValue)
                    .average()
                    .orElse(thisWeekLate);
            long d = Math.round(thisWeekLate - prevLate);
            String lateTrend =
                    d == 0 ? "steady over 2 weeks" : (d > 0 ? "up " : "down ") + Math.abs(d) + " min in 2 weeks";
            readings.add(TabReading.period("late_use", Math.round(lastNight), "duration", "last_night", lateTrend)
                    .withShort(d == 0 ? "steady" : (d > 0 ? "up " : "down ") + Math.abs(d) + "m in 2wk"));
        }
        double social = 0, entertainment = 0, productivity = 0;
        for (var e : Map.of("screen_time_social", 0, "screen_time_entertainment", 1, "screen_time_productivity", 2)
                .entrySet()) {
            double avg = health.daily(userId, tzName, e.getKey(), c.today().minusDays(6), c.today()).values().stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);
            switch (e.getValue()) {
                case 0 -> social = avg;
                case 1 -> entertainment = avg;
                default -> productivity = avg;
            }
        }
        List<Category> categories = new ArrayList<>();
        if (recent > 0) {
            categories.add(new Category("Social", "", (int) Math.round(social)));
            categories.add(new Category("Entertainment", "", (int) Math.round(entertainment)));
            categories.add(new Category("Productivity", "", (int) Math.round(productivity)));
            categories.add(new Category(
                    "Other", "", (int) Math.max(0, Math.round(recent - social - entertainment - productivity))));
        }
        // Downtime and app limits are phone settings; VitalLink doesn't read or change them.
        return new DigitalTab(
                (int) Math.round(recent),
                (int) Math.round(recent - before),
                week,
                readings,
                categories,
                deepSleepVsLateUse(c),
                List.of());
    }
}
