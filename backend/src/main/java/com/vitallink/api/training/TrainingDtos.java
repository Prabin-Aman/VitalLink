package com.vitallink.api.training;

import com.vitallink.api.core.ApiError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Training bodies. Responses match packages/api/src/types.ts (Workout, Training and its parts). */
public final class TrainingDtos {

    private TrainingDtos() {}

    public enum WorkoutType {
        walk,
        run,
        cycle,
        strength,
        swim,
        hiit,
        yoga,
        other
    }

    public enum SessionKind {
        cardio,
        strength,
        mobility
    }

    public enum MuscleGroup {
        chest,
        back,
        shoulders,
        arms,
        core,
        legs,
        glutes,
        full_body
    }

    static String strip(String s) {
        return s == null ? null : s.strip();
    }

    public record SetIn(
            @NotBlank @Size(max = 80) String exercise,
            @Min(1) @Max(1000) Integer reps,
            @DecimalMin("0") @DecimalMax("1000") Double weightKg,
            @Min(1) @Max(86400) Integer durationS) {

        public SetIn {
            exercise = strip(exercise);
            if (reps == null && durationS == null) {
                throw ApiError.invalid("A set needs reps or a duration.");
            }
        }
    }

    public record WorkoutIn(
            @NotNull WorkoutType type,
            SessionKind kind,
            @NotBlank @Size(max = 80) String title,
            @NotNull OffsetDateTime startedAt,
            @NotNull @Min(1) @Max(86400) Integer durationS,
            @DecimalMin("0") @DecimalMax("1000000") Double distanceM,
            @Min(0) @Max(20000) Integer kcal,
            @Min(25) @Max(250) Integer avgHr,
            @Min(1) @Max(10) Integer rpe,
            @Size(min = 5, max = 5) List<@NotNull @PositiveOrZero Integer> zoneSeconds,
            @Size(max = 200) String focus,
            String source,
            @Size(min = 1, max = 200) String externalId,
            UUID planSessionId,
            @Size(max = 200) List<@Valid SetIn> sets) {

        public WorkoutIn {
            title = strip(title);
            focus = strip(focus);
            source = source == null ? "manual" : source;
            sets = sets == null ? List.of() : sets;
            if (!List.of("manual", "healthkit", "health_connect").contains(source)) {
                throw ApiError.invalid("source must be manual, healthkit or health_connect.");
            }
            // Synced workouts carry the platform's id, so a re-sync updates instead of duplicating.
            if (source.equals("manual") != (externalId == null)) {
                throw ApiError.invalid("Synced workouts need an external_id; manual ones must not have one.");
            }
        }
    }

    /** {@code Workout} in types.ts (dashboard and Activity lists). */
    public record WorkoutOut(
            UUID id,
            WorkoutType type,
            String title,
            OffsetDateTime startedAt,
            int durationS,
            Double distanceM,
            Integer kcal,
            Integer avgHr) {}

    public record SetOut(String exercise, int setNo, Integer reps, Double weightKg, Integer durationS) {}

    public record WorkoutDetail(
            UUID id,
            WorkoutType type,
            String title,
            OffsetDateTime startedAt,
            int durationS,
            Double distanceM,
            Integer kcal,
            Integer avgHr,
            SessionKind kind,
            Integer rpe,
            List<Integer> zoneSeconds,
            String focus,
            String source,
            List<SetOut> sets) {}

    public record PR(String exercise, double weightKg) {}

    public record TrainingSession(
            UUID id,
            WorkoutType type,
            SessionKind kind,
            String title,
            OffsetDateTime startedAt,
            int durationS,
            Integer exercises,
            Integer sets,
            Double volumeKg,
            Double distanceM,

            @com.fasterxml.jackson.annotation.JsonProperty("pace_s_per_km")
            Integer paceSPerKm,

            Integer kcal,
            Integer avgHr,
            Integer rpe,
            int zone,
            String focus,
            PR pr) {}

    public record TrainingWeek(
            int sessions,
            int targetSessions,
            int minutes,
            double volumeKg,
            int activeKcal,
            int streakDays,
            int longestStreakDays,
            String source,
            List<Map<SessionKind, Integer>> load) {}

    public record MuscleGroupSets(String group, int sets) {}

    public record Muscles(int days, List<MuscleGroupSets> groups, String note) {}

    public record PersonalRecord(
            String exercise, OffsetDateTime achievedAt, Double weightKg, Integer reps, Integer timeS) {}

    public record PlannedSession(
            UUID id, String title, String kind, OffsetDateTime startsAt, int exercises, int minutes) {}

    public record Training(
            int totalSessions,
            TrainingWeek week,
            List<TrainingSession> sessions,
            Muscles muscles,
            List<PersonalRecord> records,
            PlannedSession next) {}

    public record PlanSessionIn(
            @NotBlank @Size(max = 80) String title,
            @NotNull SessionKind kind,
            @NotNull OffsetDateTime scheduledFor,
            @Min(0) @Max(50) Integer exercises,
            @NotNull @Min(1) @Max(600) Integer minutes) {

        public PlanSessionIn {
            title = strip(title);
            exercises = exercises == null ? 0 : exercises;
        }
    }

    public record PlanIn(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Min(1) @Max(14) Integer weeklyTargetSessions,
            @Size(max = 100) List<@Valid PlanSessionIn> sessions) {

        public PlanIn {
            name = strip(name);
            sessions = sessions == null ? List.of() : sessions;
        }
    }

    public record PlanOut(
            UUID id, String name, String status, int weeklyTargetSessions, List<PlannedSession> sessions) {}

    public record ExerciseOut(int id, String name, MuscleGroup primaryMuscle) {}
}
