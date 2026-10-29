package com.vitallink.api.records;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Records and labs bodies. Responses match packages/api/src/types.ts (HealthRecord, RecordsOverview, LabPanel). */
public final class RecordDtos {

    private RecordDtos() {}

    public enum RecordType {
        lab,
        prescription,
        imaging,
        visit,
        vaccination,
        allergy,
        procedure,
        upload
    }

    public enum Interpretation {
        normal,
        borderline,
        abnormal
    }

    static String strip(String s) {
        return s == null ? null : s.strip();
    }

    public record RecordIn(
            @NotBlank @Size(max = 120) String title,
            @NotNull RecordType type,
            @Size(max = 120) String source,
            @Size(max = 200) String note,
            @NotNull LocalDate date) {

        public RecordIn {
            title = strip(title);
            source = source == null ? "" : source.strip();
            note = note == null || note.isBlank() ? null : note.strip();
        }
    }

    /** {@code status}: none (no file), pending (upload not confirmed yet), attached. */
    public record FileInfo(String name, Integer sizeKb, String contentType, String status) {}

    /**
     * @param date the record's date, as local midnight in the owner's time zone
     * @param sharedWith who can see records right now (the newest live share that covers records), or null
     */
    public record HealthRecord(
            UUID id,
            String title,
            RecordType type,
            String source,
            String note,
            OffsetDateTime date,
            String sharedWith,
            UUID panelId,
            FileInfo file) {}

    /** {@code id} is the share's, for Revoke (DELETE /me/shares/{id}). */
    public record RecordShare(UUID id, String grantee, Integer records, String scope, OffsetDateTime expiresAt) {}

    public record RecordsOverview(
            Map<RecordType, Integer> counts, List<HealthRecord> recent, List<RecordShare> shares) {}

    public record UploadIn(
            @NotBlank @Size(max = 200) String fileName,
            @NotBlank @Size(max = 100) String contentType,
            @NotNull @Positive @Max(26214400) Long size) {

        public UploadIn {
            fileName = strip(fileName);
            contentType = contentType == null ? null : contentType.strip().toLowerCase();
        }
    }

    public record UploadOut(String uploadUrl, String method, Map<String, String> headers, OffsetDateTime expiresAt) {}

    public record DownloadOut(String url, OffsetDateTime expiresAt) {}

    // ---------------------------------------------------------------- labs

    public record LabResultIn(
            @NotBlank @Size(max = 80) String name,
            @NotNull Double value,
            @Size(max = 30) String unit,
            @NotNull Double low,
            @NotNull Double high,
            @Min(0) @Max(4) Integer decimals,
            Interpretation interpretation,
            Boolean onReport) {

        public LabResultIn {
            name = strip(name);
            unit = unit == null ? "" : unit.strip();
            decimals = decimals == null ? 1 : decimals;
            onReport = onReport == null || onReport;
        }
    }

    /**
     * A lab panel typed in from a report (decision D6). Without {@code record_id} a lab record is created
     * for it, so it shows in the records list; with one, the panel describes that record's document.
     */
    public record LabPanelIn(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 20) String shortName,
            @Size(max = 120) String facility,
            @Size(max = 120) String laboratory,
            @Size(max = 80) String orderedBy,
            @NotNull OffsetDateTime collectedAt,
            OffsetDateTime reportedAt,
            @Size(max = 500) String note,
            UUID recordId,
            @NotEmpty @Size(max = 60) List<@Valid LabResultIn> results) {

        public LabPanelIn {
            name = strip(name);
            shortName = strip(shortName);
            facility = facility == null ? "" : facility.strip();
            laboratory = laboratory == null ? "" : laboratory.strip();
            orderedBy = orderedBy == null ? "" : orderedBy.strip();
            note = note == null || note.isBlank() ? null : note.strip();
            reportedAt = reportedAt == null ? collectedAt : reportedAt;
        }
    }

    /**
     * @param previous the same test in the latest earlier panel, or null
     * @param interpretation the lab's own reading when given; otherwise computed from the range (just
     *     outside = borderline)
     */
    public record LabResult(
            String name,
            double value,
            String unit,
            double low,
            double high,
            Double previous,
            int decimals,
            Interpretation interpretation) {}

    public record LabFile(String name, int sizeKb) {}

    public record LabPanel(
            UUID id,
            UUID recordId,
            String name,
            String shortName,
            String facility,
            String laboratory,
            String orderedBy,
            OffsetDateTime collectedAt,
            OffsetDateTime reportedAt,
            OffsetDateTime addedAt,
            LabFile file,
            List<LabResult> results,
            List<String> reportResults,
            String note) {}

    /** The dashboard's lab card: the latest panel's first result. */
    public record LatestLab(UUID panelId, String panel, OffsetDateTime collectedAt, LabResult result) {}
}
