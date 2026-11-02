package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vitallink.api.nutrition.OpenFoodFacts;
import com.vitallink.api.support.ApiTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Scan Barcode (Open Food Facts is a stand-in here), today's water entries, change password, units on /me. */
class NutritionExtrasTest extends ApiTest {

    @MockitoBean
    OpenFoodFacts openFoodFacts;

    static final OpenFoodFacts.Product YOGURT =
            new OpenFoodFacts.Product("Greek Yogurt Plain", "Fage", 170.0, 59, 10, 3.6, 0.4, 0.0);

    @Test
    void barcodeLooksUpOnceThenRemembersTheFood() {
        String u = user();
        when(openFoodFacts.lookup("5201054017593")).thenReturn(Optional.of(YOGURT));
        Res first = get(u, "/v1/foods/barcode/5201054017593");
        assertThat(first.status()).as(first.text()).isEqualTo(200);
        assertThat(first.at("/name").asString()).isEqualTo("Greek Yogurt Plain");
        assertThat(first.at("/kcal").asDouble()).isEqualTo(59);
        assertThat(first.at("/serving_g").asDouble()).isEqualTo(170);

        // The second scan is answered from the saved food, and search finds it too.
        Res again = get(u, "/v1/foods/barcode/5201054017593");
        assertThat(again.at("/id").asString()).isEqualTo(first.at("/id").asString());
        verify(openFoodFacts, times(1)).lookup("5201054017593");
        assertThat(get(u, "/v1/foods/search?q=greek").text())
                .contains(first.at("/id").asString());

        // Another person's scan doesn't see this user's food: it asks Open Food Facts itself.
        get(user(), "/v1/foods/barcode/5201054017593");
        verify(openFoodFacts, times(2)).lookup("5201054017593");
    }

    @Test
    void unknownAndMalformedBarcodes() {
        String u = user();
        when(openFoodFacts.lookup("00000000")).thenReturn(Optional.empty());
        assertThat(get(u, "/v1/foods/barcode/00000000").code()).isEqualTo("food_not_found");
        when(openFoodFacts.lookup("11111111")).thenThrow(new IllegalStateException("down"));
        assertThat(get(u, "/v1/foods/barcode/11111111").status()).isEqualTo(503);
        assertThat(get(u, "/v1/foods/barcode/abc").status()).isEqualTo(422);
        verify(openFoodFacts, never()).lookup("abc");
    }

    @Test
    void openFoodFactsLabelsAreReadPer100Grams() throws Exception {
        var node = tools.jackson.databind.json.JsonMapper.builder().build().readTree("""
                {"product_name": "Oat Milk", "brands": "Oatly, Oatly AB", "serving_quantity": 250,
                 "nutriments": {"energy-kcal_100g": 46, "proteins_100g": 1, "carbohydrates_100g": 6.6,
                                "fat_100g": 1.5, "fiber_100g": 0.8}}
                """);
        var method = OpenFoodFacts.class.getDeclaredMethod("product", tools.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<OpenFoodFacts.Product> p = (Optional<OpenFoodFacts.Product>) method.invoke(null, node);
        assertThat(p).contains(new OpenFoodFacts.Product("Oat Milk", "Oatly", 250.0, 46, 1, 6.6, 1.5, 0.8));
    }

    @Test
    void todaysWaterEntriesCanBeListedAndRemoved() {
        String u = user();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        Res a = post(
                u,
                "/v1/me/hydration",
                Map.of("ml", 250, "at", now.minusMinutes(30).toString()));
        Res b = post(u, "/v1/me/hydration", Map.of("ml", 500, "at", now.toString()));
        Res today = get(u, "/v1/me/hydration");
        assertThat(today.status()).isEqualTo(200);
        assertThat(today.at("/0/id").asString()).isEqualTo(b.at("/id").asString()); // newest first
        assertThat(today.at("/1/ml").asInt()).isEqualTo(250);
        assertThat(delete(u, "/v1/me/hydration/" + a.at("/id").asString()).status())
                .isEqualTo(204);
        assertThat(get(u, "/v1/me/hydration").body().size()).isEqualTo(1);
        assertThat(get(user(), "/v1/me/hydration").body().size()).isZero();
    }

    @Test
    void changePasswordNeedsTheCurrentOne() {
        String email = "chg-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        Map<String, Object> up = new HashMap<>(
                Map.of("email", email, "password", "Correct-horse-42", "first_name", "A", "last_name", "B"));
        String auth = "Bearer "
                + post(null, "/v1/auth/sign-up", up).at("/access_token").asString();

        Res wrong = post(
                auth,
                "/v1/auth/change-password",
                Map.of("current_password", "nope", "new_password", "Another-pass-77!"));
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.code()).isEqualTo("wrong_password");
        assertThat(post(
                                auth,
                                "/v1/auth/change-password",
                                Map.of("current_password", "Correct-horse-42", "new_password", "short"))
                        .status())
                .isEqualTo(422);
        assertThat(post(
                                auth,
                                "/v1/auth/change-password",
                                Map.of("current_password", "Correct-horse-42", "new_password", "Another-pass-77!"))
                        .status())
                .isEqualTo(204);
        assertThat(post(null, "/v1/auth/sign-in", Map.of("email", email, "password", "Correct-horse-42"))
                        .status())
                .isEqualTo(401);
        assertThat(post(null, "/v1/auth/sign-in", Map.of("email", email, "password", "Another-pass-77!"))
                        .status())
                .isEqualTo(200);
    }

    @Test
    void meCarriesTheUnitsSetting() {
        String u = user();
        assertThat(get(u, "/v1/me").at("/units").asString()).isEqualTo("imperial");
        patch(u, "/v1/me/account", Map.of("units", "metric"));
        assertThat(get(u, "/v1/me").at("/units").asString()).isEqualTo("metric");
        verify(openFoodFacts, never()).lookup(anyString());
    }
}
