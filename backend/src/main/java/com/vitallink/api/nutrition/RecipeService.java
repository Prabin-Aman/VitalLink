package com.vitallink.api.nutrition;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.Num;
import com.vitallink.api.nutrition.NutritionDtos.FoodOut;
import com.vitallink.api.nutrition.NutritionDtos.ItemIn;
import com.vitallink.api.nutrition.NutritionDtos.Meal;
import com.vitallink.api.nutrition.NutritionDtos.MealIn;
import com.vitallink.api.nutrition.NutritionDtos.MealSlot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Nutrition › Recipes: dishes built from foods, cooked in a number of servings. Macros are computed from
 * the foods on read; logging servings writes an ordinary meal, so editing a recipe never rewrites the past.
 */
@Service
public class RecipeService {

    public record RecipeItemIn(
            @NotNull UUID foodId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("5000")
            Double quantityG) {}

    public record RecipeIn(
            @NotBlank @Size(max = 60) String name,
            @NotNull @Min(1) @Max(20) Integer servings,
            @Size(max = 500) String notes,
            @NotEmpty @Size(max = 30) List<@Valid RecipeItemIn> items) {

        public RecipeIn {
            name = NutritionDtos.strip(name);
            notes = notes == null || notes.isBlank() ? null : notes.strip();
        }
    }

    /** Every field optional; `items` replaces the whole list. */
    public record RecipePatch(
            @Size(min = 1, max = 60) String name,
            @Min(1) @Max(20) Integer servings,
            @Size(max = 500) String notes,
            @Size(min = 1, max = 30) List<@Valid RecipeItemIn> items) {}

    /** An ingredient: the food (per 100 g, so a form can estimate), its grams and their kcal. */
    public record RecipeItemOut(FoodOut food, double quantityG, int kcal) {}

    /** Per serving: the whole recipe divided by its servings. */
    public record Serving(double grams, int kcal, int proteinG, int carbsG, int fatG, int fibreG) {}

    public record RecipeOut(
            UUID id,
            String name,
            int servings,
            String notes,
            List<RecipeItemOut> items,
            Serving perServing,
            OffsetDateTime updatedAt) {}

    /** Log servings of a recipe as a meal (default now; up to 20 servings, in quarters). */
    public record RecipeLogIn(
            @NotNull MealSlot slot,
            OffsetDateTime eatenAt,
            @NotNull @DecimalMin("0.25") @DecimalMax("20") Double servings) {}

    private final JdbcClient jdbc;
    private final NutritionService nutrition;

    public RecipeService(JdbcClient jdbc, NutritionService nutrition) {
        this.jdbc = jdbc;
        this.nutrition = nutrition;
    }

    public List<RecipeOut> list(CurrentUser me) {
        return recipes(me, null);
    }

    public RecipeOut get(CurrentUser me, UUID id) {
        return recipes(me, id).stream().findFirst().orElseThrow(() -> ApiError.notFound("No such recipe."));
    }

    public RecipeOut create(CurrentUser me, RecipeIn body) {
        checkFoods(me, body.items());
        UUID id = Ids.uuid7();
        jdbc.sql("""
                        INSERT INTO vitallink.recipes (id, user_id, name, servings, notes)
                        VALUES (:id, :u, :name, :servings, :notes)
                        """)
                .param("id", id)
                .param("u", me.id())
                .param("name", body.name())
                .param("servings", body.servings())
                .param("notes", body.notes())
                .update();
        writeItems(id, body.items());
        return get(me, id);
    }

    public RecipeOut update(CurrentUser me, UUID id, RecipePatch body) {
        get(me, id); // 404 for someone else's
        if (body.name() != null && body.name().isBlank()) {
            throw ApiError.invalid("A recipe needs a name.");
        }
        if (body.items() != null) {
            checkFoods(me, body.items());
            jdbc.sql("DELETE FROM vitallink.recipe_items WHERE recipe_id = :id")
                    .param("id", id)
                    .update();
            writeItems(id, body.items());
        }
        jdbc.sql("""
                        UPDATE vitallink.recipes SET
                          name = COALESCE(:name, name),
                          servings = COALESCE(:servings, servings),
                          notes = CASE WHEN :setNotes THEN :notes ELSE notes END,
                          updated_at = now()
                        WHERE id = :id AND user_id = :u
                        """)
                .param("name", body.name() == null ? null : body.name().strip())
                .param("servings", body.servings())
                .param("setNotes", body.notes() != null)
                .param(
                        "notes",
                        body.notes() == null || body.notes().isBlank()
                                ? null
                                : body.notes().strip())
                .param("id", id)
                .param("u", me.id())
                .update();
        return get(me, id);
    }

    public void delete(CurrentUser me, UUID id) {
        int n = jdbc.sql("DELETE FROM vitallink.recipes WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such recipe.");
        }
    }

    /** Each food's grams scaled to the servings eaten; the meal is named after the recipe. */
    public Meal log(CurrentUser me, UUID id, RecipeLogIn body) {
        RecipeOut r = get(me, id);
        double share = body.servings() / r.servings();
        List<ItemIn> items = r.items().stream()
                .map(i -> new ItemIn(
                        i.food().id(),
                        Math.max(0.1, Math.round(i.quantityG() * share * 10) / 10.0),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                .toList();
        OffsetDateTime at = body.eatenAt() != null ? body.eatenAt() : com.vitallink.api.core.Clock.now();
        return nutrition.logMeal(me, new MealIn(body.slot(), r.name(), at, items));
    }

    // ----------------------------------------------------------------

    private void checkFoods(CurrentUser me, List<RecipeItemIn> items) {
        List<UUID> ids = items.stream().map(RecipeItemIn::foodId).distinct().toList();
        long known = jdbc.sql("""
                        SELECT count(*) FROM vitallink.foods
                        WHERE id IN (:ids) AND (source = 'seeded' OR owner_user_id = :u)
                        """)
                .param("ids", ids)
                .param("u", me.id())
                .query(Long.class)
                .single();
        if (known != ids.size()) {
            throw new ApiError(404, "unknown_food", "One of the foods doesn't exist.");
        }
    }

    private void writeItems(UUID recipe, List<RecipeItemIn> items) {
        for (int position = 0; position < items.size(); position++) {
            RecipeItemIn i = items.get(position);
            jdbc.sql("""
                            INSERT INTO vitallink.recipe_items (id, recipe_id, food_id, position, quantity_g)
                            VALUES (:id, :recipe, :food, :position, :g)
                            """)
                    .param("id", Ids.uuid7())
                    .param("recipe", recipe)
                    .param("food", i.foodId())
                    .param("position", position)
                    .param("g", i.quantityG())
                    .update();
        }
    }

    private record Row(UUID recipe, RecipeItemOut item, double[] macros) {}

    private List<RecipeOut> recipes(CurrentUser me, UUID only) {
        String filter = only == null ? "" : " AND r.id = :id";
        Map<String, Object> params = new HashMap<>();
        params.put("u", me.id());
        if (only != null) {
            params.put("id", only);
        }
        Map<UUID, List<Row>> items = new HashMap<>();
        jdbc.sql("""
                        SELECT i.recipe_id, i.quantity_g, f.id, f.name, f.brand, f.serving_g,
                               f.kcal, f.protein_g, f.carbs_g, f.fat_g, f.fibre_g
                        FROM vitallink.recipe_items i
                        JOIN vitallink.recipes r ON r.id = i.recipe_id
                        JOIN vitallink.foods f ON f.id = i.food_id
                        WHERE r.user_id = :u""" + filter + " ORDER BY i.recipe_id, i.position")
                .params(params)
                .query((rs, n) -> {
                    double g = rs.getDouble("quantity_g");
                    double s = g / 100;
                    double[] m = {
                        rs.getDouble("kcal") * s,
                        rs.getDouble("protein_g") * s,
                        rs.getDouble("carbs_g") * s,
                        rs.getDouble("fat_g") * s,
                        rs.getDouble("fibre_g") * s
                    };
                    UUID recipe = rs.getObject("recipe_id", UUID.class);
                    RecipeItemOut item = new RecipeItemOut(
                            new FoodOut(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("name"),
                                    rs.getString("brand"),
                                    rs.getDouble("serving_g"),
                                    rs.getDouble("kcal"),
                                    rs.getDouble("protein_g"),
                                    rs.getDouble("carbs_g"),
                                    rs.getDouble("fat_g"),
                                    rs.getDouble("fibre_g")),
                            g,
                            Num.roundInt(m[0]));
                    return items.computeIfAbsent(recipe, k -> new ArrayList<>()).add(new Row(recipe, item, m));
                })
                .list();
        return jdbc.sql("SELECT * FROM vitallink.recipes r WHERE r.user_id = :u" + filter
                        + " ORDER BY lower(r.name), r.id")
                .params(params)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    int servings = rs.getInt("servings");
                    List<Row> rows = items.getOrDefault(id, List.of());
                    double[] t = new double[5];
                    double grams = 0;
                    for (Row r : rows) {
                        grams += r.item().quantityG();
                        for (int k = 0; k < 5; k++) {
                            t[k] += r.macros()[k];
                        }
                    }
                    return new RecipeOut(
                            id,
                            rs.getString("name"),
                            servings,
                            rs.getString("notes"),
                            rows.stream().map(Row::item).toList(),
                            new Serving(
                                    Math.round(grams / servings * 10) / 10.0,
                                    Num.roundInt(t[0] / servings),
                                    Num.roundInt(t[1] / servings),
                                    Num.roundInt(t[2] / servings),
                                    Num.roundInt(t[3] / servings),
                                    Num.roundInt(t[4] / servings)),
                            rs.getObject("updated_at", OffsetDateTime.class));
                })
                .list();
    }
}
