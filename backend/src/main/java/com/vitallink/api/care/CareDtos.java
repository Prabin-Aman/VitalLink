package com.vitallink.api.care;

import com.vitallink.api.core.ApiError;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Care bodies. Responses match packages/api/src/types.ts (Medication, Prescription, MedicationsOverview,
 * Appointment, EmergencyProfile).
 */
public final class CareDtos {

    private CareDtos() {}

    public enum DoseForm {
        tablet,
        capsule,
        liquid,
        inhaler,
        injection,
        topical,
        other
    }

    public enum DoseStatus {
        taken,
        skipped
    }

    public enum VisitMode {
        in_person,
        telehealth
    }

    public enum AppointmentState {
        scheduled,
        confirmed,
        cancelled
    }

    public enum ConditionKind {
        chronic,
        allergy
    }

    public enum OrganDonor {
        yes,
        no,
        undecided
    }

    static final String E164 = "^\\+[1-9][0-9]{6,14}$";

    static String strip(String s) {
        return s == null ? null : s.strip();
    }

    // ---------------------------------------------------------------- medications

    /**
     * A medication with the times of day it's taken.
     *
     * @param times local times, e.g. "08:00"
     * @param days ISO weekdays 1 (Monday) – 7 (Sunday); empty or null = every day
     */
    public record MedicationIn(
            @NotBlank @Size(max = 80) String name,
            @NotNull DoseForm form,
            @NotBlank @Size(max = 40) String strength,
            @Size(max = 200) String instructions,
            @Size(max = 80) String prescriber,
            @Min(0) @Max(99) Integer refillsLeft,
            LocalDate refillDueOn,
            LocalDate startedOn,
            @NotEmpty @Size(max = 8) List<@NotNull LocalTime> times,
            @Size(max = 7) List<@NotNull @Min(1) @Max(7) Integer> days) {

        public MedicationIn {
            name = strip(name);
            strength = strip(strength);
            instructions = instructions == null ? "" : instructions.strip();
            prescriber = prescriber == null ? "" : prescriber.strip();
            refillsLeft = refillsLeft == null ? 0 : refillsLeft;
            days = days == null ? List.of() : days;
            if (times != null && times.stream().anyMatch(t -> t != null && (t.getSecond() != 0 || t.getNano() != 0))) {
                throw ApiError.invalid("Dose times are whole minutes, e.g. 08:00.");
            }
        }
    }

    /** The dose that decides a prescription row's state: last taken, next due, or missed. */
    public record DoseState(OffsetDateTime at, String status) {}

    public record Prescription(
            UUID id,
            String name,
            String form,
            String strength,
            String frequency,
            String prescriber,
            int refillsLeft,
            DoseState dose,
            boolean refillDue) {}

    public record DoseCount(int taken, int total) {}

    public record MedicationsOverview(List<Prescription> prescriptions, DoseCount dosesToday) {}

    /** One scheduled dose today (dashboard). {@code status}: taken, skipped, due or missed. */
    public record TodayDose(
            String id, UUID medicationId, String name, String dosage, String status, OffsetDateTime at) {}

    public record ScheduleOut(LocalTime time, List<Integer> days) {}

    public record DoseLogOut(OffsetDateTime scheduledFor, DoseStatus status, OffsetDateTime loggedAt) {}

    public record MedicationDetail(
            UUID id,
            String name,
            DoseForm form,
            String strength,
            String instructions,
            String prescriber,
            int refillsLeft,
            LocalDate refillDueOn,
            String status,
            LocalDate startedOn,
            LocalDate stoppedOn,
            String frequency,
            List<ScheduleOut> schedule,
            List<DoseLogOut> recentDoses) {}

    public record DoseIn(@NotNull DoseStatus status) {}

    // ---------------------------------------------------------------- appointments

    public record AppointmentIn(
            @NotBlank @Size(max = 80) String clinician,
            @Size(max = 80) String specialty,
            @NotNull OffsetDateTime startsAt,
            @Min(5) @Max(600) Integer durationMin,
            @NotNull VisitMode mode,
            @Size(max = 200) String location,
            AppointmentState state,
            @Size(max = 1000) String notes) {

        public AppointmentIn {
            clinician = strip(clinician);
            specialty = specialty == null ? "" : specialty.strip();
            location = location == null ? "" : location.strip();
            durationMin = durationMin == null ? 30 : durationMin;
            state = state == null ? AppointmentState.scheduled : state;
        }
    }

    /** {@code status}: confirmed, pending (scheduled, not confirmed yet), cancelled, or completed (past). */
    public record Appointment(
            UUID id,
            String clinician,
            String specialty,
            OffsetDateTime startsAt,
            int durationMin,
            VisitMode mode,
            String location,
            String status,
            String notes) {}

    // ---------------------------------------------------------------- conditions and emergency

    public record ConditionIn(
            @NotBlank @Size(max = 80) String label,
            @NotNull ConditionKind kind,
            @Size(max = 80) String note,
            LocalDate notedOn) {

        public ConditionIn {
            label = strip(label);
            note = note == null || note.isBlank() ? null : note.strip();
        }
    }

    public record Condition(UUID id, String label, ConditionKind kind, String note, LocalDate notedOn) {}

    public record ContactIn(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 40) String relation,
            @NotBlank @Pattern(regexp = E164) String phone,
            Boolean primary) {

        public ContactIn {
            name = strip(name);
            relation = strip(relation);
            primary = primary != null && primary;
        }
    }

    public record Contact(UUID id, String name, String relation, String phone, boolean primary) {}

    /**
     * The emergency medical ID, assembled from the profile, conditions and active medications. The phone
     * renders it (and its QR code) without a server call (decision D2).
     */
    public record EmergencyProfile(
            String name,
            Integer age,
            String sex,
            String city,
            List<String> allergies,
            List<String> conditions,
            List<String> medications,
            String organDonor,
            String preferredHospital,
            String notes,
            boolean showOnLockScreen,
            List<Contact> contacts) {}
}
