package com.vitallink.api.goals;

import com.vitallink.api.goals.GoalDtos.Area;
import com.vitallink.api.goals.GoalDtos.Kind;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a goal can measure. Each entry says where the numbers come from, which kind of goal fits it, its
 * unit (SI, as stored) and the area it belongs to by default.
 */
public final class GoalMetrics {

    private GoalMetrics() {}

    public enum Source {
        /** A health metric: daily total or average from readings. */
        health,
        /** Minutes asleep per night. */
        sleep,
        /** A nutrition total from logged meals (protein_g, kcal, fibre_g) or water. */
        nutrition,
        /** Workouts logged. */
        workouts
    }

    public record GoalMetric(String code, String label, String unit, Source source, Kind kind, Area area) {}

    public static final List<GoalMetric> ALL = List.of(
            new GoalMetric("steps", "Steps", "count", Source.health, Kind.daily_total, Area.activity),
            new GoalMetric("exercise_minutes", "Exercise", "min", Source.health, Kind.daily_total, Area.activity),
            new GoalMetric("active_energy", "Active energy", "kcal", Source.health, Kind.daily_total, Area.activity),
            new GoalMetric("stand_hours", "Stand hours", "h", Source.health, Kind.daily_total, Area.activity),
            new GoalMetric("distance", "Distance", "m", Source.health, Kind.daily_total, Area.activity),
            new GoalMetric(
                    "flights_climbed", "Flights climbed", "count", Source.health, Kind.daily_total, Area.activity),
            new GoalMetric("mindful_minutes", "Mindful minutes", "min", Source.health, Kind.daily_total, Area.mind),
            new GoalMetric("screen_time", "Screen time", "min", Source.health, Kind.daily_total, Area.mind),
            new GoalMetric("sleep", "Sleep", "sleep_min", Source.sleep, Kind.daily_total, Area.recovery),
            new GoalMetric("protein_g", "Protein", "g", Source.nutrition, Kind.daily_total, Area.nutrition),
            new GoalMetric("kcal", "Calories", "kcal", Source.nutrition, Kind.daily_total, Area.nutrition),
            new GoalMetric("fibre_g", "Fibre", "g", Source.nutrition, Kind.daily_total, Area.nutrition),
            new GoalMetric("water_ml", "Water", "ml", Source.nutrition, Kind.daily_total, Area.nutrition),
            new GoalMetric("workouts", "Workouts", "sessions", Source.workouts, Kind.weekly_count, Area.training),
            new GoalMetric(
                    "resting_heart_rate", "Resting heart rate", "bpm", Source.health, Kind.target_value, Area.heart),
            new GoalMetric("hrv", "Heart rate variability", "ms", Source.health, Kind.target_value, Area.heart),
            new GoalMetric("vo2_max", "Cardio fitness", "mL/kg/min", Source.health, Kind.target_value, Area.heart),
            new GoalMetric("weight", "Weight", "kg", Source.health, Kind.target_value, Area.body),
            new GoalMetric("body_fat", "Body fat", "%", Source.health, Kind.target_value, Area.body),
            new GoalMetric("waist", "Waist", "cm", Source.health, Kind.target_value, Area.body));

    public static final Map<String, GoalMetric> BY_CODE =
            ALL.stream().collect(Collectors.toUnmodifiableMap(GoalMetric::code, Function.identity()));
}
