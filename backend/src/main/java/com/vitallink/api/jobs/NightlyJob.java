package com.vitallink.api.jobs;

import com.vitallink.api.care.CareDtos.Appointment;
import com.vitallink.api.care.CareDtos.Prescription;
import com.vitallink.api.care.CareDtos.TodayDose;
import com.vitallink.api.care.CareService;
import com.vitallink.api.core.Clock;
import com.vitallink.api.goals.GoalDtos.TrackedGoal;
import com.vitallink.api.goals.GoalService;
import com.vitallink.api.health.GoogleHealth;
import com.vitallink.api.health.HealthDtos.DeviceOut;
import com.vitallink.api.health.HealthService;
import com.vitallink.api.notifications.NotificationService;
import com.vitallink.api.notifications.NotificationService.Kind;
import com.vitallink.api.notifications.NotificationService.Message;
import com.vitallink.api.records.RecordService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The nightly job (docs/08 §8.4), run by GitHub Actions cron. It's synchronous and safe to re-run: every
 * notification has a dedupe key, every cleanup is a condition rather than a counter. Each user and each
 * cleanup step runs in its own transaction, so one failure is logged and the rest carry on; the summary
 * counts the failures and the endpoint answers 500 when there are any.
 */
@Service
public class NightlyJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyJob.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final GoalService goals;
    private final CareService care;
    private final HealthService health;
    private final NotificationService notifications;
    private final RecordService records;
    private final GoogleHealth google;
    private final com.vitallink.api.health.PhoneLinks phones;

    public NightlyJob(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            GoalService goals,
            CareService care,
            HealthService health,
            NotificationService notifications,
            RecordService records,
            GoogleHealth google,
            com.vitallink.api.health.PhoneLinks phones) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.goals = goals;
        this.care = care;
        this.health = health;
        this.notifications = notifications;
        this.records = records;
        this.google = google;
        this.phones = phones;
    }

    record User(UUID id, String timezone) {}

    /** Runs every step; returns counts for the job log. */
    public Map<String, Object> run() {
        long started = System.currentTimeMillis();
        Map<String, Object> summary = new LinkedHashMap<>();
        List<User> users = jdbc.sql("SELECT id, timezone FROM vitallink.users ORDER BY id")
                .query((rs, i) -> new User(rs.getObject("id", UUID.class), rs.getString("timezone")))
                .list();
        int notified = 0;
        int failed = 0;
        for (User u : users) {
            try {
                Integer n = tx.execute(s -> perUser(u));
                notified += n == null ? 0 : n;
            } catch (RuntimeException e) {
                failed++;
                log.error("nightly job failed for user {}", u.id(), e);
            }
        }
        summary.put("users", users.size());
        summary.put("users_failed", failed);
        summary.put("notifications_created", notified);
        // Google Health: pull what each connected person's devices uploaded since the last sync. A revoked grant
        // or a Google outage is the person's card to show, not a job failure.
        int synced = 0;
        int syncFailed = 0;
        if (google.configured()) {
            Map<UUID, String> zones = new java.util.HashMap<>();
            users.forEach(u -> zones.put(u.id(), u.timezone()));
            for (UUID id : google.connectedUsers()) {
                try {
                    tx.executeWithoutResult(s -> google.sync(id, zones.getOrDefault(id, "UTC")));
                    synced++;
                } catch (RuntimeException e) {
                    syncFailed++;
                    log.warn("nightly google health sync failed for user {}: {}", id, e.getMessage());
                }
            }
        }
        summary.put("google_health_synced", synced);
        summary.put("google_health_failed", syncFailed);
        // Each cleanup in its own transaction and try: one failing step doesn't skip the rest.
        List<String> stepsFailed = new java.util.ArrayList<>();
        step(summary, stepsFailed, "notifications_deleted", notifications::deleteOld);
        step(
                summary,
                stepsFailed,
                "ingest_batches_deleted",
                () -> jdbc.sql("DELETE FROM vitallink.ingest_batches WHERE received_at < now() - interval '30 days'")
                        .update());
        step(summary, stepsFailed, "expired_shares_closed", () -> jdbc.sql("""
                        UPDATE vitallink.consents SET revoked_at = now(), revoke_reason = 'expired_cleanup'
                        WHERE revoked_at IS NULL AND expires_at < now() - interval '30 days'
                        """).update());
        step(summary, stepsFailed, "abandoned_uploads", records::cleanAbandonedUploads);
        step(summary, stepsFailed, "phone_codes_expired", phones::deleteExpiredCodes);
        step(summary, stepsFailed, "stored_files_deleted", records::drainFileDeletions);
        summary.put("steps_failed", stepsFailed);
        summary.put("ms", System.currentTimeMillis() - started);
        log.info("nightly job: {}", summary);
        return summary;
    }

    private void step(
            Map<String, Object> summary, List<String> failed, String name, java.util.function.IntSupplier work) {
        try {
            summary.put(name, tx.execute(s -> work.getAsInt()));
        } catch (RuntimeException e) {
            failed.add(name);
            log.error("nightly job step {} failed", name, e);
        }
    }

    /** One user's notifications and goal updates. Returns how many notifications were added. */
    int perUser(User u) {
        ZoneId tz = ZoneId.of(u.timezone());
        OffsetDateTime now = Clock.now();
        LocalDate today = now.atZoneSameInstant(tz).toLocalDate();
        int added = 0;

        for (String title : goals.markAchieved(u.id(), u.timezone())) {
            added += add(
                    u.id(),
                    "goal-achieved:" + title + ":" + today,
                    new Message(
                            Kind.goals,
                            "Goal achieved: " + title,
                            "You reached your goal. Nice work.",
                            null,
                            "View goal",
                            "/goals"));
        }
        if (today.getDayOfWeek().getValue() >= 4) { // from Thursday, a weekly goal can still be rescued
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            for (TrackedGoal g : goals.atRisk(u.id(), u.timezone())) {
                if (g.detail().contains("sessions")) {
                    added += add(
                            u.id(),
                            "goal-risk:" + g.id() + ":" + monday,
                            new Message(
                                    Kind.fitness,
                                    "Weekly training goal at risk",
                                    g.title() + ": " + g.detail() + ".",
                                    g.shortDetail(),
                                    "View goal",
                                    "/goals"));
                }
            }
        }

        LocalDate yesterday = today.minusDays(1);
        for (TodayDose d : care.missedOn(u.id(), u.timezone(), yesterday)) {
            added += add(
                    u.id(),
                    "missed:" + d.id() + ":" + yesterday,
                    new Message(
                            Kind.medication,
                            d.name() + " dose missed",
                            "Scheduled for " + d.at().format(TIME) + " yesterday and not marked taken.",
                            null,
                            "Mark taken",
                            "/medications"));
        }
        for (Prescription p : care.refillsDue(u.id(), u.timezone())) {
            added += add(
                    u.id(),
                    "refill:" + p.id() + ":" + today.with(java.time.DayOfWeek.MONDAY),
                    new Message(
                            Kind.medication,
                            "Refill due soon",
                            p.name() + " " + p.strength() + " needs a refill within a week.",
                            null,
                            "View medication",
                            "/medications"));
        }

        OffsetDateTime tomorrow = today.plusDays(1).atStartOfDay(tz).toOffsetDateTime();
        for (Appointment a : care.appointments(u.id(), u.timezone(), tomorrow, tomorrow.plusDays(1), true, 20)) {
            String where = a.location().isEmpty() ? "" : " at " + a.location();
            String who = a.clinician() + (a.specialty().isEmpty() ? "" : ", " + a.specialty());
            added += add(
                    u.id(),
                    "appt-tomorrow:" + a.id(),
                    new Message(
                            Kind.appointments,
                            "Appointment tomorrow",
                            who + ", " + a.startsAt().format(TIME) + where + ".",
                            who + ", " + a.startsAt().format(TIME) + ".",
                            a.mode().name().equals("telehealth") ? "Join call" : "Directions",
                            "/appointments"));
        }

        for (DeviceOut d : health.devices(u.id(), u.timezone())) {
            if (d.status().equals("overdue")) {
                long days = ChronoUnit.DAYS.between(d.lastSyncAt().toInstant(), now.toInstant());
                added += add(
                        u.id(),
                        "overdue:" + d.id() + ":" + d.lastSyncAt().toLocalDate(),
                        new Message(
                                Kind.health,
                                d.name() + " hasn't synced",
                                "Last sync " + (days <= 1 ? "a day" : days + " days") + " ago ("
                                        + d.lastSyncAt().format(DAY) + "). Open the app near the device to sync it.",
                                "Last sync " + (days <= 1 ? "a day" : days + " days") + " ago.",
                                "View devices",
                                "/devices"));
            }
        }
        return added;
    }

    private int add(UUID user, String key, Message m) {
        return notifications.notify(user, key, m) ? 1 : 0;
    }
}
