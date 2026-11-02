package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Care: medications and doses, appointments, conditions, the emergency medical ID, privacy switch. */
class CareTest extends ApiTest {

    static Map<String, Object> med(String name, List<String> times) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("form", "tablet");
        m.put("strength", "500 mg");
        m.put("prescriber", "Dr. Laura Bennett");
        m.put("refills_left", 3);
        m.put(
                "started_on",
                ZonedDateTime.now(CHICAGO).minusDays(10).toLocalDate().toString());
        m.put("times", times);
        return m;
    }

    /** A dose time 30 minutes ago (whole minutes), and the instant of that dose. */
    static ZonedDateTime recentDose() {
        return ZonedDateTime.now(CHICAGO).minusMinutes(30).truncatedTo(ChronoUnit.MINUTES);
    }

    // ---------------------------------------------------------------- medications

    @Test
    void createListAndLogADose() {
        String u = user();
        ZonedDateTime dose = recentDose();
        Res created = post(
                u,
                "/v1/me/medications",
                med("Metformin", List.of(dose.toLocalTime().toString())));
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        String id = created.at("/id").asString();
        assertThat(created.at("/frequency").asString()).isEqualTo("Once daily");
        assertThat(created.at("/schedule/0/days").size()).isEqualTo(7);

        Res overview = get(u, "/v1/me/medications");
        assertThat(overview.at("/prescriptions/0/name").asString()).isEqualTo("Metformin");
        assertThat(overview.at("/prescriptions/0/form").asString()).isEqualTo("Tablet");
        assertThat(overview.at("/prescriptions/0/dose/status").asString()).isEqualTo("due");

        String path = "/v1/me/medications/" + id + "/doses/" + iso(dose);
        Res logged = put(u, path, Map.of("status", "taken"));
        assertThat(logged.status()).as(logged.text()).isEqualTo(200);
        // Idempotent: logging the same dose again updates it.
        assertThat(put(u, path, Map.of("status", "taken")).status()).isEqualTo(200);
        assertThat(get(u, "/v1/me/medications/" + id).at("/recent_doses").size())
                .isEqualTo(1);

        Res today = get(u, "/v1/me/medications/today");
        if (dose.toLocalDate().equals(ZonedDateTime.now(CHICAGO).toLocalDate())) {
            // (Shortly after midnight the dose belongs to yesterday, and today's is due instead.)
            assertThat(get(u, "/v1/me/medications")
                            .at("/prescriptions/0/dose/status")
                            .asString())
                    .isEqualTo("taken");
            assertThat(today.at("/0/status").asString()).isEqualTo("taken");
            assertThat(today.at("/0/medication_id").asString()).isEqualTo(id);
            assertThat(get(u, "/v1/me/medications").at("/doses_today/taken").asInt())
                    .isEqualTo(1);
        }
        assertThat(delete(u, path).status()).isEqualTo(204);
        assertThat(delete(u, path).status()).isEqualTo(404);
    }

    @Test
    void onlyScheduledDosesCanBeLogged() {
        String u = user();
        ZonedDateTime dose = recentDose();
        String id = post(
                        u,
                        "/v1/me/medications",
                        med("Atorvastatin", List.of(dose.toLocalTime().toString())))
                .at("/id")
                .asString();
        Res wrongTime =
                put(u, "/v1/me/medications/" + id + "/doses/" + iso(dose.plusMinutes(7)), Map.of("status", "taken"));
        assertThat(wrongTime.status()).isEqualTo(422);
        assertThat(wrongTime.code()).isEqualTo("not_scheduled");
        Res tooFar = put(u, "/v1/me/medications/" + id + "/doses/" + iso(dose.plusDays(2)), Map.of("status", "taken"));
        assertThat(tooFar.status()).isEqualTo(422);
        assertThat(put(u, "/v1/me/medications/" + id + "/doses/" + iso(dose), Map.of("status", "lost"))
                        .status())
                .isEqualTo(422);
    }

    @Test
    void stoppedMedicationsLeaveTheOverview() {
        String u = user();
        String id = post(u, "/v1/me/medications", med("Lisinopril", List.of("21:00")))
                .at("/id")
                .asString();
        Res stopped = patch(u, "/v1/me/medications/" + id, Map.of("status", "stopped"));
        assertThat(stopped.at("/status").asString()).isEqualTo("stopped");
        assertThat(stopped.at("/stopped_on").isNull()).isFalse();
        assertThat(get(u, "/v1/me/medications").at("/prescriptions").size()).isZero();
        assertThat(patch(u, "/v1/me/medications/" + id, Map.of("status", "active"))
                        .at("/stopped_on")
                        .isNull())
                .isTrue();
    }

    @Test
    void updateScheduleAndRefill() {
        String u = user();
        String id = post(u, "/v1/me/medications", med("Metformin", List.of("08:00")))
                .at("/id")
                .asString();
        Res changed = patch(
                u,
                "/v1/me/medications/" + id,
                Map.of(
                        "times", List.of("08:00", "20:00"),
                        "refill_due_on",
                                ZonedDateTime.now(CHICAGO)
                                        .plusDays(2)
                                        .toLocalDate()
                                        .toString()));
        assertThat(changed.status()).as(changed.text()).isEqualTo(200);
        assertThat(changed.at("/frequency").asString()).isEqualTo("Twice daily");
        assertThat(get(u, "/v1/me/medications")
                        .at("/prescriptions/0/refill_due")
                        .asBoolean())
                .isTrue();
        assertThat(patch(u, "/v1/me/medications/" + id, Map.of("times", List.of()))
                        .status())
                .isEqualTo(422);
        assertThat(patch(u, "/v1/me/medications/" + id, Map.of("dose", 1)).status())
                .isEqualTo(422);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"name\": \"x\", \"form\": \"tablet\", \"strength\": \"1 mg\", \"times\": []}",
                "{\"name\": \"x\", \"form\": \"pill\", \"strength\": \"1 mg\", \"times\": [\"08:00\"]}",
                "{\"name\": \"x\", \"form\": \"tablet\", \"strength\": \"1 mg\", \"times\": [\"08:00:30\"]}",
                "{\"name\": \"x\", \"form\": \"tablet\", \"strength\": \"1 mg\", \"times\": [\"08:00\", \"08:00\"]}",
                "{\"name\": \"x\", \"form\": \"tablet\", \"strength\": \"1 mg\", \"times\": [\"08:00\"], \"days\": [8]}",
                "{\"name\": \"\", \"form\": \"tablet\", \"strength\": \"1 mg\", \"times\": [\"08:00\"]}",
                "{\"name\": \"x\", \"form\": \"tablet\", \"strength\": \"1 mg\", \"times\": [\"08:00\"], \"user_id\": \"x\"}"
            })
    void medicationValidation(String body) {
        assertThat(post(user(), "/v1/me/medications", body).status()).isEqualTo(422);
    }

    @Test
    void medicationsArePrivate() {
        String u = user();
        String id = post(u, "/v1/me/medications", med("Metformin", List.of("08:00")))
                .at("/id")
                .asString();
        String other = user();
        assertThat(get(other, "/v1/me/medications/" + id).status()).isEqualTo(404);
        assertThat(patch(other, "/v1/me/medications/" + id, Map.of("name", "x")).status())
                .isEqualTo(404);
        assertThat(delete(other, "/v1/me/medications/" + id).status()).isEqualTo(404);
        assertThat(get(other, "/v1/me/medications").at("/prescriptions").size()).isZero();
        assertThat(delete(u, "/v1/me/medications/" + id).status()).isEqualTo(204);
    }

    // ---------------------------------------------------------------- appointments

    Res appointment(String u, OffsetDateTime at, String state) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "clinician", "Dr. Laura Bennett",
                "specialty", "Cardiology",
                "starts_at", at.toString(),
                "mode", "in_person",
                "location", "Trinity Valley Medical Center, Dallas"));
        if (state != null) {
            body.put("state", state);
        }
        return post(u, "/v1/me/appointments", body);
    }

    @Test
    void appointmentStatuses() {
        String u = user();
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(appointment(u, now.plusDays(2), "confirmed").status()).isEqualTo(201);
        appointment(u, now.plusDays(5), null);
        appointment(u, now.minusDays(3), "confirmed");
        appointment(u, now.plusDays(1), "cancelled");

        List<String> statuses = new ArrayList<>();
        get(u, "/v1/me/appointments")
                .body()
                .forEach(a -> statuses.add(a.get("status").asString()));
        assertThat(statuses).containsExactly("completed", "cancelled", "confirmed", "pending");

        Res next = get(u, "/v1/me/appointments?upcoming=true&limit=1");
        assertThat(next.body().size()).isEqualTo(1);
        assertThat(next.at("/0/status").asString()).isEqualTo("confirmed");
        assertThat(get(u, "/v1/me/appointments?upcoming=true").body().size()).isEqualTo(2);

        String pending = get(u, "/v1/me/appointments?upcoming=true").at("/1/id").asString();
        assertThat(patch(u, "/v1/me/appointments/" + pending, Map.of("state", "confirmed"))
                        .at("/status")
                        .asString())
                .isEqualTo("confirmed");
        assertThat(patch(u, "/v1/me/appointments/" + pending, Map.of("state", "booked"))
                        .status())
                .isEqualTo(422);
        assertThat(delete(token(), "/v1/me/appointments/" + pending).status()).isEqualTo(404);
        assertThat(delete(u, "/v1/me/appointments/" + pending).status()).isEqualTo(204);
    }

    // ---------------------------------------------------------------- conditions and emergency ID

    @Test
    void emergencyProfileIsAssembled() {
        String u = user();
        patch(
                u,
                "/v1/me/account",
                Map.of(
                        "first_name",
                        "Alex",
                        "last_name",
                        "Carter",
                        "date_of_birth",
                        "1994-04-17",
                        "sex",
                        "male",
                        "city",
                        "Dallas, TX"));
        post(u, "/v1/me/conditions", Map.of("label", "Penicillin", "kind", "allergy", "note", "severe"));
        post(u, "/v1/me/conditions", Map.of("label", "Type 2 diabetes", "kind", "chronic"));
        assertThat(post(u, "/v1/me/conditions", Map.of("label", "type 2 DIABETES", "kind", "chronic"))
                        .status())
                .isEqualTo(409);
        post(u, "/v1/me/medications", med("Metformin", List.of("08:00")));
        Res first = post(
                u,
                "/v1/me/emergency-contacts",
                Map.of("name", "Susan Carter", "relation", "Mother", "phone", "+12145550193"));
        assertThat(first.at("/primary").asBoolean()).isTrue(); // the first contact is primary
        Res second = post(
                u,
                "/v1/me/emergency-contacts",
                Map.of("name", "Ray Carter", "relation", "Father", "phone", "+12145550177", "primary", true));
        assertThat(second.at("/primary").asBoolean()).isTrue();
        patch(
                u,
                "/v1/me/emergency-profile",
                Map.of(
                        "organ_donor",
                        "yes",
                        "organ_donor_note",
                        "registered in Texas",
                        "preferred_hospital",
                        "Trinity Valley Medical Center, Dallas",
                        "notes",
                        "Wears a continuous glucose monitor on left arm."));

        Res e = get(u, "/v1/me/emergency-profile");
        assertThat(e.at("/name").asString()).isEqualTo("Alex Carter");
        assertThat(e.at("/sex").asString()).isEqualTo("Male");
        assertThat(e.at("/age").asInt()).isGreaterThanOrEqualTo(32);
        assertThat(e.at("/allergies").toString()).isEqualTo("[\"Penicillin (severe)\"]");
        assertThat(e.at("/conditions").toString()).isEqualTo("[\"Type 2 diabetes\"]");
        assertThat(e.at("/medications").toString()).isEqualTo("[\"Metformin 500 mg\"]");
        assertThat(e.at("/organ_donor").asString()).isEqualTo("Yes, registered in Texas");
        List<String> contacts = new ArrayList<>();
        e.at("/contacts")
                .forEach(c -> contacts.add(
                        c.get("name").asString() + ":" + c.get("primary").asBoolean()));
        assertThat(contacts).containsExactly("Ray Carter:true", "Susan Carter:false");
    }

    @Test
    void contactLimitsAndValidation() {
        String u = user();
        for (int i = 0; i < 5; i++) {
            assertThat(post(
                                    u,
                                    "/v1/me/emergency-contacts",
                                    Map.of("name", "Contact " + i, "relation", "Friend", "phone", "+1214555010" + i))
                            .status())
                    .isEqualTo(201);
        }
        Res sixth = post(
                u, "/v1/me/emergency-contacts", Map.of("name", "Six", "relation", "Friend", "phone", "+12145550109"));
        assertThat(sixth.status()).isEqualTo(409);
        assertThat(post(
                                user(),
                                "/v1/me/emergency-contacts",
                                Map.of("name", "X", "relation", "Friend", "phone", "214-555"))
                        .status())
                .isEqualTo(422);
    }

    @Test
    void privacySwitch() {
        String u = user();
        Res p = get(u, "/v1/me/privacy");
        assertThat(p.at("/emergency_on_lock_screen").asBoolean()).isFalse();
        assertThat(p.at("/devices/total").asInt()).isZero();
        assertThat(patch(u, "/v1/me/privacy", Map.of("emergency_on_lock_screen", true))
                        .at("/emergency_on_lock_screen")
                        .asBoolean())
                .isTrue();
        assertThat(get(u, "/v1/me/emergency-profile").at("/show_on_lock_screen").asBoolean())
                .isTrue();
        assertThat(patch(u, "/v1/me/privacy", Map.of("emergency_on_lock_screen", "yes"))
                        .status())
                .isEqualTo(422);
    }

    // ---------------------------------------------------------------- shared care views

    @Test
    void sharedCareNeedsTheRecordsScope() {
        String ownerEmail = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String owner = tokenFor(ownerEmail);
        String ownerId = get(owner, "/v1/me").at("/id").asString();
        post(owner, "/v1/me/medications", med("Metformin", List.of("08:00")));
        String viewerEmail = "viewer-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String viewer = tokenFor(viewerEmail);
        get(viewer, "/v1/me");
        post(
                owner,
                "/v1/me/shares",
                Map.of("email", viewerEmail, "label", "Mum", "kind", "family", "scopes", List.of("vitals")));
        assertThat(get(viewer, "/v1/users/" + ownerId + "/medications").status())
                .isEqualTo(403);
        String share = get(owner, "/v1/me/shares").at("/0/id").asString();
        delete(owner, "/v1/me/shares/" + share);
        post(
                owner,
                "/v1/me/shares",
                Map.of("email", viewerEmail, "label", "Mum", "kind", "family", "scopes", List.of("records")));
        JsonNode meds = get(viewer, "/v1/users/" + ownerId + "/medications").body();
        assertThat(meds.at("/prescriptions/0/name").asString()).isEqualTo("Metformin");
        assertThat(get(viewer, "/v1/users/" + ownerId + "/conditions").status()).isEqualTo(200);
        assertThat(get(viewer, "/v1/users/" + ownerId + "/appointments").status())
                .isEqualTo(200);
    }
}
