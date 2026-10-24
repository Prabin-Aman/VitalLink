package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.nutrition.Foods;
import com.vitallink.api.support.ApiTest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Nutrition: food catalogue, meals with copied macros, water, targets, the day view. */
class NutritionTest extends ApiTest {

    static String today(int hour, int minute) {
        return iso(todayAt(hour, minute, 0));
    }

    List<String> search(String u, String q) {
        List<String> names = new ArrayList<>();
        get(u, "/v1/foods/search?q=" + q)
                .body()
                .forEach(f -> names.add(f.get("name").asString()));
        return names;
    }

    @Test
    void foodCatalogueMatchesDatabase() throws Exception {
        Set<String> stored = new HashSet<>();
        try (var c = sql();
                var rs = c.createStatement()
                        .executeQuery(
                                "SELECT id, name, kcal, protein_g FROM vitallink.foods WHERE source = 'seeded'")) {
            while (rs.next()) {
                stored.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getDouble(3) + "|" + rs.getDouble(4));
            }
        }
        Set<String> code = new HashSet<>();
        Foods.ALL.forEach(f -> code.add(f.id() + "|" + f.name() + "|" + f.kcal() + "|" + f.protein()));
        assertThat(stored).isEqualTo(code);
    }

    @Test
    void searchFindsSeededAndOwnFoodsOnly() {
        String u = user();
        assertThat(search(u, "yog")).containsExactly("Greek yoghurt");
        Res mine = post(
                u,
                "/v1/me/foods",
                Map.of("name", "Grandma's yoghurt cake", "kcal", 320, "protein_g", 6, "carbs_g", 45, "fat_g", 12));
        assertThat(mine.status()).isEqualTo(201);
        assertThat(search(u, "yog")).containsExactly("Greek yoghurt", "Grandma's yoghurt cake");
        assertThat(search(token(), "yog")).containsExactly("Greek yoghurt");
        assertThat(search(u, "%25%25")).isEmpty(); // "%%": wildcards are matched literally
    }

    @Test
    void mealCopiesMacrosFromFoods() {
        String u = user();
        String yoghurt = get(u, "/v1/foods/search?q=greek").at("/0/id").asString();
        Res meal = post(
                u,
                "/v1/me/meals",
                Map.of(
                        "slot", "breakfast",
                        "eaten_at", today(7, 10),
                        "items",
                                List.of(
                                        Map.of("food_id", yoghurt, "quantity_g", 200),
                                        Map.of(
                                                "label",
                                                "Coffee with milk",
                                                "quantity_g",
                                                250,
                                                "kcal",
                                                40,
                                                "protein_g",
                                                2,
                                                "carbs_g",
                                                3,
                                                "fat_g",
                                                2))));
        assertThat(meal.status()).as(meal.text()).isEqualTo(201);
        assertThat(meal.at("/name").asString()).isEqualTo("Breakfast");
        assertThat(meal.at("/items").toString()).isEqualTo("[\"Greek yoghurt\",\"Coffee with milk\"]");
        assertThat(meal.at("/kcal").asInt()).isEqualTo(186); // 146 + 40
        assertThat(meal.at("/protein_g").asInt()).isEqualTo(22); // 19.8 + 2
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"quantity_g\": 100}", // neither a food nor macros
                "{\"food_id\": \"BANANA\", \"quantity_g\": 100, \"kcal\": 1}", // macros come from the food
                "{\"food_id\": \"BANANA\", \"quantity_g\": 0}",
                "{\"label\": \"x\", \"quantity_g\": 10, \"kcal\": -5, \"protein_g\": 0, \"carbs_g\": 0, \"fat_g\": 0}"
            })
    void mealRejects(String item) {
        String body = "{\"slot\": \"snack\", \"eaten_at\": \"" + today(15, 0) + "\", \"items\": ["
                + item.replace("BANANA", Foods.foodId("Banana").toString()) + "]}";
        assertThat(post(user(), "/v1/me/meals", body).status()).isEqualTo(422);
    }

    @Test
    void cannotUseSomeoneElsesFood() {
        String u = user();
        String other = user();
        String theirs = post(
                        other,
                        "/v1/me/foods",
                        Map.of("name", "Secret", "kcal", 1, "protein_g", 0, "carbs_g", 0, "fat_g", 0))
                .at("/id")
                .asString();
        Map<String, Object> body = Map.of(
                "slot",
                "snack",
                "eaten_at",
                today(15, 0),
                "items",
                List.of(Map.of("food_id", theirs, "quantity_g", 50)));
        assertThat(post(u, "/v1/me/meals", body).status()).isEqualTo(404);
    }

    @Test
    void dayView() {
        String u = user();
        String banana = Foods.foodId("Banana").toString();
        post(
                u,
                "/v1/me/meals",
                Map.of(
                        "slot",
                        "snack",
                        "eaten_at",
                        today(0, 30),
                        "items",
                        List.of(Map.of("food_id", banana, "quantity_g", 118))));
        String yesterday = iso(ZonedDateTime.now(CHICAGO).minusDays(1));
        post(
                u,
                "/v1/me/meals",
                Map.of(
                        "slot",
                        "dinner",
                        "eaten_at",
                        yesterday,
                        "items",
                        List.of(Map.of("food_id", banana, "quantity_g", 1000))));
        post(u, "/v1/me/hydration", Map.of("ml", 500, "at", today(0, 40)));
        patch(u, "/v1/me/nutrition/targets", Map.of("kcal", 2400, "weekly_target_kg", -0.4));

        Res d = get(u, "/v1/me/nutrition/day");
        // one banana; yesterday's isn't counted
        assertThat(List.of(
                        d.at("/kcal").asInt(),
                        d.at("/carbs_g").asInt(),
                        d.at("/fibre_g").asInt()))
                .containsExactly(105, 27, 3);
        assertThat(d.at("/kcal_goal").asInt()).isEqualTo(2400);
        assertThat(d.at("/weekly_target_kg").asDouble()).isEqualTo(-0.4);
        assertThat(d.at("/water_ml").asInt()).isEqualTo(500);
        assertThat(d.at("/base_burn_kcal").isNull()).isTrue(); // no height, weight, birth date or sex yet
        assertThat(d.at("/meals").size()).isEqualTo(1);
        assertThat(d.at("/meals/0/name").asString()).isEqualTo("Snack");
        assertThat(OffsetDateTime.parse(d.at("/date").asString())
                        .atZoneSameInstant(CHICAGO)
                        .toLocalTime())
                .isEqualTo(LocalTime.MIDNIGHT);
        // An explicit date reads that day.
        assertThat(get(u, "/v1/me/nutrition/day?date=" + LocalDate.now(CHICAGO).minusDays(1))
                        .at("/meals/0/name")
                        .asString())
                .isEqualTo("Dinner");
    }

    @Test
    void baseBurnNeedsAFullProfile() {
        String u = user();
        patch(u, "/v1/me/account", Map.of("height_cm", 178, "date_of_birth", "1994-04-17", "sex", "male"));
        post(u, "/v1/me/health/manual", Map.of("metric", "weight", "value", 72.4));
        int burn = get(u, "/v1/me/nutrition/day").at("/base_burn_kcal").asInt();
        long age = ChronoUnit.DAYS.between(LocalDate.of(1994, 4, 17), LocalDate.now(CHICAGO)) / 365;
        assertThat(burn).isEqualTo((int) Math.rint(10 * 72.4 + 6.25 * 178 - 5 * age + 5));
    }

    @Test
    void targetsDefaultsAndValidation() {
        String u = user();
        Res t = get(u, "/v1/me/nutrition/targets");
        assertThat(List.of(
                        t.at("/kcal").asInt(),
                        t.at("/water_ml").asInt(),
                        t.at("/glass_ml").asInt()))
                .containsExactly(2200, 2500, 250);
        assertThat(patch(u, "/v1/me/nutrition/targets", Map.of("kcal", 100)).status())
                .isEqualTo(422);
        assertThat(patch(u, "/v1/me/nutrition/targets", Map.of("weekly_target_kg", 3))
                        .status())
                .isEqualTo(422);
    }

    @Test
    void deleteMealAndWaterAreScoped() {
        String u = user();
        String meal = post(
                        u,
                        "/v1/me/meals",
                        Map.of(
                                "slot", "lunch",
                                "eaten_at", today(1, 0),
                                "items",
                                        List.of(Map.of(
                                                "food_id", Foods.foodId("Apple").toString(), "quantity_g", 180))))
                .at("/id")
                .asString();
        String water = post(u, "/v1/me/hydration", Map.of("ml", 300)).at("/id").asString();
        String other = token();
        assertThat(delete(other, "/v1/me/meals/" + meal).status()).isEqualTo(404);
        assertThat(delete(other, "/v1/me/hydration/" + water).status()).isEqualTo(404);
        assertThat(delete(u, "/v1/me/meals/" + meal).status()).isEqualTo(204);
        assertThat(delete(u, "/v1/me/hydration/" + water).status()).isEqualTo(204);
        assertThat(delete(u, "/v1/me/meals/" + UUID.randomUUID()).status()).isEqualTo(404);
    }
}
