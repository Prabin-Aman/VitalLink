package com.vitallink.api.account;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Account request and response bodies. Responses match packages/api/src/types.ts (Me, AccountProfile,
 * Targets). JSON names are snake_case (Jackson naming strategy); unknown input fields are refused, so a
 * client can never set id, email or member_since (mass assignment). Email changes go through Supabase Auth.
 */
public final class AccountDtos {

    private AccountDtos() {}

    public enum Sex {
        female,
        male,
        other,
        undisclosed
    }

    public enum Units {
        metric,
        imperial
    }

    public enum Theme {
        light,
        dark,
        system
    }

    /** {@code avatarUrl}: a short-lived link to the profile photo, or null. */
    public record Me(UUID id, String email, String displayName, String timezone, String avatarUrl, Units units) {}

    public record Account(
            String firstName,
            String lastName,
            String email,
            String phone,
            String zip,
            String city,
            String language,
            Double heightCm,
            OffsetDateTime memberSince,
            Units units,
            boolean twoFactor,
            Theme theme,
            LocalDate dateOfBirth,
            Sex sex,
            String timezone,
            String avatarUrl) {}

    /** Daily targets (steps, move, exercise, stand, sleep): the one source for goals on every screen. */
    public record Targets(
            int dailyStepsGoal, int moveKcalGoal, int exerciseMinGoal, int standHoursGoal, int sleepGoalMin) {}

    /** Every field optional; null leaves it unchanged. */
    public record TargetsPatch(
            @Min(1000) @Max(100000) Integer dailyStepsGoal,
            @Min(50) @Max(5000) Integer moveKcalGoal,
            @Min(5) @Max(600) Integer exerciseMinGoal,
            @Min(1) @Max(24) Integer standHoursGoal,
            @Min(180) @Max(900) Integer sleepGoalMin) {}
}
