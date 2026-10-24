package com.vitallink.api.goals;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Goal bodies. Responses match packages/api/src/types.ts (Goal, TrackedGoal, GoalsOverview). */
public final class GoalDtos {

    private GoalDtos() {}

    public enum Area {
        activity,
        training,
        nutrition,
        recovery,
        body,
        heart,
        mind
    }

    public enum Kind {
        daily_total,
        weekly_count,
        target_value
    }

    public enum Comparator {
        at_least,
        at_most
    }

    public enum Lifecycle {
        active,
        paused,
        achieved,
        abandoned
    }

    /**
     * A new goal. {@code kind}, {@code area} and {@code short_title} default from the metric; target_value
     * goals take their baseline from the latest reading unless one is given.
     */
    public record GoalIn(
            @NotBlank @Size(max = 80) String title,
            @Size(min = 1, max = 40) String shortTitle,
            @NotBlank @Size(max = 40) String metric,
            Area area,
            Comparator comparator,

            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("99999999")
            Double target,

            @DecimalMin("0") @DecimalMax("99999999") Double baseline,
            LocalDate startsOn,
            LocalDate endsOn) {

        public GoalIn {
            title = title == null ? null : title.strip();
            shortTitle = shortTitle == null ? null : shortTitle.strip();
        }
    }

    /** The stored goal, for editing screens. */
    public record GoalOut(
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
            OffsetDateTime achievedAt) {}

    /** {@code Goal} in types.ts: today's progress (dashboard and Activity). */
    public record GoalProgress(String id, String metric, String label, double current, double target, String unit) {}

    /** {@code TrackedGoal}: a goal with its computed status and progress text. */
    public record TrackedGoal(
            UUID id,
            String title,
            String shortTitle,
            String area,
            String status,
            double progress,
            String detail,
            String shortDetail) {}

    public record Streak(int days, String label) {}

    public record Achievement(String title, OffsetDateTime achievedAt) {}

    public record GoalsOverview(
            List<TrackedGoal> goals,
            int achievedThisMonth,
            Streak longestStreak,
            List<List<Boolean>> consistency,
            List<Achievement> recent) {}

    /** {@code {label, days}} for the dashboard's and Activity's streak cards. */
    public record StreakOut(String label, int days) {}
}
