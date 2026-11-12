package com.vitallink.api.nutrition;

import com.vitallink.api.account.AccountDtos.Sex;
import com.vitallink.api.account.AccountService;
import com.vitallink.api.account.AccountService.Profile;
import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.Num;
import com.vitallink.api.health.HealthService;
import com.vitallink.api.nutrition.NutritionDtos.FoodIn;
import com.vitallink.api.nutrition.NutritionDtos.FoodOut;
import com.vitallink.api.nutrition.NutritionDtos.ItemIn;
import com.vitallink.api.nutrition.NutritionDtos.MacroGoals;
import com.vitallink.api.nutrition.NutritionDtos.Meal;
import com.vitallink.api.nutrition.NutritionDtos.MealIn;
import com.vitallink.api.nutrition.NutritionDtos.NutritionDay;
import com.vitallink.api.nutrition.NutritionDtos.NutritionTargetsIn;
import com.vitallink.api.nutrition.NutritionDtos.NutritionTargetsOut;
import com.vitallink.api.nutrition.NutritionDtos.WaterIn;
import com.vitallink.api.nutrition.NutritionDtos.WaterOut;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Nutrition rules: food search, meals with copied macros, water, targets, the day view. */
@Service
public class NutritionService {

    private static final List<String> MACROS = List.of("kcal", "protein_g", "carbs_g", "fat_g", "fibre_g");

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final HealthService health;

    private final OpenFoodFacts openFoodFacts;
    private final FoodDataCentral usda;

    public NutritionService(
            JdbcClient jdbc,
            AccountService accounts,
            HealthService health,
            OpenFoodFacts openFoodFacts,
            FoodDataCentral usda) {
        this.openFoodFacts = openFoodFacts;
        this.usda = usda;
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.health = health;
    }

    private static FoodOut foodOut(ResultSet rs, int i) throws SQLException {
        return new FoodOut(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("brand"),
                rs.getDouble("serving_g"),
                rs.getDouble("kcal"),
                rs.getDouble("protein_g"),
                rs.getDouble("carbs_g"),
                rs.getDouble("fat_g"),
                rs.getDouble("fibre_g"),
                rs.getString("source"));
    }

    /** Below this many matches of its own, the search also asks USDA FoodData Central. */
    static final int ASK_USDA_BELOW = 10;

    static final int SEARCH_LIMIT = 25;

    /**
     * The caller's own foods, VitalLink's list and USDA foods found before, by name; with fewer than 10 of those,
     * also USDA FoodData Central. A USDA food is stored once (by its FDC id) as a shared catalogue row, so meals and
     * recipes can point at it and the next search finds it without asking USDA.
     */
    public List<FoodOut> searchFoods(CurrentUser me, String q) {
        String pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<FoodOut> local = jdbc.sql("""
                        SELECT * FROM vitallink.foods
                        WHERE name ILIKE :p ESCAPE '\\' AND (source IN ('seeded', 'usda') OR owner_user_id = :u)
                        ORDER BY source = 'usda', length(name), name LIMIT 25
                        """)
                .param("p", pattern)
                .param("u", me.id())
                .query(NutritionService::foodOut)
                .list();
        if (local.size() >= ASK_USDA_BELOW || !usda.configured()) {
            return local;
        }
        List<FoodOut> out = new ArrayList<>(local);
        Set<UUID> seen = new java.util.HashSet<>();
        local.forEach(f -> seen.add(f.id()));
        for (FoodDataCentral.Food f : usda.search(q)) {
            if (out.size() >= SEARCH_LIMIT) {
                break;
            }
            FoodOut saved = saveUsda(f);
            if (seen.add(saved.id())) {
                out.add(saved);
            }
        }
        return out;
    }

    /** A USDA food as a shared catalogue row; the latest USDA values win. */
    FoodOut saveUsda(FoodDataCentral.Food f) {
        return jdbc.sql("""
                        INSERT INTO vitallink.foods
                          (id, name, source, serving_g, kcal, protein_g, carbs_g, fat_g, fibre_g, fdc_id)
                        VALUES (:id, :name, 'usda', :serving, :kcal, :protein, :carbs, :fat, :fibre, :fdc)
                        ON CONFLICT (fdc_id) WHERE fdc_id IS NOT NULL DO UPDATE
                          SET name = EXCLUDED.name, serving_g = EXCLUDED.serving_g, kcal = EXCLUDED.kcal,
                              protein_g = EXCLUDED.protein_g, carbs_g = EXCLUDED.carbs_g, fat_g = EXCLUDED.fat_g,
                              fibre_g = EXCLUDED.fibre_g
                        RETURNING *
                        """)
                .param("id", com.vitallink.api.core.Ids.uuid7())
                .param("name", f.name())
                .param("serving", f.servingG())
                .param("kcal", f.kcal())
                .param("protein", f.proteinG())
                .param("carbs", f.carbsG())
                .param("fat", f.fatG())
                .param("fibre", f.fibreG())
                .param("fdc", f.fdcId())
                .query(NutritionService::foodOut)
                .single();
    }

    /**
     * Nutrition › Scan Barcode: a food the user (or the seeded list) already has with this barcode, else the
     * product from Open Food Facts, saved as the user's own food with its barcode.
     */
    public FoodOut foodByBarcode(CurrentUser me, String code) {
        if (code == null || !code.matches("\\d{8,14}")) {
            throw ApiError.invalid("A barcode is 8 to 14 digits.");
        }
        var known = jdbc.sql("""
                        SELECT * FROM vitallink.foods
                        WHERE barcode = :b AND (source = 'seeded' OR owner_user_id = :u)
                        ORDER BY source LIMIT 1
                        """)
                .param("b", code)
                .param("u", me.id())
                .query(NutritionService::foodOut)
                .optional();
        if (known.isPresent()) {
            return known.get();
        }
        OpenFoodFacts.Product p;
        try {
            p = openFoodFacts
                    .lookup(code)
                    .orElseThrow(() -> new ApiError(
                            404, "food_not_found", "This barcode isn't in Open Food Facts. Add the food yourself."));
        } catch (IllegalStateException e) {
            throw new ApiError(
                    503, "lookup_unavailable", "Couldn't reach Open Food Facts. Try again, or add the food yourself.");
        }
        FoodOut food = createFood(
                me,
                new FoodIn(
                        p.name(), p.brand(), p.servingG(), p.kcal(), p.proteinG(), p.carbsG(), p.fatG(), p.fibreG()));
        jdbc.sql("UPDATE vitallink.foods SET barcode = :b WHERE id = :id")
                .param("b", code)
                .param("id", food.id())
                .update();
        return food;
    }

    /** The water entries of one local day, newest first (Hydration › Today). */
    public List<WaterOut> waterOn(CurrentUser me, java.time.LocalDate day) {
        java.time.ZoneId zone = me.zone();
        return jdbc.sql("""
                        SELECT id, ml, logged_at FROM vitallink.hydration_logs
                        WHERE user_id = :u AND logged_at >= :start AND logged_at < :end
                        ORDER BY logged_at DESC, id DESC
                        """)
                .param("u", me.id())
                .param("start", day.atStartOfDay(zone).toOffsetDateTime())
                .param("end", day.plusDays(1).atStartOfDay(zone).toOffsetDateTime())
                .query((rs, i) -> new WaterOut(
                        rs.getObject("id", UUID.class),
                        rs.getInt("ml"),
                        rs.getObject("logged_at", java.time.OffsetDateTime.class)))
                .list();
    }

    public FoodOut createFood(CurrentUser me, FoodIn body) {
        UUID id = Ids.uuid7();
        jdbc.sql("""
                        INSERT INTO vitallink.foods (id, name, brand, source, owner_user_id, serving_g, kcal, protein_g,
                                                     carbs_g, fat_g, fibre_g)
                        VALUES (:id, :name, :brand, 'user', :u, :serving, :kcal, :protein, :carbs, :fat, :fibre)
                        """)
                .param("id", id)
                .param("name", body.name())
                .param("brand", body.brand())
                .param("u", me.id())
                .param("serving", body.servingG())
                .param("kcal", body.kcal())
                .param("protein", body.proteinG())
                .param("carbs", body.carbsG())
                .param("fat", body.fatG())
                .param("fibre", body.fibreG())
                .update();
        return jdbc.sql("SELECT * FROM vitallink.foods WHERE id = :id")
                .param("id", id)
                .query(NutritionService::foodOut)
                .single();
    }

    // ---------------------------------------------------------------- meals and water

    record Item(UUID mealId, String label, Map<String, BigDecimal> macros) {}

    record MealRow(UUID id, String name, OffsetDateTime eatenAt, List<Item> items) {}

    private static int total(List<Item> items, String macro) {
        return Num.roundInt(items.stream()
                .mapToDouble(i -> i.macros().get(macro).doubleValue())
                .sum());
    }

    static Meal mealOut(MealRow m, ZoneId tz) {
        return new Meal(
                m.id(),
                m.name(),
                m.eatenAt().atZoneSameInstant(tz).toOffsetDateTime(),
                m.items().stream().map(Item::label).toList(),
                total(m.items(), "kcal"),
                total(m.items(), "protein_g"),
                total(m.items(), "carbs_g"),
                total(m.items(), "fat_g"));
    }

    private List<MealRow> meals(String where, Map<String, Object> params) {
        Map<UUID, List<Item>> items = new HashMap<>();
        jdbc.sql(
                        "SELECT i.* FROM vitallink.meal_items i WHERE i.meal_id IN (SELECT id FROM vitallink.meal_entries WHERE "
                                + where + ") ORDER BY i.position")
                .params(params)
                .query((rs, n) -> {
                    Map<String, BigDecimal> macros = new LinkedHashMap<>();
                    for (String k : MACROS) {
                        macros.put(k, rs.getBigDecimal(k));
                    }
                    UUID meal = rs.getObject("meal_id", UUID.class);
                    return items.computeIfAbsent(meal, k -> new ArrayList<>())
                            .add(new Item(meal, rs.getString("label"), macros));
                })
                .list();
        return jdbc.sql("SELECT id, name, eaten_at FROM vitallink.meal_entries WHERE " + where
                        + " ORDER BY eaten_at, id")
                .params(params)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new MealRow(
                            id,
                            rs.getString("name"),
                            rs.getObject("eaten_at", OffsetDateTime.class),
                            items.getOrDefault(id, List.of()));
                })
                .list();
    }

    public Meal logMeal(CurrentUser me, MealIn body) {
        Set<UUID> foodIds = body.items().stream()
                .map(ItemIn::foodId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<UUID, Map<String, Object>> foods = new HashMap<>();
        if (!foodIds.isEmpty()) {
            jdbc.sql(
                            "SELECT * FROM vitallink.foods WHERE id IN (:ids) AND (source IN ('seeded', 'usda') OR owner_user_id = :u)")
                    .param("ids", List.copyOf(foodIds))
                    .param("u", me.id())
                    .query()
                    .listOfRows()
                    .forEach(r -> foods.put((UUID) r.get("id"), r));
        }
        List<String> missing = foodIds.stream()
                .filter(id -> !foods.containsKey(id))
                .map(UUID::toString)
                .toList();
        if (!missing.isEmpty()) {
            throw new ApiError(404, "unknown_food", "One of the foods doesn't exist.", missing);
        }

        UUID mealId = Ids.uuid7();
        jdbc.sql("""
                        INSERT INTO vitallink.meal_entries (id, user_id, slot, name, eaten_at)
                        VALUES (:id, :u, CAST(:slot AS vitallink.meal_slot), :name, :at)
                        """)
                .param("id", mealId)
                .param("u", me.id())
                .param("slot", body.slot().name())
                .param(
                        "name",
                        body.name() != null
                                ? body.name()
                                : capitalize(body.slot().name()))
                .param("at", body.eatenAt())
                .update();
        for (int position = 0; position < body.items().size(); position++) {
            ItemIn i = body.items().get(position);
            Map<String, BigDecimal> macros = new LinkedHashMap<>();
            String label;
            if (i.foodId() != null) {
                Map<String, Object> f = foods.get(i.foodId());
                BigDecimal share = new BigDecimal(Double.toString(i.quantityG())).divide(BigDecimal.valueOf(100));
                label = i.label() != null ? i.label() : (String) f.get("name");
                for (String k : MACROS) {
                    macros.put(k, ((BigDecimal) f.get(k)).multiply(share).setScale(1, RoundingMode.HALF_EVEN));
                }
            } else {
                label = i.label();
                macros.put("kcal", BigDecimal.valueOf(i.kcal()));
                macros.put("protein_g", BigDecimal.valueOf(i.proteinG()));
                macros.put("carbs_g", BigDecimal.valueOf(i.carbsG()));
                macros.put("fat_g", BigDecimal.valueOf(i.fatG()));
                macros.put("fibre_g", BigDecimal.valueOf(i.fibreG() == null ? 0 : i.fibreG()));
            }
            jdbc.sql("""
                            INSERT INTO vitallink.meal_items (id, meal_id, food_id, position, label, quantity_g, kcal,
                                                              protein_g, carbs_g, fat_g, fibre_g)
                            VALUES (:id, :meal, :food, :position, :label, :quantity, :kcal_g, :protein_g, :carbs_g,
                                    :fat_g, :fibre_g)
                            """)
                    .param("id", Ids.uuid7())
                    .param("meal", mealId)
                    .param("food", i.foodId())
                    .param("position", position)
                    .param("label", label)
                    .param("quantity", i.quantityG())
                    .param("kcal_g", macros.get("kcal"))
                    .param("protein_g", macros.get("protein_g"))
                    .param("carbs_g", macros.get("carbs_g"))
                    .param("fat_g", macros.get("fat_g"))
                    .param("fibre_g", macros.get("fibre_g"))
                    .update();
        }
        return mealOut(meals("id = :id", Map.of("id", mealId)).getFirst(), me.zone());
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public void deleteMeal(CurrentUser me, UUID mealId) {
        int n = jdbc.sql("DELETE FROM vitallink.meal_entries WHERE id = :id AND user_id = :u")
                .param("id", mealId)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such meal.");
        }
    }

    public WaterOut logWater(CurrentUser me, WaterIn body) {
        OffsetDateTime now = com.vitallink.api.core.Clock.now();
        OffsetDateTime at = body.at() != null ? body.at() : now;
        if (at.isAfter(now.plusMinutes(5))) {
            throw ApiError.invalid("That's in the future.");
        }
        UUID id = Ids.uuid7();
        jdbc.sql("INSERT INTO vitallink.hydration_logs (id, user_id, logged_at, ml) VALUES (:id, :u, :at, :ml)")
                .param("id", id)
                .param("u", me.id())
                .param("at", at)
                .param("ml", body.ml())
                .update();
        return new WaterOut(id, body.ml(), at.atZoneSameInstant(me.zone()).toOffsetDateTime());
    }

    public void deleteWater(CurrentUser me, UUID logId) {
        int n = jdbc.sql("DELETE FROM vitallink.hydration_logs WHERE id = :id AND user_id = :u")
                .param("id", logId)
                .param("u", me.id())
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such entry.");
        }
    }

    // ---------------------------------------------------------------- targets and the day view

    /** Insert-if-absent with the defaults, then read. */
    private NutritionTargetsOut targets(UUID userId) {
        jdbc.sql("INSERT INTO vitallink.nutrition_targets (user_id) VALUES (:u) ON CONFLICT DO NOTHING")
                .param("u", userId)
                .update();
        return jdbc.sql("SELECT * FROM vitallink.nutrition_targets WHERE user_id = :u")
                .param("u", userId)
                .query((rs, i) -> new NutritionTargetsOut(
                        rs.getInt("protein_g"),
                        rs.getInt("carbs_g"),
                        rs.getInt("fat_g"),
                        rs.getInt("fibre_g"),
                        rs.getInt("kcal"),
                        rs.getInt("water_ml"),
                        rs.getInt("glass_ml"),
                        rs.getBigDecimal("weekly_target_kg").doubleValue()))
                .single();
    }

    public NutritionTargetsOut getTargets(CurrentUser me) {
        return targets(me.id());
    }

    public NutritionTargetsOut updateTargets(CurrentUser me, NutritionTargetsIn body) {
        targets(me.id());
        Map<String, Object> changes = new LinkedHashMap<>();
        if (body.kcal() != null) changes.put("kcal", body.kcal());
        if (body.proteinG() != null) changes.put("protein_g", body.proteinG());
        if (body.carbsG() != null) changes.put("carbs_g", body.carbsG());
        if (body.fatG() != null) changes.put("fat_g", body.fatG());
        if (body.fibreG() != null) changes.put("fibre_g", body.fibreG());
        if (body.waterMl() != null) changes.put("water_ml", body.waterMl());
        if (body.glassMl() != null) changes.put("glass_ml", body.glassMl());
        if (body.weeklyTargetKg() != null) changes.put("weekly_target_kg", body.weeklyTargetKg());
        if (!changes.isEmpty()) {
            String sets = changes.keySet().stream().map(k -> k + " = :" + k).collect(Collectors.joining(", "));
            jdbc.sql("UPDATE vitallink.nutrition_targets SET " + sets + ", updated_at = now() WHERE user_id = :u")
                    .params(changes)
                    .param("u", me.id())
                    .update();
        }
        return targets(me.id());
    }

    /** Mifflin-St Jeor resting energy. Null unless height, weight, birth date and sex are all known. */
    public Integer baseBurn(UUID userId, LocalDate on) {
        Profile p = accounts.profile(userId);
        Double weight = health.latestValue(userId, "weight");
        if (weight == null || p.heightCm() == null || p.dateOfBirth() == null || p.sex() == null) {
            return null;
        }
        long age = ChronoUnit.DAYS.between(p.dateOfBirth(), on) / 365;
        int sexTerm = p.sex() == Sex.male ? 5 : p.sex() == Sex.female ? -161 : -78; // other/undisclosed: the midpoint
        return Num.roundInt(10 * weight + 6.25 * p.heightCm().doubleValue() - 5 * age + sexTerm);
    }

    public NutritionDay day(UUID userId, String tzName, LocalDate on) {
        ZoneId tz = ZoneId.of(tzName);
        LocalDate day = on != null ? on : LocalDate.now(tz);
        OffsetDateTime start = day.atStartOfDay(tz).toOffsetDateTime();
        OffsetDateTime end = day.plusDays(1).atStartOfDay(tz).toOffsetDateTime();

        List<MealRow> meals = meals(
                "user_id = :u AND eaten_at >= :start AND eaten_at < :end",
                Map.of("u", userId, "start", start, "end", end));
        int water = jdbc.sql("""
                        SELECT coalesce(sum(ml), 0) FROM vitallink.hydration_logs
                        WHERE user_id = :u AND logged_at >= :start AND logged_at < :end
                        """)
                .param("u", userId)
                .param("start", start)
                .param("end", end)
                .query(Integer.class)
                .single();
        NutritionTargetsOut t = targets(userId);
        List<Item> items = meals.stream().flatMap(m -> m.items().stream()).toList();
        return new NutritionDay(
                start,
                total(items, "kcal"),
                t.kcal(),
                total(items, "protein_g"),
                total(items, "carbs_g"),
                total(items, "fat_g"),
                total(items, "fibre_g"),
                new MacroGoals(t.proteinG(), t.carbsG(), t.fatG(), t.fibreG()),
                water,
                t.waterMl(),
                t.glassMl(),
                baseBurn(userId, day),
                Num.roundInt(health.totalBetween(userId, "active_energy", start, end)),
                t.weeklyTargetKg(),
                meals.stream().map(m -> mealOut(m, tz)).toList());
    }

    /** Daily protein (or another macro) totals for [from, to] in local days, for goals. */
    public Map<LocalDate, Double> dailyMacro(UUID userId, String tzName, String macro, LocalDate from, LocalDate to) {
        if (!MACROS.contains(macro)) {
            throw new IllegalArgumentException(macro);
        }
        ZoneId tz = ZoneId.of(tzName);
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT (e.eaten_at AT TIME ZONE :tz)::date AS day, sum(i.%s) AS total
                        FROM vitallink.meal_entries e JOIN vitallink.meal_items i ON i.meal_id = e.id
                        WHERE e.user_id = :u AND e.eaten_at >= :start AND e.eaten_at < :end
                        GROUP BY 1 ORDER BY 1
                        """.formatted(macro))
                .param("tz", tzName)
                .param("u", userId)
                .param("start", from.atStartOfDay(tz).toOffsetDateTime())
                .param("end", to.plusDays(1).atStartOfDay(tz).toOffsetDateTime())
                .query((rs, i) -> out.put(rs.getObject("day", LocalDate.class), rs.getDouble("total")))
                .list();
        return out;
    }

    /** Water drunk per local day in [from, to], ml. */
    public Map<LocalDate, Double> dailyWater(UUID userId, String tzName, LocalDate from, LocalDate to) {
        ZoneId tz = ZoneId.of(tzName);
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT (logged_at AT TIME ZONE :tz)::date AS day, sum(ml) AS total
                        FROM vitallink.hydration_logs
                        WHERE user_id = :u AND logged_at >= :start AND logged_at < :end
                        GROUP BY 1 ORDER BY 1
                        """)
                .param("tz", tzName)
                .param("u", userId)
                .param("start", from.atStartOfDay(tz).toOffsetDateTime())
                .param("end", to.plusDays(1).atStartOfDay(tz).toOffsetDateTime())
                .query((rs, i) -> out.put(rs.getObject("day", LocalDate.class), rs.getDouble("total")))
                .list();
        return out;
    }
}
