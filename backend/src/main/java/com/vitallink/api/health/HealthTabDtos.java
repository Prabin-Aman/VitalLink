package com.vitallink.api.health;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * My Health payloads. They match packages/api/src/types.ts (HealthOverview, VitalsTab … DigitalTab). A
 * reading that has no data is left out, and a block with nothing behind it is null: screens show "—"
 * instead of a made-up number.
 */
public final class HealthTabDtos {

    private HealthTabDtos() {}

    /**
     * {@code Reading} in types.ts. {@code metric} is the screen's key (resting_hr, spo2 …), {@code unit}
     * the label it shows ("BPM", "duration" for minutes shown as hours and minutes), {@code period} set
     * for aggregates without one timestamp.
     */
    public record TabReading(
            String metric,
            double value,
            Double value2,
            String unit,
            OffsetDateTime at,
            String period,
            String assessment,
            String shortAssessment) {

        static TabReading at(String metric, double value, String unit, OffsetDateTime at, String assessment) {
            return new TabReading(metric, value, null, unit, at, null, assessment, null);
        }

        static TabReading period(String metric, double value, String unit, String period, String assessment) {
            return new TabReading(metric, value, null, unit, null, period, assessment, null);
        }

        TabReading withShort(String s) {
            return new TabReading(metric, value, value2, unit, at, period, assessment, s);
        }
    }

    public record Insight(String text, String tone) {}

    public record Rings(int[] move, int[] exercise, int[] stand, String source, OffsetDateTime syncedAt) {}

    public record Section(String title, String sources, List<String> metrics) {}

    public record Source(String name, String data, boolean ok) {}

    public record HealthOverview(
            Rings rings,
            List<Section> sections,
            List<TabReading> readings,
            List<Source> sources,
            List<Insight> insights) {}

    public record Bp30(List<Integer> systolic, List<Integer> diastolic) {}

    public record DeviceData(String device, String data) {}

    public record VitalsTab(
            OffsetDateTime measuredAt, List<TabReading> readings, Bp30 bp30, List<DeviceData> sources) {}

    public record SleepNight(int deep, int core, int rem, int awake) {}

    public record LastNight(
            int asleepMin,
            OffsetDateTime start,
            OffsetDateTime end,
            int goalMin,
            int deep,
            int rem,
            int awake,
            int wakeups,
            int score) {}

    public record SleepConsistency(String bedtime, String wake, int varianceMin, String steadiest) {}

    public record SleepTab(
            LastNight last, List<SleepNight> week, SleepConsistency consistency, List<Insight> insights) {}

    public record Resting(
            int value,

            @com.fasterxml.jackson.annotation.JsonProperty("delta_90d")
            int delta90d,

            List<Double> trend) {}

    public record Zone(int zone, String name, String range, int minutes) {}

    public record Alert(String label, String value) {}

    public record HeartTab(Resting resting, List<TabReading> readings, List<Zone> zones, List<Alert> alerts) {}

    public record Aerobic(double current, double sixMonthsAgo, String category) {}

    public record RespiratoryTab(
            List<TabReading> readings, List<Integer> overnightSpo2, Aerobic aerobic, List<Alert> alerts) {}

    public record Weekly(List<Double> weight, List<Double> bodyFat) {}

    public record Measurement(String name, double current, double previous, String unit, OffsetDateTime takenAt) {}

    public record BodyFatGoal(double current, double start, double target, Integer weeksToGo) {}

    public record BodyTab(
            List<TabReading> readings,
            Weekly weekly,
            double muscleChangeKg,
            List<Measurement> measurements,
            BodyFatGoal bodyFatGoal) {}

    public record Mindful(int sessions, int minutes, int goal, int longest) {}

    public record MentalTab(List<String> mood, List<TabReading> readings, Mindful mindful, List<Insight> insights) {}

    public record DigitalDay(double dayH, double lateH) {}

    public record Category(String name, String apps, int minutes) {}

    public record DeepSleep(int heavyMin, int lightMin, int heavyNights, int lightNights) {}

    public record Limit(String name, String detail, boolean on) {}

    public record DigitalTab(
            int averageMin,
            int changeMin,
            List<DigitalDay> week,
            List<TabReading> readings,
            List<Category> categories,
            DeepSleep deepSleep,
            List<Limit> limits) {}
}
