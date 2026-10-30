package com.vitallink.api.health;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Health request/response bodies. Responses match packages/api/src/types.ts (Dashboard, MetricSummary,
 * Series, Reading, Device).
 */
public final class HealthDtos {

    private HealthDtos() {}

    public enum DeviceKind {
        phone,
        wearable,
        home_monitor,
        continuous
    }

    public enum Interval {
        hour,
        day,
        week,
        month
    }

    public enum RangeStatus {
        normal,
        borderline,
        high,
        low,
        no_data;

        @JsonValue
        public String json() {
            return this == no_data ? "no-data" : name();
        }
    }

    // ---------------------------------------------------------------- ingest

    public record DeviceIn(
            @NotBlank @Size(max = 200) String externalId,
            @NotBlank @Size(max = 120) String name,
            @NotNull DeviceKind kind,
            @Size(max = 60) List<@NotBlank @Size(max = 40) String> metrics) {

        public DeviceIn {
            name = name == null ? null : name.strip();
            metrics = metrics == null ? List.of() : metrics;
        }
    }

    public record BucketIn(
            @NotBlank @Size(max = 40) String metric,
            @NotNull OffsetDateTime start,
            @NotNull Double value,
            @Min(1) @Max(32767) Integer samples) {

        public BucketIn {
            samples = samples == null ? 1 : samples;
        }
    }

    /** The 5,000 cap is checked in the service so an oversized batch gets 413, not 422. */
    public record IngestIn(
            @NotNull UUID idempotencyKey,

            @NotNull @Pattern(regexp = "healthkit|health_connect|bluetooth")
            String platform,

            @Size(max = 20) List<@Valid DeviceIn> devices,
            @NotEmpty List<@Valid BucketIn> buckets) {

        public IngestIn {
            devices = devices == null ? List.of() : devices;
        }
    }

    /**
     * POST /me/health/import: what a file added. {@code format} is apple or csv; {@code records} are the samples
     * in the chosen period, {@code readings} the 5-minute buckets they made; {@code problems} lists the first
     * few things that were skipped.
     */
    public record ImportOut(
            String format,
            int records,
            int readings,
            int inserted,
            int updated,
            int unchanged,
            int skipped,
            List<String> problems,
            OffsetDateTime from,
            OffsetDateTime to) {}

    public record IngestOut(long batchId, int submitted, int inserted, int updated, int unchanged, boolean replayed) {}

    /** One reading typed in by the user (weight, mood, a cuff's blood pressure…). {@code at} defaults to now. */
    public record ManualIn(
            @NotBlank @Size(max = 40) String metric,
            @NotNull Double value,
            OffsetDateTime at) {}

    // ---------------------------------------------------------------- reads

    public record SeriesPoint(OffsetDateTime bucket, double value, long samples) {}

    public record Series(String metric, String unit, Interval interval, List<SeriesPoint> points) {}

    public record BloodPressureSeries(Series systolic, Series diastolic) {}

    public record MetricSummary(
            String metric,
            String unit,
            Double latest,
            OffsetDateTime latestAt,
            @JsonProperty("avg_7d") Double avg7d,
            RangeStatus range) {}

    public record Dashboard(
            OffsetDateTime generatedAt,
            Integer healthScore,
            @JsonProperty("health_score_delta_7d") Integer healthScoreDelta7d,
            List<MetricSummary> metrics) {}

    public record ReadingOut(
            String metric, double value, Double value2, String unit, OffsetDateTime at, String assessment) {}

    /** One phone platform's uploads: the latest, and what arrived in the last 7 days (`*_week`). */
    public record SyncPlatform(
            String platform,
            OffsetDateTime lastUploadAt,
            int uploadsWeek,
            long readingsWeek,
            long changedWeek,
            int devices) {}

    /** One upload (an ingest batch). Batches are kept 30 days. */
    public record SyncUpload(
            long id, OffsetDateTime at, String platform, int submitted, int inserted, int updated, int unchanged) {}

    /** GET /me/sync: how the phone app is getting data in. Platforms with no upload yet are left out. */
    public record SyncStatus(List<SyncPlatform> platforms, List<SyncUpload> recent) {}

    public record DeviceOut(
            UUID id,
            String name,
            DeviceKind kind,
            String icon,
            OffsetDateTime lastSyncAt,
            String status,
            List<String> data,
            List<String> shortData) {}
}
