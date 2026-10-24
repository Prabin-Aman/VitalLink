package com.vitallink.api.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.TreeSet;

/** Runs of consecutive days (workout streaks, step-goal streaks, move-ring streaks). */
public final class Streaks {

    private Streaks() {}

    /** The run ending today, or yesterday when today hasn't counted yet (today isn't over). */
    public static int current(Set<LocalDate> days, LocalDate today) {
        int current = 0;
        LocalDate day = days.contains(today) ? today : today.minusDays(1);
        while (days.contains(day)) {
            current++;
            day = day.minusDays(1);
        }
        return current;
    }

    /** The longest run anywhere in {@code days}. */
    public static int longest(Set<LocalDate> days) {
        int longest = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate d : new TreeSet<>(days)) {
            run = previous != null && ChronoUnit.DAYS.between(previous, d) == 1 ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = d;
        }
        return longest;
    }
}
