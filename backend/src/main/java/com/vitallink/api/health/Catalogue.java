package com.vitallink.api.health;

import static com.vitallink.api.core.audit.Audit.Scope.activity;
import static com.vitallink.api.core.audit.Audit.Scope.sleep;
import static com.vitallink.api.core.audit.Audit.Scope.vitals;

import com.vitallink.api.core.audit.Audit.Scope;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The closed metric catalogue (docs/04-database.md §4.4).
 *
 * <p>Each metric belongs to exactly one sharing scope. Its plausible range is for one 5-minute bucket and
 * rejects garbage. Its normal range (optional) drives the dashboard's range chip. Ids are permanent: never
 * renumber, only append. Migration V3 seeds the same rows, and a test keeps the two in step.
 */
public final class Catalogue {

    private Catalogue() {}

    public enum Agg {
        sum,
        avg,
        max,
        last
    }

    /**
     * @param label the device data label ("Heart rate"); several metrics share one
     */
    public record Metric(
            int id,
            String code,
            Scope scope,
            String unit,
            Agg aggregation,
            double minValue,
            double maxValue,
            boolean manualAllowed,
            Double normalLow,
            Double normalHigh,
            String label) {}

    private static Metric m(
            int id,
            String code,
            Scope scope,
            String unit,
            Agg agg,
            double min,
            double max,
            boolean manual,
            Double low,
            Double high,
            String label) {
        return new Metric(id, code, scope, unit, agg, min, max, manual, low, high, label);
    }

    private static final Agg SUM = Agg.sum, AVG = Agg.avg, LAST = Agg.last;

    public static final List<Metric> METRICS = List.of(
            // vitals
            m(1, "heart_rate", vitals, "bpm", AVG, 25, 250, true, 50.0, 100.0, "Heart rate"),
            m(2, "resting_heart_rate", vitals, "bpm", AVG, 25, 150, false, 40.0, 100.0, "Heart rate"),
            m(3, "hrv", vitals, "ms", AVG, 1, 300, false, null, null, "Heart rate"),
            m(4, "bp_systolic", vitals, "mmHg", AVG, 60, 260, true, null, null, "Blood pressure"),
            m(5, "bp_diastolic", vitals, "mmHg", AVG, 30, 160, true, null, null, "Blood pressure"),
            m(6, "oxygen_saturation", vitals, "%", AVG, 50, 100, true, 95.0, 100.0, "Blood oxygen"),
            m(7, "respiratory_rate", vitals, "breaths/min", AVG, 4, 60, false, 12.0, 20.0, "Respiratory rate"),
            m(8, "glucose", vitals, "mg/dL", AVG, 20, 600, true, 70.0, 140.0, "Blood glucose"),
            m(9, "body_temperature", vitals, "°C", AVG, 30, 45, true, 36.1, 37.5, "Temperature"),
            m(10, "vo2_max", vitals, "mL/kg/min", LAST, 10, 90, false, null, null, "Cardio fitness"),
            m(11, "hr_high_event", vitals, "count", SUM, 0, 10, false, null, null, "Heart alerts"),
            m(12, "hr_low_event", vitals, "count", SUM, 0, 10, false, null, null, "Heart alerts"),
            m(13, "irregular_rhythm_event", vitals, "count", SUM, 0, 10, false, null, null, "Heart alerts"),
            // body (vitals scope)
            m(20, "weight", vitals, "kg", AVG, 20, 400, true, null, null, "Weight"),
            m(21, "body_fat", vitals, "%", AVG, 2, 75, true, null, null, "Body composition"),
            m(22, "lean_mass", vitals, "kg", AVG, 10, 200, true, null, null, "Body composition"),
            m(23, "waist", vitals, "cm", AVG, 10, 250, true, null, null, "Measurements"),
            m(24, "hip", vitals, "cm", AVG, 10, 250, true, null, null, "Measurements"),
            m(25, "chest", vitals, "cm", AVG, 10, 250, true, null, null, "Measurements"),
            m(26, "arm", vitals, "cm", AVG, 10, 250, true, null, null, "Measurements"),
            m(27, "thigh", vitals, "cm", AVG, 10, 250, true, null, null, "Measurements"),
            // activity
            m(40, "steps", activity, "count", SUM, 0, 5000, false, null, null, "Activity"),
            m(41, "distance", activity, "m", SUM, 0, 6000, false, null, null, "Activity"),
            m(42, "active_energy", activity, "kcal", SUM, 0, 250, false, null, null, "Activity"),
            m(43, "basal_energy", activity, "kcal", SUM, 0, 50, false, null, null, "Activity"),
            m(44, "exercise_minutes", activity, "min", SUM, 0, 5, false, null, null, "Activity"),
            m(45, "stand_hours", activity, "count", SUM, 0, 1, false, null, null, "Activity"),
            m(46, "flights_climbed", activity, "count", SUM, 0, 100, false, null, null, "Activity"),
            m(47, "mindful_minutes", activity, "min", SUM, 0, 5, true, null, null, "Mindfulness"),
            m(48, "mood", activity, "score", AVG, 1, 5, true, null, null, "Mood"),
            m(49, "stress", activity, "score", AVG, 1, 5, true, null, null, "Mood"),
            m(50, "energy", activity, "score", AVG, 1, 5, true, null, null, "Mood"),
            m(51, "screen_time", activity, "min", SUM, 0, 5, false, null, null, "Screen time"),
            m(52, "screen_time_social", activity, "min", SUM, 0, 5, false, null, null, "Screen time"),
            m(53, "screen_time_entertainment", activity, "min", SUM, 0, 5, false, null, null, "Screen time"),
            m(54, "screen_time_productivity", activity, "min", SUM, 0, 5, false, null, null, "Screen time"),
            m(55, "phone_pickups", activity, "count", SUM, 0, 200, false, null, null, "Screen time"),
            m(56, "notifications_received", activity, "count", SUM, 0, 1000, false, null, null, "Screen time"),
            // sleep (minutes in each stage per 5-minute bucket)
            m(60, "sleep_asleep", sleep, "min", SUM, 0, 5, false, null, null, "Sleep"),
            m(61, "sleep_deep", sleep, "min", SUM, 0, 5, false, null, null, "Sleep"),
            m(62, "sleep_core", sleep, "min", SUM, 0, 5, false, null, null, "Sleep"),
            m(63, "sleep_rem", sleep, "min", SUM, 0, 5, false, null, null, "Sleep"),
            m(64, "sleep_awake", sleep, "min", SUM, 0, 5, false, null, null, "Sleep"));

    public static final Map<String, Metric> BY_CODE =
            METRICS.stream().collect(Collectors.toUnmodifiableMap(Metric::code, Function.identity()));
    public static final Map<Integer, Metric> BY_ID =
            METRICS.stream().collect(Collectors.toUnmodifiableMap(Metric::id, Function.identity()));

    /** Pseudo-metric the API accepts in series/latest: systolic + diastolic at the same instants. */
    public static final String BLOOD_PRESSURE = "blood_pressure";

    public static Metric get(String code) {
        return BY_CODE.get(code);
    }
}
