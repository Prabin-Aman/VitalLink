package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.nutrition.Foods;
import com.vitallink.api.support.ApiTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Nutrition › Recipes: built from foods, per-serving macros, logged as an ordinary meal. */
class RecipeTest extends ApiTest {

    static final String OATS = Foods.foodId("Rolled oats").toString(); // 379 kcal / 100 g
    static final String YOGHURT = Foods.foodId("Greek yoghurt").toString(); // 73 kcal / 100 g

    static Map<String, Object> overnightOats() {
        return Map.of(
                "name",
                "  Overnight oats ",
                "servings",
                2,
                "items",
                List.of(Map.of("food_id", OATS, "quantity_g", 100), Map.of("food_id", YOGHURT, "quantity_g", 200)));
    }

    @Test
    void aRecipeComputesItsServing() {
        String u = user();
        Res r = post(u, "/v1/me/recipes", overnightOats());
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        assertThat(r.at("/name").asString()).isEqualTo("Overnight oats");
        assertThat(r.at("/items/0/food/name").asString()).isEqualTo("Rolled oats");
        assertThat(r.at("/items/1/kcal").asInt()).isEqualTo(146);
        // (379 + 146) / 2 servings = 262.5, rounded half-even; 300 g / 2
        assertThat(r.at("/per_serving/kcal").asInt()).isEqualTo(262);
        assertThat(r.at("/per_serving/grams").asDouble()).isEqualTo(150);
        assertThat(get(u, "/v1/me/recipes").body().size()).isEqualTo(1);
        assertThat(get(user(), "/v1/me/recipes").body().size()).isZero();
        assertThat(get(user(), "/v1/me/recipes/" + r.at("/id").asString()).status())
                .isEqualTo(404);
    }

    @Test
    void editingReplacesTheIngredientsButNotPastMeals() {
        String u = user();
        String id = post(u, "/v1/me/recipes", overnightOats()).at("/id").asString();
        Res meal = post(u, "/v1/me/recipes/" + id + "/log", Map.of("slot", "breakfast", "servings", 1));
        assertThat(meal.status()).as(meal.text()).isEqualTo(201);
        assertThat(meal.at("/name").asString()).isEqualTo("Overnight oats");
        assertThat(meal.at("/kcal").asInt()).isEqualTo(262);

        Res edited = patch(
                u,
                "/v1/me/recipes/" + id,
                Map.of(
                        "servings",
                        1,
                        "notes",
                        "Soak overnight",
                        "items",
                        List.of(Map.of("food_id", OATS, "quantity_g", 50))));
        assertThat(edited.at("/per_serving/kcal").asInt()).isEqualTo(190);
        assertThat(edited.at("/notes").asString()).isEqualTo("Soak overnight");
        assertThat(edited.at("/items").size()).isEqualTo(1);
        // The meal logged before the edit keeps its macros.
        assertThat(get(u, "/v1/me/nutrition/day").text()).contains("\"kcal\":262");

        assertThat(delete(u, "/v1/me/recipes/" + id).status()).isEqualTo(204);
        assertThat(get(u, "/v1/me/recipes").body().size()).isZero();
        assertThat(get(u, "/v1/me/nutrition/day").text()).contains("Overnight oats");
    }

    @Test
    void foodsMustBeTheUsersOrSeeded() {
        String u = user();
        String theirs = post(
                        user(),
                        "/v1/me/foods",
                        Map.of("name", "Secret sauce", "kcal", 100, "protein_g", 1, "carbs_g", 1, "fat_g", 1))
                .at("/id")
                .asString();
        Res r = post(
                u,
                "/v1/me/recipes",
                Map.of(
                        "name",
                        "Borrowed",
                        "servings",
                        1,
                        "items",
                        List.of(Map.of("food_id", theirs, "quantity_g", 10))));
        assertThat(r.status()).isEqualTo(404);
        assertThat(post(u, "/v1/me/recipes", Map.of("name", "Empty", "servings", 1, "items", List.of()))
                        .status())
                .isEqualTo(422);
        assertThat(post(
                                u,
                                "/v1/me/recipes",
                                Map.of(
                                        "name",
                                        "Feast",
                                        "servings",
                                        50,
                                        "items",
                                        List.of(Map.of("food_id", OATS, "quantity_g", 10))))
                        .status())
                .isEqualTo(422);
    }
}
