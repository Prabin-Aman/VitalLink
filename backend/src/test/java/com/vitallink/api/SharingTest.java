package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.core.consent.ConsentGate;
import com.vitallink.api.support.ApiTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Sharing end to end: grant → read → revoke → refused, all in the owner's access log (docs/07 §7.3). */
class SharingTest extends ApiTest {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    record Account(String auth, String id, String email, UUID subject) {}

    static String email() {
        return "viewer-" + UUID.randomUUID().toString().substring(0, 10) + "@example.com";
    }

    Account account(String email, boolean verified) {
        UUID sub = UUID.randomUUID();
        String auth = token(email, sub, verified, null, null);
        return new Account(auth, get(auth, "/v1/me").at("/id").asString(), email, sub);
    }

    Account account() {
        return account(email(), true);
    }

    void steps(String auth, int value) {
        long start = Math.floorDiv(Instant.now().getEpochSecond(), 300) * 300 - 600;
        String at = Instant.ofEpochSecond(start).atOffset(ZoneOffset.UTC).toString();
        Map<String, Object> body = Map.of(
                "idempotency_key", UUID.randomUUID().toString(),
                "platform", "healthkit",
                "buckets",
                        List.of(
                                Map.of("metric", "steps", "start", at, "value", value),
                                Map.of("metric", "heart_rate", "start", at, "value", 70)));
        assertThat(post(auth, "/v1/me/health/ingest", body).status()).isEqualTo(202);
    }

    Res share(String owner, String email, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("label", "Dr. Laura Bennett");
        body.put("kind", "doctor");
        body.put("scopes", List.of("activity"));
        body.putAll(extra);
        return post(owner, "/v1/me/shares", body);
    }

    Res share(String owner, String email) {
        return share(owner, email, Map.of());
    }

    Res read(String viewer, String ownerId, String metric) {
        // Two days: the steps are logged ten minutes ago, which is yesterday just after midnight.
        return get(viewer, "/v1/users/" + ownerId + "/health/series/" + metric + "?days=2");
    }

    Res read(String viewer, String ownerId) {
        return read(viewer, ownerId, "steps");
    }

    Account owner;
    Account viewer;

    @BeforeEach
    void pair() {
        owner = account();
        viewer = account();
        steps(owner.auth(), 1234);
    }

    // ---------------------------------------------------------------- the full story

    @Test
    void grantReadRevokeRefusedAndLogged() {
        // Before any share: 404, so the account's existence stays hidden.
        assertThat(read(viewer.auth(), owner.id()).status()).isEqualTo(404);

        Res s = share(owner.auth(), viewer.email(), Map.of("purpose", "Treatment"));
        assertThat(s.at("/active").asBoolean()).isTrue();
        assertThat(s.at("/scopes").toString()).isEqualTo("[\"activity\"]");

        Res res = read(viewer.auth(), owner.id());
        assertThat(res.status()).isEqualTo(200);
        assertThat(HealthTest.sum(res.at("/points"))).isEqualTo(1234);

        // Activity was shared; vitals were not.
        Res denied = read(viewer.auth(), owner.id(), "heart_rate");
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("scope_not_granted");

        assertThat(delete(owner.auth(), "/v1/me/shares/" + s.at("/id").asString())
                        .status())
                .isEqualTo(204);
        Res after = read(viewer.auth(), owner.id());
        assertThat(after.status()).isEqualTo(403);
        assertThat(after.code()).isEqualTo("revoked");

        Res log = get(owner.auth(), "/v1/me/access-log");
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var e = log.at("/" + i);
            rows.add(e.get("status").asString() + "|" + e.get("accessed").asString() + "|"
                    + e.get("purpose").asString());
        }
        assertThat(rows)
                .containsExactly(
                        "blocked|None — blocked|Share revoked",
                        "blocked|None — blocked|Not part of the share",
                        "authorised|Activity|Treatment",
                        "blocked|None — blocked|No share");
        assertThat(log.at("/2/actor").asString()).isEqualTo("Dr. Laura Bennett");
        assertThat(log.at("/1/actor").asString())
                .isEqualTo("Dr. Laura Bennett"); // a refused scope still names the share
        assertThat(log.at("/2/actor_detail").asString()).isEqualTo("Doctor · " + viewer.email());
        // Each row names the share it went through, and says it has since been stopped.
        assertThat(log.at("/2/share_id").asString()).isEqualTo(s.at("/id").asString());
        assertThat(log.at("/2/share_active").asBoolean()).isFalse();
        assertThat(log.at("/3/share_id").isNull()).isTrue();
    }

    @Test
    void sharedWithMe() {
        patch(owner.auth(), "/v1/me/account", Map.of("first_name", "Alex", "last_name", "Carter"));
        Res s = share(owner.auth(), viewer.email(), Map.of("scopes", List.of("activity", "sleep")));
        Res received = get(viewer.auth(), "/v1/me/shared-with-me");
        assertThat(received.body().size()).isEqualTo(1);
        assertThat(received.at("/0/owner_id").asString()).isEqualTo(owner.id());
        assertThat(received.at("/0/owner_name").asString()).isEqualTo("Alex Carter");
        assertThat(received.at("/0/scopes").toString()).isEqualTo("[\"activity\",\"sleep\"]");
        delete(owner.auth(), "/v1/me/shares/" + s.at("/id").asString());
        assertThat(get(viewer.auth(), "/v1/me/shared-with-me").body().size()).isZero();
    }

    @Test
    void sharedTrainingNeedsActivity() {
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        post(
                owner.auth(),
                "/v1/me/workouts",
                Map.of("type", "walk", "title", "Walk", "started_at", now, "duration_s", 600));
        assertThat(get(viewer.auth(), "/v1/users/" + owner.id() + "/workouts").status())
                .isEqualTo(404);
        share(owner.auth(), viewer.email(), Map.of("scopes", List.of("sleep")));
        assertThat(get(viewer.auth(), "/v1/users/" + owner.id() + "/training").status())
                .isEqualTo(403);
        String id = get(owner.auth(), "/v1/me/shares").at("/0/id").asString();
        delete(owner.auth(), "/v1/me/shares/" + id);
        share(owner.auth(), viewer.email(), Map.of("scopes", List.of("activity")));
        Res workouts = get(viewer.auth(), "/v1/users/" + owner.id() + "/workouts");
        assertThat(workouts.at("/0/title").asString()).isEqualTo("Walk");
        assertThat(workouts.body().size()).isEqualTo(1);
        assertThat(get(viewer.auth(), "/v1/users/" + owner.id() + "/training")
                        .at("/total_sessions")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void sharedDashboardHasNoReadinessScore() {
        share(owner.auth(), viewer.email(), Map.of("scopes", List.of("vitals")));
        Res dash = get(viewer.auth(), "/v1/users/" + owner.id() + "/health/dashboard");
        assertThat(dash.at("/health_score").isNull()).isTrue();
        assertThat(dash.at("/metrics/0/latest").asDouble()).isEqualTo(70);
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void unconfirmedEmailGets404() {
        String e = email();
        share(owner.auth(), e);
        Account unconfirmed = account(e, false);
        assertThat(read(unconfirmed.auth(), owner.id()).status()).isEqualTo(404);
    }

    @Test
    void strangerAndUnknownUserLookTheSame() {
        Account stranger = account();
        Res a = read(stranger.auth(), owner.id());
        Res b = read(stranger.auth(), UUID.randomUUID().toString());
        assertThat(a.status()).isEqualTo(404).isEqualTo(b.status());
        assertThat(a.code()).isEqualTo("not_found").isEqualTo(b.code());
    }

    @Test
    void notYetValid() {
        share(
                owner.auth(),
                viewer.email(),
                Map.of(
                        "starts_at",
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString()));
        Res res = read(viewer.auth(), owner.id());
        assertThat(res.status()).isEqualTo(403);
        assertThat(res.code()).isEqualTo("not_yet_valid");
    }

    @Test
    void expired() throws Exception {
        Res s = share(owner.auth(), viewer.email());
        try (var c = sql();
                var st = c.prepareStatement("UPDATE vitallink.consents SET starts_at = now() - interval '2 days', "
                        + "expires_at = now() - interval '1 second' WHERE id = ?")) {
            st.setObject(1, UUID.fromString(s.at("/id").asString()));
            st.executeUpdate();
        }
        Res res = read(viewer.auth(), owner.id());
        assertThat(res.status()).isEqualTo(403);
        assertThat(res.code()).isEqualTo("expired");
        assertThat(get(owner.auth(), "/v1/me/shares").at("/0/active").asBoolean())
                .isFalse();
        // An expired share doesn't block sharing again with the same person.
        assertThat(share(owner.auth(), viewer.email()).status()).isEqualTo(201);
        assertThat(read(viewer.auth(), owner.id()).status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- binding

    @Test
    void grantBindsToTheAccountOnFirstRead() throws Exception {
        Res s = share(owner.auth(), viewer.email());
        assertThat(read(viewer.auth(), owner.id()).status()).isEqualTo(200);
        try (var c = sql();
                var st = c.prepareStatement("SELECT grantee_user_id FROM vitallink.consents WHERE id = ?")) {
            st.setObject(1, UUID.fromString(s.at("/id").asString()));
            var rs = st.executeQuery();
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(viewer.id());
        }
        // The viewer changes their email in Supabase: the share follows the account.
        String moved = token(email(), viewer.subject(), true, null, null);
        assertThat(read(moved, owner.id()).status()).isEqualTo(200);

        // Someone who later signs up with the old address gets nothing.
        Account newcomer = account(viewer.email(), true);
        assertThat(read(newcomer.auth(), owner.id()).status()).isEqualTo(404);
    }

    @Test
    void refusedReadsAreAuditedDespiteTheRollback() throws Exception {
        assertThat(read(viewer.auth(), owner.id()).status()).isEqualTo(404);
        try (var c = sql();
                var st = c.prepareStatement("SELECT count(*) FROM vitallink.audit_log WHERE actor_user_id = ? "
                        + "AND subject_user_id = ? AND outcome = 'blocked' AND reason = 'no_grant'")) {
            st.setObject(1, UUID.fromString(viewer.id()));
            st.setObject(2, UUID.fromString(owner.id()));
            var rs = st.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- creating shares

    @Test
    void cannotShareTwiceOrWithSelf() {
        assertThat(share(owner.auth(), viewer.email()).status()).isEqualTo(201);
        assertThat(share(owner.auth(), viewer.email().toUpperCase()).status()).isEqualTo(409);
        String mine = get(owner.auth(), "/v1/me").at("/email").asString();
        assertThat(share(owner.auth(), mine).code()).isEqualTo("cannot_share_with_self");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"kind\": \"hospital\"}", // consumers only
                "{\"kind\": \"app\"}", // no third parties
                "{\"scopes\": []}",
                "{\"scopes\": [\"blood_type\"]}",
                "{\"email\": \"not-an-email\"}",
                "{\"expires_at\": \"2020-01-01T00:00:00Z\"}",
                "{\"grantee_user_id\": \"0192a3f4-7c1e-7d2a-9b3c-5e6f7a8b9c0d\"}" // can't bind from the client
            })
    void shareValidation(String change) throws Exception {
        Map<String, Object> full = new HashMap<>(Map.of(
                "email", viewer.email(), "label", "Dr. Bennett", "kind", "doctor", "scopes", List.of("activity")));
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = json.readValue(change, Map.class);
        full.putAll(extra);
        assertThat(post(owner.auth(), "/v1/me/shares", full).status()).isEqualTo(422);
    }

    @Test
    void ownerCannotRevokeSomeoneElsesShare() {
        Res s = share(owner.auth(), viewer.email());
        assertThat(delete(viewer.auth(), "/v1/me/shares/" + s.at("/id").asString())
                        .status())
                .isEqualTo(404);
    }

    // ---------------------------------------------------------------- guardrails

    private Map<RequestMappingInfo, HandlerMethod> shared() {
        Map<RequestMappingInfo, HandlerMethod> out = new HashMap<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (info.getPatternValues().stream().anyMatch(p -> p.startsWith("/v1/users/"))) {
                out.put(info, method);
            }
        });
        return out;
    }

    @Test
    void everySharedRoutePassesTheConsentGate() {
        assertThat(shared()).as("shared routes not found").hasSizeGreaterThanOrEqualTo(4);
        shared().forEach((info, method) -> assertThat(Arrays.stream(method.getMethodParameters())
                        .anyMatch(p -> p.getParameterType() == ConsentGate.class))
                .as(info + " skips the consent gate")
                .isTrue());
    }

    @Test
    void sharedRoutesAreReadOnly() {
        shared().forEach((info, method) -> assertThat(info.getMethodsCondition().getMethods())
                .as(info.toString())
                .containsOnly(RequestMethod.GET));
    }

    @Test
    void everyApiRouteIdentifiesTheCaller() {
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (info.getPatternValues().stream().anyMatch(p -> p.startsWith("/v1/"))) {
                assertThat(Arrays.stream(method.getMethodParameters())
                                .anyMatch(p -> p.getParameterType() == com.vitallink.api.account.CurrentUser.class
                                        || p.getParameterType() == ConsentGate.class))
                        .as(info + " has no signed-in caller")
                        .isTrue();
            }
        });
    }

    @Test
    void noProviderOrAdminRoutes() {
        List<String> banned = List.of("hospital", "clinic", "provider", "admin");
        List<String> paths = new ArrayList<>();
        mappings.getHandlerMethods().keySet().forEach(info -> paths.addAll(info.getPatternValues()));
        assertThat(paths).hasSizeGreaterThan(20);
        assertThat(paths)
                .noneMatch(p -> banned.stream().anyMatch(w -> p.toLowerCase().contains(w)));
    }
}
