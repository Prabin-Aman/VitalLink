package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Account API: first-request creation, profile and targets (docs/05-api.md, Account). */
class AccountTest extends ApiTest {

    @Test
    void firstCallCreatesTheAccount() {
        String t = token(null, null, true, "Alex", "Carter");
        Res me = get(t, "/v1/me");
        assertThat(me.at("/display_name").asString()).isEqualTo("Alex Carter");
        assertThat(me.at("/timezone").asString()).isEqualTo("America/Chicago");
        assertThat(UUID.fromString(me.at("/id").asString()).version()).isEqualTo(7);
        // Same token again → same account.
        assertThat(get(t, "/v1/me").at("/id")).isEqualTo(me.at("/id"));
    }

    @Test
    void emailIsStoredLowercase() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Res me = get(tokenFor("Mixed.Case." + tag + "@Example.com"), "/v1/me");
        assertThat(me.at("/email").asString()).isEqualTo("mixed.case." + tag + "@example.com");
    }

    @Test
    void parallelFirstRequestsCreateOneAccount() throws Exception {
        String t = token();
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = IntStream.range(0, 6)
                    .mapToObj(i -> pool.submit(() -> get(t, "/v1/me")))
                    .toList();
            Set<Integer> statuses = new java.util.HashSet<>();
            Set<String> ids = new java.util.HashSet<>();
            for (var f : futures) {
                Res r = f.get();
                statuses.add(r.status());
                ids.add(r.at("/id").asString());
            }
            assertThat(statuses).containsExactly(200);
            assertThat(ids).hasSize(1);
        }
    }

    @Test
    void sameEmailNewIdentityIs409() {
        String email = "dup-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        assertThat(get(tokenFor(email), "/v1/me").status()).isEqualTo(200);
        Res res = get(tokenFor(email), "/v1/me");
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.code()).isEqualTo("email_in_use");
    }

    @Test
    void newAccountDefaults() {
        Res a = get(token(), "/v1/me/account");
        assertThat(a.at("/first_name").isNull()).isTrue();
        assertThat(a.at("/units").asString()).isEqualTo("imperial");
        assertThat(a.at("/theme").asString()).isEqualTo("system");
        assertThat(a.at("/language").asString()).isEqualTo("en-US");
        assertThat(a.at("/two_factor").asBoolean()).isFalse();
    }

    @Test
    void patchAccount() {
        String t = token();
        Map<String, Object> body = Map.of(
                "first_name", " Alex ",
                "last_name", "Carter",
                "phone", "+12145550148",
                "zip", "75201",
                "city", "Dallas, TX",
                "height_cm", 178,
                "date_of_birth", "1994-04-17",
                "units", "metric",
                "theme", "light",
                "timezone", "America/New_York");
        Res res = patch(t, "/v1/me/account", body);
        assertThat(res.status()).as(res.text()).isEqualTo(200);
        assertThat(res.at("/first_name").asString()).isEqualTo("Alex");
        assertThat(res.at("/phone").asString()).isEqualTo("+12145550148");
        assertThat(res.at("/height_cm").asDouble()).isEqualTo(178.0);
        assertThat(res.at("/units").asString()).isEqualTo("metric");
        assertThat(get(t, "/v1/me").at("/timezone").asString()).isEqualTo("America/New_York");
        // null clears an optional field
        assertThat(patch(t, "/v1/me/account", "{\"phone\": null}").at("/phone").isNull())
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"email\": \"new@example.com\"}", // email changes go through Supabase
                "{\"id\": \"0192a3f4-7c1e-7d2a-9b3c-5e6f7a8b9c0d\"}",
                "{\"member_since\": \"2020-01-01T00:00:00Z\"}",
                "{\"zip\": \"7520\"}",
                "{\"phone\": \"214-555-0148\"}",
                "{\"height_cm\": 300}",
                "{\"date_of_birth\": \"2999-01-01\"}",
                "{\"timezone\": \"Mars/Olympus\"}",
                "{\"language\": \"fr-FR\"}",
                "{\"language\": \"es-US\"}", // English only (V19)
                "{\"units\": null}"
            })
    void patchAccountRejects(String body) {
        Res res = patch(token(), "/v1/me/account", body);
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.code()).isEqualTo("invalid_request");
    }

    @Test
    void targets() {
        String t = token();
        assertThat(get(t, "/v1/me/targets").at("/daily_steps_goal").asInt()).isEqualTo(10000);
        Res res = patch(t, "/v1/me/targets", Map.of("daily_steps_goal", 8000, "stand_hours_goal", 10));
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.at("/daily_steps_goal").asInt()).isEqualTo(8000);
        assertThat(patch(t, "/v1/me/targets", Map.of("sleep_goal_min", 60)).status())
                .isEqualTo(422);
    }

    @Test
    void usersCannotSeeEachOther() {
        String a = token();
        String b = token();
        patch(a, "/v1/me/account", Map.of("first_name", "Ada"));
        assertThat(get(b, "/v1/me/account").at("/first_name").isNull()).isTrue();
    }

    @Test
    void deletingAUserCascadesToProfile() throws Exception {
        UUID id = UUID.fromString(get(token(), "/v1/me").at("/id").asString());
        try (var c = sql()) {
            var del = c.prepareStatement("DELETE FROM vitallink.users WHERE id = ?");
            del.setObject(1, id);
            del.executeUpdate();
            var count = c.prepareStatement("SELECT count(*) FROM vitallink.profiles WHERE user_id = ?");
            count.setObject(1, id);
            var rs = count.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
