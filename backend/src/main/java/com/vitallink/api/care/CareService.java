package com.vitallink.api.care;

import com.vitallink.api.account.AccountDtos.Sex;
import com.vitallink.api.account.AccountService;
import com.vitallink.api.account.AccountService.Profile;
import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.care.CareDtos.Appointment;
import com.vitallink.api.care.CareDtos.AppointmentIn;
import com.vitallink.api.care.CareDtos.AppointmentState;
import com.vitallink.api.care.CareDtos.Condition;
import com.vitallink.api.care.CareDtos.ConditionIn;
import com.vitallink.api.care.CareDtos.ConditionKind;
import com.vitallink.api.care.CareDtos.Contact;
import com.vitallink.api.care.CareDtos.ContactIn;
import com.vitallink.api.care.CareDtos.DoseCount;
import com.vitallink.api.care.CareDtos.DoseForm;
import com.vitallink.api.care.CareDtos.DoseIn;
import com.vitallink.api.care.CareDtos.DoseLogOut;
import com.vitallink.api.care.CareDtos.DoseState;
import com.vitallink.api.care.CareDtos.DoseStatus;
import com.vitallink.api.care.CareDtos.EmergencyProfile;
import com.vitallink.api.care.CareDtos.MedicationDetail;
import com.vitallink.api.care.CareDtos.MedicationIn;
import com.vitallink.api.care.CareDtos.MedicationsOverview;
import com.vitallink.api.care.CareDtos.OrganDonor;
import com.vitallink.api.care.CareDtos.Prescription;
import com.vitallink.api.care.CareDtos.ScheduleOut;
import com.vitallink.api.care.CareDtos.TodayDose;
import com.vitallink.api.care.CareDtos.VisitMode;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Clock;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.web.Patch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Care rules: medications and their doses, appointments, conditions, the emergency medical ID.
 *
 * <p>A dose's "due" and "missed" states are computed from the schedule and the log: a scheduled dose with
 * no log is due until two hours after its time, then missed. Reminders are local notifications on the
 * phone (decision D9); the nightly job adds a feed entry for missed doses.
 */
@Service
public class CareService {

    static final Duration GRACE = Duration.ofHours(2);
    static final int MAX_CONTACTS = 5;
    private static final Pattern E164 = Pattern.compile(CareDtos.E164);

    private final JdbcClient jdbc;
    private final AccountService accounts;

    public CareService(JdbcClient jdbc, AccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    // ---------------------------------------------------------------- medication rows and schedules

    record Slot(LocalTime time, int daysMask) {
        boolean on(DayOfWeek d) {
            return (daysMask & (1 << (d.getValue() - 1))) != 0;
        }
    }

    record Med(
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
            List<Slot> slots) {

        /** Scheduled dose instants between two local dates, inclusive, oldest first. */
        List<OffsetDateTime> doses(LocalDate from, LocalDate to, ZoneId tz) {
            List<OffsetDateTime> out = new ArrayList<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (d.isBefore(startedOn) || (stoppedOn != null && d.isAfter(stoppedOn))) {
                    continue;
                }
                for (Slot s : slots) {
                    if (s.on(d.getDayOfWeek())) {
                        out.add(d.atTime(s.time()).atZone(tz).toOffsetDateTime());
                    }
                }
            }
            out.sort(Comparator.naturalOrder());
            return out;
        }
    }

    private List<Med> meds(UUID userId, boolean activeOnly, UUID onlyId) {
        Map<UUID, List<Slot>> slots = new HashMap<>();
        jdbc.sql("""
                        SELECT s.medication_id, s.local_time, s.days_mask FROM vitallink.medication_schedules s
                        JOIN vitallink.medications m ON m.id = s.medication_id
                        WHERE m.user_id = :u ORDER BY s.local_time
                        """)
                .param("u", userId)
                .query((rs, i) -> slots.computeIfAbsent(
                                rs.getObject("medication_id", UUID.class), k -> new ArrayList<>())
                        .add(new Slot(rs.getObject("local_time", LocalTime.class), rs.getInt("days_mask"))))
                .list();
        String where =
                "user_id = :u" + (activeOnly ? " AND status = 'active'" : "") + (onlyId != null ? " AND id = :id" : "");
        var q = jdbc.sql("SELECT * FROM vitallink.medications WHERE " + where + " ORDER BY created_at, id")
                .param("u", userId);
        if (onlyId != null) {
            q = q.param("id", onlyId);
        }
        return q.query((rs, i) -> new Med(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        DoseForm.valueOf(rs.getString("form")),
                        rs.getString("strength"),
                        rs.getString("instructions"),
                        rs.getString("prescriber"),
                        rs.getInt("refills_left"),
                        rs.getObject("refill_due_on", LocalDate.class),
                        rs.getString("status"),
                        rs.getObject("started_on", LocalDate.class),
                        rs.getObject("stopped_on", LocalDate.class),
                        slots.getOrDefault(rs.getObject("id", UUID.class), List.of())))
                .list();
    }

    private Med med(CurrentUser me, UUID id) {
        return meds(me.id(), false, id).stream()
                .findFirst()
                .orElseThrow(() -> ApiError.notFound("No such medication."));
    }

    /** Logged doses by medication and instant, for doses scheduled in [from, to). */
    private Map<UUID, Map<Instant, DoseLogOut>> logs(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        Map<UUID, Map<Instant, DoseLogOut>> out = new HashMap<>();
        jdbc.sql("""
                        SELECT d.medication_id, d.scheduled_for, d.status, d.logged_at FROM vitallink.dose_logs d
                        JOIN vitallink.medications m ON m.id = d.medication_id
                        WHERE m.user_id = :u AND d.scheduled_for >= :from AND d.scheduled_for < :to
                        """)
                .param("u", userId)
                .param("from", from)
                .param("to", to)
                .query((rs, i) -> {
                    OffsetDateTime at = rs.getObject("scheduled_for", OffsetDateTime.class);
                    return out.computeIfAbsent(rs.getObject("medication_id", UUID.class), k -> new HashMap<>())
                            .put(
                                    at.toInstant(),
                                    new DoseLogOut(
                                            at,
                                            DoseStatus.valueOf(rs.getString("status")),
                                            rs.getObject("logged_at", OffsetDateTime.class)));
                })
                .list();
        return out;
    }

    static final String[] TIMES = {"", "Once", "Twice", "3 times", "4 times", "5 times", "6 times", "7 times", "8 times"
    };

    /** "Once daily", "Twice daily", "Once weekly on Mon", "Twice a day on Mon, Wed, Fri". */
    static String frequency(List<Slot> slots) {
        if (slots.isEmpty()) {
            return "As needed";
        }
        int n = slots.size();
        int mask = slots.stream().mapToInt(Slot::daysMask).reduce(0, (a, b) -> a | b);
        if (mask == 127) {
            return TIMES[n] + " daily";
        }
        String days = IntStream.range(0, 7)
                .filter(i -> (mask & (1 << i)) != 0)
                .mapToObj(i -> DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.US))
                .collect(Collectors.joining(", "));
        return Integer.bitCount(mask) == 1 && n == 1 ? "Once weekly on " + days : TIMES[n] + " a day on " + days;
    }

    static int mask(List<Integer> days) {
        return days == null || days.isEmpty()
                ? 127
                : days.stream().mapToInt(d -> 1 << (d - 1)).reduce(0, (a, b) -> a | b);
    }

    static List<Integer> days(int mask) {
        return IntStream.rangeClosed(1, 7)
                .filter(d -> (mask & (1 << (d - 1))) != 0)
                .boxed()
                .toList();
    }

    static String formLabel(DoseForm f) {
        return Character.toUpperCase(f.name().charAt(0)) + f.name().substring(1);
    }

    static OffsetDateTime local(OffsetDateTime t, ZoneId tz) {
        return t.atZoneSameInstant(tz).toOffsetDateTime();
    }

    /** The dose that decides the row: a missed dose in the last day, else today's last dose if taken, else the next due. */
    static DoseState doseState(Med m, Map<Instant, DoseLogOut> logs, OffsetDateTime now, ZoneId tz) {
        LocalDate today = now.atZoneSameInstant(tz).toLocalDate();
        List<OffsetDateTime> doses = m.doses(today.minusDays(1), today.plusDays(8), tz);
        OffsetDateTime missed = null;
        OffsetDateTime lastToday = null;
        for (OffsetDateTime d : doses) {
            boolean logged = logs.containsKey(d.toInstant());
            if (!logged && d.plus(GRACE).isBefore(now) && !d.isBefore(now.minusHours(24))) {
                missed = d;
            }
            if (!d.isAfter(now) && d.atZoneSameInstant(tz).toLocalDate().equals(today)) {
                lastToday = d;
            }
        }
        if (missed != null) {
            return new DoseState(local(missed, tz), "missed");
        }
        if (lastToday != null) {
            DoseLogOut log = logs.get(lastToday.toInstant());
            if (log != null && log.status() == DoseStatus.taken) {
                return new DoseState(local(lastToday, tz), "taken");
            }
        }
        for (OffsetDateTime d : doses) {
            if (!logs.containsKey(d.toInstant()) && !d.plus(GRACE).isBefore(now)) {
                return new DoseState(local(d, tz), "due");
            }
        }
        return new DoseState(local(now, tz), "due");
    }

    /** Refill is due when its date is within a week (or past). */
    static boolean refillDue(Med m, LocalDate today) {
        return m.refillDueOn() != null && !m.refillDueOn().isAfter(today.plusDays(7));
    }

    // ---------------------------------------------------------------- medication reads

    public MedicationsOverview overview(UUID userId, String tzName) {
        ZoneId tz = ZoneId.of(tzName);
        OffsetDateTime now = Clock.now();
        LocalDate today = now.atZoneSameInstant(tz).toLocalDate();
        List<Med> meds = meds(userId, true, null);
        Map<UUID, Map<Instant, DoseLogOut>> logs = logs(
                userId,
                today.minusDays(1).atStartOfDay(tz).toOffsetDateTime(),
                today.plusDays(9).atStartOfDay(tz).toOffsetDateTime());
        int taken = 0;
        int total = 0;
        List<Prescription> rows = new ArrayList<>();
        for (Med m : meds) {
            Map<Instant, DoseLogOut> mine = logs.getOrDefault(m.id(), Map.of());
            for (OffsetDateTime d : m.doses(today, today, tz)) {
                total++;
                DoseLogOut log = mine.get(d.toInstant());
                if (log != null && log.status() == DoseStatus.taken) {
                    taken++;
                }
            }
            rows.add(new Prescription(
                    m.id(),
                    m.name(),
                    formLabel(m.form()),
                    m.strength(),
                    frequency(m.slots()),
                    m.prescriber(),
                    m.refillsLeft(),
                    doseState(m, mine, now, tz),
                    refillDue(m, today)));
        }
        return new MedicationsOverview(rows, new DoseCount(taken, total));
    }

    /** Every dose scheduled today, in time order (the dashboard card). */
    public List<TodayDose> today(UUID userId, String tzName) {
        ZoneId tz = ZoneId.of(tzName);
        OffsetDateTime now = Clock.now();
        LocalDate today = now.atZoneSameInstant(tz).toLocalDate();
        Map<UUID, Map<Instant, DoseLogOut>> logs = logs(
                userId,
                today.atStartOfDay(tz).toOffsetDateTime(),
                today.plusDays(1).atStartOfDay(tz).toOffsetDateTime());
        List<TodayDose> out = new ArrayList<>();
        for (Med m : meds(userId, true, null)) {
            for (OffsetDateTime d : m.doses(today, today, tz)) {
                DoseLogOut log = logs.getOrDefault(m.id(), Map.of()).get(d.toInstant());
                String status =
                        log != null ? log.status().name() : d.plus(GRACE).isBefore(now) ? "missed" : "due";
                out.add(new TodayDose(
                        m.id() + "@" + d.atZoneSameInstant(tz).toLocalTime(),
                        m.id(),
                        m.name(),
                        m.strength(),
                        status,
                        local(d, tz)));
            }
        }
        out.sort(Comparator.comparing(TodayDose::at).thenComparing(TodayDose::name));
        return out;
    }

    public MedicationDetail detail(CurrentUser me, UUID id) {
        Med m = med(me, id);
        ZoneId tz = me.zone();
        OffsetDateTime now = Clock.now();
        List<DoseLogOut> recent =
                logs(me.id(), now.minusDays(30), now.plusDays(1)).getOrDefault(m.id(), Map.of()).values().stream()
                        .sorted(Comparator.comparing(DoseLogOut::scheduledFor).reversed())
                        .map(l -> new DoseLogOut(local(l.scheduledFor(), tz), l.status(), local(l.loggedAt(), tz)))
                        .toList();
        return new MedicationDetail(
                m.id(),
                m.name(),
                m.form(),
                m.strength(),
                m.instructions(),
                m.prescriber(),
                m.refillsLeft(),
                m.refillDueOn(),
                m.status(),
                m.startedOn(),
                m.stoppedOn(),
                frequency(m.slots()),
                m.slots().stream()
                        .map(s -> new ScheduleOut(s.time(), days(s.daysMask())))
                        .toList(),
                recent);
    }

    // ---------------------------------------------------------------- medication writes

    private void replaceSchedule(UUID medId, List<LocalTime> times, List<Integer> days) {
        if (times.stream().distinct().count() != times.size()) {
            throw ApiError.invalid("Each dose time can appear only once.");
        }
        jdbc.sql("DELETE FROM vitallink.medication_schedules WHERE medication_id = :m")
                .param("m", medId)
                .update();
        int mask = mask(days);
        for (LocalTime t : times) {
            jdbc.sql("""
                            INSERT INTO vitallink.medication_schedules (id, medication_id, local_time, days_mask)
                            VALUES (:id, :m, :t, :mask)
                            """)
                    .param("id", Ids.uuid7())
                    .param("m", medId)
                    .param("t", t)
                    .param("mask", mask)
                    .update();
        }
    }

    public MedicationDetail create(CurrentUser me, MedicationIn body) {
        UUID id = Ids.uuid7();
        LocalDate started = body.startedOn() != null ? body.startedOn() : LocalDate.now(me.zone());
        jdbc.sql("""
                        INSERT INTO vitallink.medications (id, user_id, name, form, strength, instructions, prescriber,
                                                           refills_left, refill_due_on, started_on)
                        VALUES (:id, :u, :name, CAST(:form AS vitallink.dose_form), :strength, :instructions,
                                :prescriber, :refills, :refill_due, :started)
                        """)
                .param("id", id)
                .param("u", me.id())
                .param("name", body.name())
                .param("form", body.form().name())
                .param("strength", body.strength())
                .param("instructions", body.instructions())
                .param("prescriber", body.prescriber())
                .param("refills", body.refillsLeft())
                .param("refill_due", body.refillDueOn())
                .param("started", started)
                .update();
        replaceSchedule(id, body.times(), body.days());
        return detail(me, id);
    }

    private static final Set<String> MED_FIELDS = Set.of(
            "name",
            "form",
            "strength",
            "instructions",
            "prescriber",
            "refills_left",
            "refill_due_on",
            "times",
            "days",
            "status");

    /** Change fields, the schedule, or stop/restart (status "stopped" / "active"). */
    public MedicationDetail update(CurrentUser me, UUID id, Map<String, Object> body) {
        Med m = med(me, id);
        Patch p = Patch.of(body, MED_FIELDS);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (p.has("name")) changes.put("name", require(p.string("name", 1, 80, null), "name"));
        if (p.has("strength")) changes.put("strength", require(p.string("strength", 1, 40, null), "strength"));
        if (p.has("instructions")) changes.put("instructions", orEmpty(p.string("instructions", 0, 200, null)));
        if (p.has("prescriber")) changes.put("prescriber", orEmpty(p.string("prescriber", 0, 80, null)));
        if (p.has("refills_left"))
            changes.put("refills_left", require(p.integer("refills_left", 0, 99), "refills_left"));
        if (p.has("refill_due_on")) changes.put("refill_due_on", p.date("refill_due_on"));
        String casts = "";
        if (p.has("form")) {
            changes.put(
                    "form", require(p.enumValue("form", DoseForm.class), "form").name());
            casts = "form";
        }
        if (p.has("status")) {
            String status = String.valueOf(body.get("status"));
            if (status.equals("stopped") && m.status().equals("active")) {
                LocalDate today = LocalDate.now(me.zone());
                changes.put("status_raw", "stopped");
                changes.put("stopped_on", today.isBefore(m.startedOn()) ? m.startedOn() : today);
            } else if (status.equals("active") && m.status().equals("stopped")) {
                changes.put("status_raw", "active");
                changes.put("stopped_on", null);
            } else if (!status.equals("active") && !status.equals("stopped")) {
                throw Patch.invalid("status", "must be active or stopped");
            }
        }
        if (!changes.isEmpty()) {
            Object status = changes.remove("status_raw");
            List<String> sets = new ArrayList<>();
            for (String k : changes.keySet()) {
                sets.add(k.equals(casts) ? k + " = CAST(:" + k + " AS vitallink.dose_form)" : k + " = :" + k);
            }
            if (status != null) {
                sets.add("status = CAST(:status AS vitallink.med_status)");
                changes.put("status", status);
            }
            jdbc.sql("UPDATE vitallink.medications SET " + String.join(", ", sets) + ", updated_at = now() "
                            + "WHERE id = :id AND user_id = :u")
                    .params(changes)
                    .param("id", id)
                    .param("u", me.id())
                    .update();
        }
        if (p.has("times") || p.has("days")) {
            List<LocalTime> times = p.has("times")
                    ? times(body.get("times"))
                    : m.slots().stream().map(Slot::time).toList();
            List<Integer> days = p.has("days")
                    ? days(body.get("days"))
                    : days(m.slots().isEmpty() ? 127 : m.slots().getFirst().daysMask());
            replaceSchedule(id, times, days);
        }
        return detail(me, id);
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw Patch.invalid(field, "can't be empty");
        }
        return value;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static List<LocalTime> times(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 8) {
            throw Patch.invalid("times", "give 1–8 times of day, e.g. [\"08:00\"]");
        }
        try {
            List<LocalTime> out =
                    list.stream().map(o -> LocalTime.parse(String.valueOf(o))).toList();
            if (out.stream().anyMatch(t -> t.getSecond() != 0 || t.getNano() != 0)) {
                throw Patch.invalid("times", "times are whole minutes");
            }
            return out;
        } catch (java.time.format.DateTimeParseException e) {
            throw Patch.invalid("times", "times look like 08:00");
        }
    }

    private static List<Integer> days(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list) || list.size() > 7) {
            throw Patch.invalid("days", "give ISO weekdays 1 (Monday) – 7 (Sunday)");
        }
        List<Integer> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Integer d) || d < 1 || d > 7) {
                throw Patch.invalid("days", "give ISO weekdays 1 (Monday) – 7 (Sunday)");
            }
            out.add(d);
        }
        return out;
    }

    public void delete(CurrentUser me, UUID id) {
        int n = jdbc.sql("DELETE FROM vitallink.medications WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such medication.");
        }
    }

    /** Mark one scheduled dose taken or skipped. Idempotent: the same dose logged again is updated. */
    public DoseLogOut logDose(CurrentUser me, UUID id, OffsetDateTime scheduledFor, DoseIn body) {
        Med m = med(me, id);
        if (!m.status().equals("active")) {
            throw new ApiError(409, "medication_stopped", "This medication is stopped.");
        }
        ZoneId tz = me.zone();
        OffsetDateTime now = Clock.now();
        if (scheduledFor.isAfter(now.plusHours(12)) || scheduledFor.isBefore(now.minusDays(7))) {
            throw ApiError.invalid("Doses can be logged from 7 days back to 12 hours ahead.");
        }
        LocalDate day = scheduledFor.atZoneSameInstant(tz).toLocalDate();
        boolean scheduled =
                m.doses(day, day, tz).stream().anyMatch(d -> d.toInstant().equals(scheduledFor.toInstant()));
        if (!scheduled) {
            throw new ApiError(422, "not_scheduled", "No dose of this medication is scheduled at that time.");
        }
        jdbc.sql("""
                        INSERT INTO vitallink.dose_logs (id, medication_id, scheduled_for, status)
                        VALUES (:id, :m, :at, CAST(:status AS vitallink.dose_status))
                        ON CONFLICT (medication_id, scheduled_for)
                        DO UPDATE SET status = EXCLUDED.status, logged_at = now()
                        """)
                .param("id", Ids.uuid7())
                .param("m", id)
                .param("at", scheduledFor)
                .param("status", body.status().name())
                .update();
        return new DoseLogOut(local(scheduledFor, tz), body.status(), local(now, tz));
    }

    public void undoDose(CurrentUser me, UUID id, OffsetDateTime scheduledFor) {
        med(me, id);
        int n = jdbc.sql("DELETE FROM vitallink.dose_logs WHERE medication_id = :m AND scheduled_for = :at")
                .param("m", id)
                .param("at", scheduledFor)
                .update();
        if (n == 0) {
            throw ApiError.notFound("That dose isn't logged.");
        }
    }

    // ---------------------------------------------------------------- appointments

    /** The API's status: cancelled; completed once it's over; confirmed; or pending (not confirmed yet). */
    static String appointmentStatus(
            AppointmentState state, OffsetDateTime startsAt, int durationMin, OffsetDateTime now) {
        if (state == AppointmentState.cancelled) return "cancelled";
        if (startsAt.plusMinutes(durationMin).isBefore(now)) return "completed";
        return state == AppointmentState.confirmed ? "confirmed" : "pending";
    }

    private Appointment appointment(ResultSet rs, ZoneId tz, OffsetDateTime now) throws SQLException {
        OffsetDateTime starts = rs.getObject("starts_at", OffsetDateTime.class);
        int duration = rs.getInt("duration_min");
        return new Appointment(
                rs.getObject("id", UUID.class),
                rs.getString("clinician_name"),
                rs.getString("specialty"),
                local(starts, tz),
                duration,
                VisitMode.valueOf(rs.getString("mode")),
                rs.getString("location"),
                appointmentStatus(AppointmentState.valueOf(rs.getString("state")), starts, duration, now),
                rs.getString("notes"));
    }

    /**
     * Appointments in start order. {@code upcoming}: only those not over and not cancelled. {@code from} /
     * {@code to} bound the start time.
     */
    public List<Appointment> appointments(
            UUID userId, String tzName, OffsetDateTime from, OffsetDateTime to, boolean upcoming, int limit) {
        ZoneId tz = ZoneId.of(tzName);
        OffsetDateTime now = Clock.now();
        StringBuilder where = new StringBuilder("user_id = :u");
        Map<String, Object> params = new HashMap<>();
        params.put("u", userId);
        if (from != null) {
            where.append(" AND starts_at >= :from");
            params.put("from", from);
        }
        if (to != null) {
            where.append(" AND starts_at < :to");
            params.put("to", to);
        }
        if (upcoming) {
            where.append(" AND state <> 'cancelled' AND starts_at + make_interval(mins => duration_min) >= :now");
            params.put("now", now);
        }
        params.put("limit", limit);
        return jdbc.sql("SELECT * FROM vitallink.appointments WHERE " + where + " ORDER BY starts_at, id LIMIT :limit")
                .params(params)
                .query((rs, i) -> appointment(rs, tz, now))
                .list();
    }

    private Appointment appointment(CurrentUser me, UUID id) {
        OffsetDateTime now = Clock.now();
        return jdbc.sql("SELECT * FROM vitallink.appointments WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", me.id())
                .query((rs, i) -> appointment(rs, me.zone(), now))
                .optional()
                .orElseThrow(() -> ApiError.notFound("No such appointment."));
    }

    public Appointment createAppointment(CurrentUser me, AppointmentIn body) {
        UUID id = Ids.uuid7();
        jdbc.sql("""
                        INSERT INTO vitallink.appointments (id, user_id, clinician_name, specialty, starts_at,
                                                            duration_min, mode, location, state, notes)
                        VALUES (:id, :u, :clinician, :specialty, :starts, :duration, CAST(:mode AS vitallink.visit_mode),
                                :location, CAST(:state AS vitallink.appointment_state), :notes)
                        """)
                .param("id", id)
                .param("u", me.id())
                .param("clinician", body.clinician())
                .param("specialty", body.specialty())
                .param("starts", body.startsAt())
                .param("duration", body.durationMin())
                .param("mode", body.mode().name())
                .param("location", body.location())
                .param("state", body.state().name())
                .param("notes", body.notes())
                .update();
        return appointment(me, id);
    }

    private static final Set<String> APPOINTMENT_FIELDS =
            Set.of("clinician", "specialty", "starts_at", "duration_min", "mode", "location", "state", "notes");

    public Appointment updateAppointment(CurrentUser me, UUID id, Map<String, Object> body) {
        appointment(me, id);
        Patch p = Patch.of(body, APPOINTMENT_FIELDS);
        Map<String, Object> changes = new LinkedHashMap<>();
        List<String> sets = new ArrayList<>();
        if (p.has("clinician")) {
            changes.put("clinician_name", require(p.string("clinician", 1, 80, null), "clinician"));
            sets.add("clinician_name = :clinician_name");
        }
        if (p.has("specialty")) {
            changes.put("specialty", orEmpty(p.string("specialty", 0, 80, null)));
            sets.add("specialty = :specialty");
        }
        if (p.has("location")) {
            changes.put("location", orEmpty(p.string("location", 0, 200, null)));
            sets.add("location = :location");
        }
        if (p.has("notes")) {
            changes.put("notes", p.string("notes", 0, 1000, null));
            sets.add("notes = :notes");
        }
        if (p.has("duration_min")) {
            changes.put("duration_min", require(p.integer("duration_min", 5, 600), "duration_min"));
            sets.add("duration_min = :duration_min");
        }
        if (p.has("starts_at")) {
            try {
                changes.put(
                        "starts_at", OffsetDateTime.parse(String.valueOf(require(body.get("starts_at"), "starts_at"))));
            } catch (java.time.format.DateTimeParseException e) {
                throw Patch.invalid("starts_at", "must be a date-time with an offset");
            }
            sets.add("starts_at = :starts_at");
        }
        if (p.has("mode")) {
            changes.put(
                    "mode",
                    require(p.enumValue("mode", VisitMode.class), "mode").name());
            sets.add("mode = CAST(:mode AS vitallink.visit_mode)");
        }
        if (p.has("state")) {
            changes.put(
                    "state",
                    require(p.enumValue("state", AppointmentState.class), "state")
                            .name());
            sets.add("state = CAST(:state AS vitallink.appointment_state)");
        }
        if (!sets.isEmpty()) {
            jdbc.sql("UPDATE vitallink.appointments SET " + String.join(", ", sets)
                            + ", updated_at = now() WHERE id = :id AND user_id = :u")
                    .params(changes)
                    .param("id", id)
                    .param("u", me.id())
                    .update();
        }
        return appointment(me, id);
    }

    public void deleteAppointment(CurrentUser me, UUID id) {
        int n = jdbc.sql("DELETE FROM vitallink.appointments WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such appointment.");
        }
    }

    // ---------------------------------------------------------------- conditions

    public List<Condition> conditions(UUID userId) {
        return jdbc.sql("SELECT * FROM vitallink.conditions WHERE user_id = :u ORDER BY kind, created_at, id")
                .param("u", userId)
                .query((rs, i) -> new Condition(
                        rs.getObject("id", UUID.class),
                        rs.getString("label"),
                        ConditionKind.valueOf(rs.getString("kind")),
                        rs.getString("note"),
                        rs.getObject("noted_on", LocalDate.class)))
                .list();
    }

    public Condition addCondition(CurrentUser me, ConditionIn body) {
        UUID id = Ids.uuid7();
        try {
            jdbc.sql("""
                            INSERT INTO vitallink.conditions (id, user_id, label, kind, note, noted_on)
                            VALUES (:id, :u, :label, CAST(:kind AS vitallink.condition_kind), :note, :noted)
                            """)
                    .param("id", id)
                    .param("u", me.id())
                    .param("label", body.label())
                    .param("kind", body.kind().name())
                    .param("note", body.note())
                    .param("noted", body.notedOn())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiError(409, "condition_exists", "You've already added this.");
        }
        return new Condition(id, body.label(), body.kind(), body.note(), body.notedOn());
    }

    public void deleteCondition(CurrentUser me, UUID id) {
        int n = jdbc.sql("DELETE FROM vitallink.conditions WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such condition.");
        }
    }

    // ---------------------------------------------------------------- emergency medical ID

    static String sexLabel(Sex sex) {
        if (sex == null) return null;
        return switch (sex) {
            case female -> "Female";
            case male -> "Male";
            case other -> "Other";
            case undisclosed -> "Prefer not to say";
        };
    }

    record EmergencyRow(OrganDonor donor, String donorNote, String hospital, String notes, boolean lockScreen) {}

    private EmergencyRow emergencyRow(UUID userId) {
        return jdbc.sql("SELECT * FROM vitallink.emergency_profiles WHERE user_id = :u")
                .param("u", userId)
                .query((rs, i) -> new EmergencyRow(
                        rs.getString("organ_donor") == null ? null : OrganDonor.valueOf(rs.getString("organ_donor")),
                        rs.getString("organ_donor_note"),
                        rs.getString("preferred_hospital"),
                        rs.getString("notes"),
                        rs.getBoolean("show_on_lock_screen")))
                .optional()
                .orElse(new EmergencyRow(null, null, null, "", false));
    }

    /** Whether the medical ID may show on the phone's lock screen (privacy centre switch). */
    public boolean showOnLockScreen(UUID userId) {
        return emergencyRow(userId).lockScreen();
    }

    public List<Contact> contacts(UUID userId) {
        return jdbc.sql(
                        "SELECT * FROM vitallink.emergency_contacts WHERE user_id = :u ORDER BY is_primary DESC, created_at, id")
                .param("u", userId)
                .query((rs, i) -> new Contact(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("relation"),
                        rs.getString("phone"),
                        rs.getBoolean("is_primary")))
                .list();
    }

    public EmergencyProfile emergency(CurrentUser me) {
        Profile p = accounts.profile(me.id());
        EmergencyRow e = emergencyRow(me.id());
        List<Condition> conditions = conditions(me.id());
        String donor = e.donor() == null
                ? null
                : switch (e.donor()) {
                    case yes -> "Yes" + (e.donorNote() != null ? " — " + e.donorNote() : "");
                    case no -> "No";
                    case undecided -> "Undecided";
                };
        String name = p.displayName() != null ? p.displayName() : me.user().email();
        return new EmergencyProfile(
                name,
                p.ageOn(LocalDate.now(me.zone())),
                sexLabel(p.sex()),
                p.city() == null ? "" : p.city(),
                conditions.stream()
                        .filter(c -> c.kind() == ConditionKind.allergy)
                        .map(c -> c.label() + (c.note() != null ? " (" + c.note() + ")" : ""))
                        .toList(),
                conditions.stream()
                        .filter(c -> c.kind() == ConditionKind.chronic)
                        .map(Condition::label)
                        .toList(),
                meds(me.id(), true, null).stream()
                        .map(m -> m.name() + " " + m.strength())
                        .toList(),
                donor,
                e.hospital(),
                e.notes(),
                e.lockScreen(),
                contacts(me.id()));
    }

    private static final Set<String> EMERGENCY_FIELDS =
            Set.of("organ_donor", "organ_donor_note", "preferred_hospital", "notes", "show_on_lock_screen");

    public EmergencyProfile updateEmergency(CurrentUser me, Map<String, Object> body) {
        Patch p = Patch.of(body, EMERGENCY_FIELDS);
        EmergencyRow e = emergencyRow(me.id());
        OrganDonor donor = p.has("organ_donor") ? p.enumValue("organ_donor", OrganDonor.class) : e.donor();
        String donorNote = p.has("organ_donor_note") ? p.string("organ_donor_note", 1, 80, null) : e.donorNote();
        String hospital = p.has("preferred_hospital") ? p.string("preferred_hospital", 1, 120, null) : e.hospital();
        String notes = p.has("notes") ? orEmpty(p.string("notes", 0, 500, null)) : e.notes();
        boolean lock = p.has("show_on_lock_screen")
                ? require(p.bool("show_on_lock_screen"), "show_on_lock_screen")
                : e.lockScreen();
        jdbc.sql("""
                        INSERT INTO vitallink.emergency_profiles
                          (user_id, organ_donor, organ_donor_note, preferred_hospital, notes, show_on_lock_screen)
                        VALUES (:u, CAST(:donor AS vitallink.organ_donor), :note, :hospital, :notes, :lock)
                        ON CONFLICT (user_id) DO UPDATE SET organ_donor = EXCLUDED.organ_donor,
                          organ_donor_note = EXCLUDED.organ_donor_note, preferred_hospital = EXCLUDED.preferred_hospital,
                          notes = EXCLUDED.notes, show_on_lock_screen = EXCLUDED.show_on_lock_screen, updated_at = now()
                        """)
                .param("u", me.id())
                .param("donor", donor == null ? null : donor.name())
                .param("note", donorNote)
                .param("hospital", hospital)
                .param("notes", notes)
                .param("lock", lock)
                .update();
        return emergency(me);
    }

    private void clearPrimary(UUID userId) {
        jdbc.sql("UPDATE vitallink.emergency_contacts SET is_primary = false WHERE user_id = :u AND is_primary")
                .param("u", userId)
                .update();
    }

    public Contact addContact(CurrentUser me, ContactIn body) {
        // Serialise contact writes for this user so the count check and the primary flag can't race.
        jdbc.sql("SELECT id FROM vitallink.users WHERE id = :u FOR UPDATE")
                .param("u", me.id())
                .query()
                .listOfRows();
        int count = jdbc.sql("SELECT count(*) FROM vitallink.emergency_contacts WHERE user_id = :u")
                .param("u", me.id())
                .query(Integer.class)
                .single();
        if (count >= MAX_CONTACTS) {
            throw new ApiError(409, "too_many_contacts", "You can keep up to " + MAX_CONTACTS + " emergency contacts.");
        }
        boolean primary = body.primary() || count == 0;
        if (primary) {
            clearPrimary(me.id());
        }
        UUID id = Ids.uuid7();
        jdbc.sql("""
                        INSERT INTO vitallink.emergency_contacts (id, user_id, name, relation, phone, is_primary)
                        VALUES (:id, :u, :name, :relation, :phone, :primary)
                        """)
                .param("id", id)
                .param("u", me.id())
                .param("name", body.name())
                .param("relation", body.relation())
                .param("phone", body.phone())
                .param("primary", primary)
                .update();
        return new Contact(id, body.name(), body.relation(), body.phone(), primary);
    }

    private static final Set<String> CONTACT_FIELDS = Set.of("name", "relation", "phone", "primary");

    public Contact updateContact(CurrentUser me, UUID id, Map<String, Object> body) {
        Contact c = contacts(me.id()).stream()
                .filter(x -> x.id().equals(id))
                .findFirst()
                .orElseThrow(() -> ApiError.notFound("No such contact."));
        Patch p = Patch.of(body, CONTACT_FIELDS);
        String name = p.has("name") ? require(p.string("name", 1, 80, null), "name") : c.name();
        String relation = p.has("relation") ? require(p.string("relation", 1, 40, null), "relation") : c.relation();
        String phone = p.has("phone") ? require(p.string("phone", 0, 16, E164), "phone") : c.phone();
        boolean primary = p.has("primary") ? require(p.bool("primary"), "primary") : c.primary();
        if (primary && !c.primary()) {
            clearPrimary(me.id());
        }
        jdbc.sql("""
                        UPDATE vitallink.emergency_contacts SET name = :name, relation = :relation, phone = :phone,
                          is_primary = :primary WHERE id = :id AND user_id = :u
                        """)
                .param("name", name)
                .param("relation", relation)
                .param("phone", phone)
                .param("primary", primary)
                .param("id", id)
                .param("u", me.id())
                .update();
        return new Contact(id, name, relation, phone, primary);
    }

    public void deleteContact(CurrentUser me, UUID id) {
        int n = jdbc.sql("DELETE FROM vitallink.emergency_contacts WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such contact.");
        }
    }

    // ---------------------------------------------------------------- for other modules

    /** Active medication count, for the family card. */
    public int activeMedicationCount(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM vitallink.medications WHERE user_id = :u AND status = 'active'")
                .param("u", userId)
                .query(Integer.class)
                .single();
    }

    /** The next appointment that isn't over or cancelled. */
    public Optional<Appointment> nextAppointment(UUID userId, String tz) {
        return appointments(userId, tz, null, null, true, 1).stream().findFirst();
    }

    /** Missed doses scheduled on a local day (nightly job). */
    public List<TodayDose> missedOn(UUID userId, String tzName, LocalDate day) {
        ZoneId tz = ZoneId.of(tzName);
        OffsetDateTime now = Clock.now();
        Map<UUID, Map<Instant, DoseLogOut>> logs = logs(
                userId,
                day.atStartOfDay(tz).toOffsetDateTime(),
                day.plusDays(1).atStartOfDay(tz).toOffsetDateTime());
        List<TodayDose> out = new ArrayList<>();
        for (Med m : meds(userId, true, null)) {
            for (OffsetDateTime d : m.doses(day, day, tz)) {
                if (!logs.getOrDefault(m.id(), Map.of()).containsKey(d.toInstant())
                        && d.plus(GRACE).isBefore(now)) {
                    out.add(new TodayDose(
                            m.id() + "@" + d.atZoneSameInstant(tz).toLocalTime(),
                            m.id(),
                            m.name(),
                            m.strength(),
                            "missed",
                            local(d, tz)));
                }
            }
        }
        return out;
    }

    /** Active medications whose refill date falls within a week (nightly job). */
    public List<Prescription> refillsDue(UUID userId, String tzName) {
        return overview(userId, tzName).prescriptions().stream()
                .filter(Prescription::refillDue)
                .toList();
    }
}
