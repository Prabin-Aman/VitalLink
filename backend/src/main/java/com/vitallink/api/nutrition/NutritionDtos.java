package com.vitallink.api.nutrition;

import com.vitallink.api.core.ApiError;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Nutrition bodies. Responses match packages/api/src/types.ts (NutritionDay, Meal). */
public final class NutritionDtos {

    private NutritionDtos() {}

    public enum MealSlot {
        breakfast,
        lunch,
        dinner,
        snack
    }

    static String strip(String s) {
        return s == null ? null : s.strip();
    }

    public record FoodOut(
            UUID id,
            String name,
            String brand,
            double servingG,
            double kcal,
            double proteinG,
            double carbsG,
            double fatG,
            double fibreG,
            /** seeded (VitalLink's list), user (your own) or usda (USDA FoodData Central). */
            String source) {}

    /** A user's own food, per 100 g. */
    public record FoodIn(
            @NotBlank @Size(max = 80) String name,
            @Size(min = 1, max = 80) String brand,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("5000")
            Double servingG,

            @NotNull @DecimalMin("0") @DecimalMax("900") Double kcal,
            @NotNull @DecimalMin("0") @DecimalMax("100") Double proteinG,
            @NotNull @DecimalMin("0") @DecimalMax("100") Double carbsG,
            @NotNull @DecimalMin("0") @DecimalMax("100") Double fatG,
            @DecimalMin("0") @DecimalMax("100") Double fibreG) {

        public FoodIn {
            name = strip(name);
            brand = strip(brand);
            servingG = servingG == null ? 100.0 : servingG;
            fibreG = fibreG == null ? 0.0 : fibreG;
        }
    }

    /** Either a food from the catalogue (macros computed) or a one-off item with its own macros. */
    public record ItemIn(
            UUID foodId,

            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("5000")
            Double quantityG,

            @Size(min = 1, max = 80) String label,
            @DecimalMin("0") @DecimalMax("20000") Double kcal,
            @DecimalMin("0") @DecimalMax("5000") Double proteinG,
            @DecimalMin("0") @DecimalMax("5000") Double carbsG,
            @DecimalMin("0") @DecimalMax("5000") Double fatG,
            @DecimalMin("0") @DecimalMax("5000") Double fibreG) {

        public ItemIn {
            label = strip(label);
            if (foodId == null && Stream.of(label, kcal, proteinG, carbsG, fatG).anyMatch(v -> v == null)) {
                throw ApiError.invalid("Give a food_id, or a label with kcal, protein_g, carbs_g and fat_g.");
            }
            if (foodId != null && Stream.of(kcal, proteinG, carbsG, fatG).anyMatch(v -> v != null)) {
                throw ApiError.invalid("Macros come from the food; don't send them with a food_id.");
            }
        }
    }

    public record MealIn(
            @NotNull MealSlot slot,
            @Size(min = 1, max = 60) String name,
            @NotNull OffsetDateTime eatenAt,
            @NotEmpty @Size(max = 50) List<@Valid ItemIn> items) {

        public MealIn {
            name = strip(name);
        }
    }

    public record Meal(
            UUID id,
            String name,
            OffsetDateTime eatenAt,
            List<String> items,
            int kcal,
            int proteinG,
            int carbsG,
            int fatG) {}

    public record WaterIn(@NotNull @Min(1) @Max(3000) Integer ml, OffsetDateTime at) {}

    public record WaterOut(UUID id, int ml, OffsetDateTime at) {}

    public record MacroGoals(int proteinG, int carbsG, int fatG, int fibreG) {}

    public record NutritionTargetsOut(
            int proteinG,
            int carbsG,
            int fatG,
            int fibreG,
            int kcal,
            int waterMl,
            int glassMl,
            double weeklyTargetKg) {}

    public record NutritionTargetsIn(
            @Min(800) @Max(6000) Integer kcal,
            @Min(0) @Max(500) Integer proteinG,
            @Min(0) @Max(1000) Integer carbsG,
            @Min(0) @Max(400) Integer fatG,
            @Min(0) @Max(150) Integer fibreG,
            @Min(500) @Max(8000) Integer waterMl,
            @Min(100) @Max(1000) Integer glassMl,
            @DecimalMin("-1") @DecimalMax("1") Double weeklyTargetKg) {}

    /**
     * @param baseBurnKcal null until height, weight, birth date and sex are known (Mifflin-St Jeor needs all four)
     */
    public record NutritionDay(
            OffsetDateTime date,
            int kcal,
            int kcalGoal,
            int proteinG,
            int carbsG,
            int fatG,
            int fibreG,
            MacroGoals goals,
            int waterMl,
            int waterGoalMl,
            int glassMl,
            Integer baseBurnKcal,
            int activeBurnKcal,
            double weeklyTargetKg,
            List<Meal> meals) {}
}
