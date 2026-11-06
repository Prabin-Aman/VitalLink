package com.vitallink.api.care;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.care.CareDtos.Appointment;
import com.vitallink.api.care.CareDtos.AppointmentIn;
import com.vitallink.api.care.CareDtos.Condition;
import com.vitallink.api.care.CareDtos.ConditionIn;
import com.vitallink.api.care.CareDtos.Contact;
import com.vitallink.api.care.CareDtos.ContactIn;
import com.vitallink.api.care.CareDtos.DoseIn;
import com.vitallink.api.care.CareDtos.DoseLogOut;
import com.vitallink.api.care.CareDtos.EmergencyProfile;
import com.vitallink.api.care.CareDtos.MedicationDetail;
import com.vitallink.api.care.CareDtos.MedicationIn;
import com.vitallink.api.care.CareDtos.MedicationsOverview;
import com.vitallink.api.care.CareDtos.TodayDose;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Medications and doses, appointments, conditions, the emergency profile and contacts, and doctor search. */
@RestController
@RequestMapping("/v1/me")
@Tag(name = "care")
public class CareController {

    private final CareService service;
    private final DoctorSearch doctors;

    public CareController(CareService service, DoctorSearch doctors) {
        this.service = service;
        this.doctors = doctors;
    }

    @GetMapping("/doctors/search")
    @Operation(
            summary =
                    "Doctors in Texas from the public NPI Registry, by name, specialty or both, optionally in a city.")
    public List<DoctorSearch.Doctor> searchDoctors(
            CurrentUser me,
            @RequestParam(required = false) @jakarta.validation.constraints.Size(max = 60) String name,
            @RequestParam(required = false) @jakarta.validation.constraints.Size(max = 60) String specialty,
            @RequestParam(required = false) @jakarta.validation.constraints.Size(max = 60) String city) {
        boolean named = name != null && name.strip().length() >= 2;
        boolean special = specialty != null && specialty.strip().length() >= 2;
        if (!named && !special) {
            throw com.vitallink.api.core.ApiError.invalid("Enter a name or a specialty, at least 2 letters.");
        }
        return doctors.search(name, specialty, city);
    }

    // ---------------------------------------------------------------- medications

    @GetMapping("/medications")
    @Operation(summary = "Active prescriptions, each with the dose that decides its state, and today's dose count.")
    public MedicationsOverview medications(CurrentUser me) {
        return service.overview(me.id(), me.user().timezone());
    }

    @GetMapping("/medications/today")
    @Operation(summary = "Every dose scheduled today (dashboard): taken, skipped, due or missed.")
    public List<TodayDose> today(CurrentUser me) {
        return service.today(me.id(), me.user().timezone());
    }

    @PostMapping("/medications")
    @ResponseStatus(HttpStatus.CREATED)
    public MedicationDetail createMedication(CurrentUser me, @Valid @RequestBody MedicationIn body) {
        return service.create(me, body);
    }

    @GetMapping("/medications/{id}")
    public MedicationDetail medication(CurrentUser me, @PathVariable UUID id) {
        return service.detail(me, id);
    }

    @PatchMapping("/medications/{id}")
    @Operation(summary = "Change fields or the schedule; status \"stopped\" stops it (history stays).")
    public MedicationDetail updateMedication(
            CurrentUser me, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return service.update(me, id, body);
    }

    @DeleteMapping("/medications/{id}")
    public ResponseEntity<Void> deleteMedication(CurrentUser me, @PathVariable UUID id) {
        service.delete(me, id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/medications/{id}/doses/{scheduledFor}")
    @Operation(summary = "Mark a scheduled dose taken or skipped. Idempotent: logging it again updates it.")
    public DoseLogOut logDose(
            CurrentUser me,
            @PathVariable UUID id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime scheduledFor,
            @Valid @RequestBody DoseIn body) {
        return service.logDose(me, id, scheduledFor, body);
    }

    @DeleteMapping("/medications/{id}/doses/{scheduledFor}")
    public ResponseEntity<Void> undoDose(
            CurrentUser me,
            @PathVariable UUID id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime scheduledFor) {
        service.undoDose(me, id, scheduledFor);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- appointments

    @GetMapping("/appointments")
    @Operation(summary = "Appointments in start order; upcoming=true leaves out past and cancelled ones.")
    public List<Appointment> appointments(
            CurrentUser me,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "false") boolean upcoming,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return service.appointments(me.id(), me.user().timezone(), from, to, upcoming, limit);
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    public Appointment createAppointment(CurrentUser me, @Valid @RequestBody AppointmentIn body) {
        return service.createAppointment(me, body);
    }

    @PatchMapping("/appointments/{id}")
    @Operation(summary = "Change details, confirm (state \"confirmed\") or cancel (state \"cancelled\").")
    public Appointment updateAppointment(CurrentUser me, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return service.updateAppointment(me, id, body);
    }

    @DeleteMapping("/appointments/{id}")
    public ResponseEntity<Void> deleteAppointment(CurrentUser me, @PathVariable UUID id) {
        service.deleteAppointment(me, id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- conditions

    @GetMapping("/conditions")
    public List<Condition> conditions(CurrentUser me) {
        return service.conditions(me.id());
    }

    @PostMapping("/conditions")
    @ResponseStatus(HttpStatus.CREATED)
    public Condition addCondition(CurrentUser me, @Valid @RequestBody ConditionIn body) {
        return service.addCondition(me, body);
    }

    @DeleteMapping("/conditions/{id}")
    public ResponseEntity<Void> deleteCondition(CurrentUser me, @PathVariable UUID id) {
        service.deleteCondition(me, id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- emergency medical ID

    @GetMapping("/emergency-profile")
    @Operation(summary = "The emergency medical ID: profile, allergies, conditions, active medications, contacts.")
    public EmergencyProfile emergency(CurrentUser me) {
        return service.emergency(me);
    }

    @PatchMapping("/emergency-profile")
    @Operation(summary = "Organ donor preference, preferred hospital, notes and the lock-screen switch.")
    public EmergencyProfile updateEmergency(CurrentUser me, @RequestBody Map<String, Object> body) {
        return service.updateEmergency(me, body);
    }

    @GetMapping("/emergency-contacts")
    public List<Contact> contacts(CurrentUser me) {
        return service.contacts(me.id());
    }

    @PostMapping("/emergency-contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Up to 5 contacts; the first one, or one marked primary, becomes the primary contact.")
    public Contact addContact(CurrentUser me, @Valid @RequestBody ContactIn body) {
        return service.addContact(me, body);
    }

    @PatchMapping("/emergency-contacts/{id}")
    public Contact updateContact(CurrentUser me, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return service.updateContact(me, id, body);
    }

    @DeleteMapping("/emergency-contacts/{id}")
    public ResponseEntity<Void> deleteContact(CurrentUser me, @PathVariable UUID id) {
        service.deleteContact(me, id);
        return ResponseEntity.noContent().build();
    }
}
