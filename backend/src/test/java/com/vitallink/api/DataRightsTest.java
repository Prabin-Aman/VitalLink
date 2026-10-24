package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.core.security.DevTokens;
import com.vitallink.api.core.userdata.UserData;
import com.vitallink.api.support.ApiTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Export and delete (docs/06 privacy commitments). */
class DataRightsTest extends ApiTest {

    @Autowired
    List<UserData> modules;

    String freshToken(String email, UUID subject, long secondsAgo) {
        return "Bearer "
                + DevTokens.mint(
                        settings,
                        subject,
                        email,
                        true,
                        null,
                        null,
                        3600,
                        Instant.now().getEpochSecond() - secondsAgo);
    }

    @Test
    void exportHasEverythingTheUserOwns() {
        String u = user();
        patch(u, "/v1/me/account", Map.of("first_name", "Ada"));
        post(u, "/v1/me/health/manual", Map.of("metric", "weight", "value", 70.2));
        post(u, "/v1/me/conditions", Map.of("label", "Asthma", "kind", "chronic"));
        Res export = get(u, "/v1/me/export");
        assertThat(export.status()).isEqualTo(200);
        assertThat(export.at("/format").asString()).isEqualTo("vitallink-export/1");
        assertThat(export.at("/profile/0/first_name").asString()).isEqualTo("Ada");
        assertThat(export.at("/health_readings/0/metric").asString()).isEqualTo("weight");
        assertThat(export.at("/conditions/0/label").asString()).isEqualTo("Asthma");
        assertThat(export.at("/workouts").size()).isZero();
        // Someone else's data never appears.
        assertThat(get(user(), "/v1/me/export").at("/conditions").size()).isZero();
    }

    @Test
    void everyUserOwnedTableIsExported() throws Exception {
        Set<String> owned = new HashSet<>();
        try (var c = sql();
                var rs = c.createStatement().executeQuery("""
                                SELECT DISTINCT table_name FROM information_schema.columns
                                WHERE table_schema = 'vitallink'
                                  AND column_name IN ('user_id', 'owner_user_id', 'grantor_id', 'medication_id',
                                                      'workout_id', 'plan_id', 'meal_id', 'panel_id', 'record_id')
                                """)) {
            while (rs.next()) {
                owned.add(rs.getString(1));
            }
        }
        String all = String.join(
                "\n",
                modules.stream()
                        .flatMap(m -> m.exportQueries().values().stream())
                        .toList());
        List<String> missing = new ArrayList<>();
        for (String table : owned) {
            if (!all.contains("vitallink." + table + " ") && !all.contains("vitallink." + table + "\n")) {
                missing.add(table);
            }
        }
        assertThat(missing)
                .as("tables with user data that /me/export leaves out")
                .isEmpty();
    }

    @Test
    void deleteNeedsARecentSignIn() {
        String email = "old-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        UUID subject = UUID.randomUUID();
        String stale = freshToken(email, subject, 20 * 60);
        get(stale, "/v1/me");
        Res res = delete(stale, "/v1/me");
        assertThat(res.status()).isEqualTo(403);
        assertThat(res.code()).isEqualTo("reauth_required");
    }

    @Test
    void deleteRemovesTheAccountAndRevokesSharesItReceived() throws Exception {
        String ownerAuth = user();
        String viewerEmail = "leaving-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        UUID viewerSubject = UUID.randomUUID();
        String viewer = freshToken(viewerEmail, viewerSubject, 0);
        String viewerId = get(viewer, "/v1/me").at("/id").asString();
        post(viewer, "/v1/me/conditions", Map.of("label", "Asthma", "kind", "chronic"));
        String ownerId = get(ownerAuth, "/v1/me").at("/id").asString();
        post(
                ownerAuth,
                "/v1/me/shares",
                Map.of("email", viewerEmail, "label", "Sam", "kind", "family", "scopes", List.of("activity")));
        // The viewer reads once, binding the share to their account.
        assertThat(get(viewer, "/v1/users/" + ownerId + "/workouts").status()).isEqualTo(200);

        assertThat(delete(viewer, "/v1/me").status()).isEqualTo(204);

        try (var c = sql()) {
            var st = c.prepareStatement("SELECT count(*) FROM vitallink.users WHERE id = ?");
            st.setObject(1, UUID.fromString(viewerId));
            var rs = st.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isZero();
            var conditions = c.prepareStatement("SELECT count(*) FROM vitallink.conditions WHERE user_id = ?");
            conditions.setObject(1, UUID.fromString(viewerId));
            rs = conditions.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
        // The owner's share is gone (revoked), and a new sign-up with the same email inherits nothing.
        assertThat(get(ownerAuth, "/v1/me/shares").body().size()).isZero();
        String newcomer = freshToken(viewerEmail, UUID.randomUUID(), 0);
        get(newcomer, "/v1/me");
        assertThat(get(newcomer, "/v1/users/" + ownerId + "/workouts").status()).isEqualTo(404);
    }
}
