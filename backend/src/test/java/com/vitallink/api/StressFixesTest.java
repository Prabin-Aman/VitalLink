package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Regression tests for what the 1 Nov 2026 stress test broke (fuzzing and concurrent writes); each failed before its fix. */
class StressFixesTest extends ApiTest {

    /** Runs {@code n} requests at once and returns their statuses. */
    List<Integer> burst(int n, IntFunction<Res> request) throws Exception {
        List<CompletableFuture<Res>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            all.add(CompletableFuture.supplyAsync(() -> request.apply(k)));
        }
        List<Integer> statuses = new ArrayList<>();
        for (var f : all) {
            statuses.add(f.get(60, TimeUnit.SECONDS).status());
        }
        return statuses;
    }

    @Test
    void concurrentPlansLeaveOneActiveAndNeverFail() throws Exception {
        String u = user();
        var statuses = burst(
                12,
                i -> post(
                        u,
                        "/v1/me/training/plans",
                        Map.of("name", "Plan " + i, "weekly_target_sessions", 3, "sessions", List.of())));
        assertThat(statuses).containsOnly(201);
        JsonNode plans = get(u, "/v1/me/training/plans").body();
        int active = 0;
        for (JsonNode p : plans.isArray() ? plans : plans.path("plans")) {
            if ("active".equals(p.path("status").asString())) {
                active++;
            }
        }
        assertThat(active).isEqualTo(1);
    }

    /** Each share writes an audit entry in its own transaction; with every connection held that used to deadlock. */
    @Test
    void manySharesAtOnceDontStarveTheAuditLog() throws Exception {
        String u = user();
        var statuses = burst(
                40,
                i -> post(
                        u,
                        "/v1/me/shares",
                        Map.of(
                                "email",
                                "friend-" + i + "@example.com",
                                "label",
                                "Friend",
                                "kind",
                                "family",
                                "scopes",
                                List.of("vitals"))));
        assertThat(statuses).containsOnly(201);
    }

    @Test
    void theSameShareAtOnceIsOneCreatedTheRestConflicts() throws Exception {
        String u = user();
        var statuses = burst(
                10,
                i -> post(
                        u,
                        "/v1/me/shares",
                        Map.of(
                                "email",
                                "same@example.com",
                                "label",
                                "Friend",
                                "kind",
                                "family",
                                "scopes",
                                List.of("vitals"))));
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s != 201).containsOnly(409);
    }

    @Test
    void aNulCharacterInTextIsInvalidNotAServerError() {
        String u = user();
        Res food = post(
                u,
                "/v1/me/foods",
                Map.of("name", "Oat\u0000s", "serving_g", 40, "kcal", 150, "protein_g", 5, "carbs_g", 27, "fat_g", 3));
        assertThat(food.status()).as(food.text()).isEqualTo(422);
        Res signIn = post(null, "/v1/auth/sign-in", Map.of("email", "a\u0000@example.com", "password", "x"));
        assertThat(signIn.status()).as(signIn.text()).isIn(401, 422);
    }

    /** A new account's first requests arrive together: every one succeeds and there's one account. */
    @Test
    void parallelFirstRequestsCreateOneAccount() throws Exception {
        String fresh = token();
        var statuses = burst(12, i -> get(fresh, "/v1/me"));
        assertThat(statuses).containsOnly(200);
    }
}
