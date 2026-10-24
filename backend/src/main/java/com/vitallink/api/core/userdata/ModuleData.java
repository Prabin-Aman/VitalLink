package com.vitallink.api.core.userdata;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Export queries for the modules built before the hook existed (account, health, training, nutrition,
 * sharing, care, goals). Newer modules implement {@link UserData} next to their own code.
 */
@Configuration
public class ModuleData {

    private static Map<String, String> of(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Bean
    UserData accountData() {
        return () -> of(
                "account",
                "SELECT id, email, email_confirmed_at, timezone, created_at FROM vitallink.users WHERE id = :u",
                "profile",
                "SELECT * FROM vitallink.profiles WHERE user_id = :u");
    }

    @Bean
    UserData healthData() {
        return () -> of(
                "health_readings",
                """
                SELECT m.code AS metric, r.recorded_at, r.source, r.value, r.samples
                FROM vitallink.health_readings r JOIN vitallink.metrics m ON m.id = r.metric_id
                WHERE r.user_id = :u ORDER BY r.recorded_at, m.code, r.source
                """,
                "devices",
                "SELECT id, platform, external_id, name, kind, metric_ids, first_seen_at, last_sync_at "
                        + "FROM vitallink.devices WHERE user_id = :u ORDER BY first_seen_at, id",
                "ingest_batches",
                "SELECT id, idempotency_key, platform, received_at, submitted, inserted, updated, unchanged "
                        + "FROM vitallink.ingest_batches WHERE user_id = :u ORDER BY id");
    }

    @Bean
    UserData trainingData() {
        return () -> of(
                "workouts",
                "SELECT * FROM vitallink.workouts WHERE user_id = :u ORDER BY started_at, id",
                "exercise_sets",
                """
                SELECT s.workout_id, e.name AS exercise, s.set_no, s.reps, s.weight_kg, s.duration_s
                FROM vitallink.exercise_sets s JOIN vitallink.workouts w ON w.id = s.workout_id
                JOIN vitallink.exercises e ON e.id = s.exercise_id
                WHERE w.user_id = :u ORDER BY s.id
                """,
                "training_plans",
                "SELECT * FROM vitallink.training_plans WHERE user_id = :u ORDER BY created_at, id",
                "plan_sessions",
                """
                SELECT s.* FROM vitallink.plan_sessions s JOIN vitallink.training_plans p ON p.id = s.plan_id
                WHERE p.user_id = :u ORDER BY s.scheduled_for, s.id
                """);
    }

    @Bean
    UserData nutritionData() {
        return () -> of(
                "foods",
                "SELECT * FROM vitallink.foods WHERE owner_user_id = :u ORDER BY name, id",
                "meals",
                "SELECT * FROM vitallink.meal_entries WHERE user_id = :u ORDER BY eaten_at, id",
                "meal_items",
                """
                SELECT i.* FROM vitallink.meal_items i JOIN vitallink.meal_entries e ON e.id = i.meal_id
                WHERE e.user_id = :u ORDER BY e.eaten_at, i.position
                """,
                "hydration",
                "SELECT * FROM vitallink.hydration_logs WHERE user_id = :u ORDER BY logged_at, id",
                "nutrition_targets",
                "SELECT * FROM vitallink.nutrition_targets WHERE user_id = :u");
    }

    @Bean
    UserData sharingData() {
        return () -> of(
                "shares_given",
                "SELECT id, grantee_email, grantee_label, grantee_kind, scopes, purpose, starts_at, expires_at, "
                        + "created_at, revoked_at, revoke_reason FROM vitallink.consents WHERE grantor_id = :u "
                        + "ORDER BY created_at, id",
                "shares_received",
                "SELECT id, grantor_id, scopes, starts_at, expires_at, revoked_at FROM vitallink.consents "
                        + "WHERE grantee_user_id = :u ORDER BY created_at, id",
                "access_log",
                "SELECT at, actor_user_id, action, scope, outcome, reason, consent_id FROM vitallink.audit_log "
                        + "WHERE subject_user_id = :u ORDER BY at, id");
    }

    @Bean
    UserData careData() {
        return () -> of(
                "medications",
                "SELECT * FROM vitallink.medications WHERE user_id = :u ORDER BY created_at, id",
                "medication_schedules",
                """
                SELECT s.* FROM vitallink.medication_schedules s JOIN vitallink.medications m ON m.id = s.medication_id
                WHERE m.user_id = :u ORDER BY s.medication_id, s.local_time
                """,
                "dose_logs",
                """
                SELECT d.* FROM vitallink.dose_logs d JOIN vitallink.medications m ON m.id = d.medication_id
                WHERE m.user_id = :u ORDER BY d.scheduled_for, d.id
                """,
                "appointments",
                "SELECT * FROM vitallink.appointments WHERE user_id = :u ORDER BY starts_at, id",
                "conditions",
                "SELECT * FROM vitallink.conditions WHERE user_id = :u ORDER BY created_at, id",
                "emergency_profile",
                "SELECT * FROM vitallink.emergency_profiles WHERE user_id = :u",
                "emergency_contacts",
                "SELECT * FROM vitallink.emergency_contacts WHERE user_id = :u ORDER BY created_at, id");
    }

    @Bean
    UserData goalsData() {
        return () -> of("goals", "SELECT * FROM vitallink.goals WHERE user_id = :u ORDER BY created_at, id");
    }
}
