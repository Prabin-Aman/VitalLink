package com.vitallink.api.nutrition;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.nutrition.NutritionDtos.FoodIn;
import com.vitallink.api.nutrition.NutritionDtos.FoodOut;
import com.vitallink.api.nutrition.NutritionDtos.Meal;
import com.vitallink.api.nutrition.NutritionDtos.MealIn;
import com.vitallink.api.nutrition.NutritionDtos.NutritionDay;
import com.vitallink.api.nutrition.NutritionDtos.NutritionTargetsIn;
import com.vitallink.api.nutrition.NutritionDtos.NutritionTargetsOut;
import com.vitallink.api.nutrition.NutritionDtos.WaterIn;
import com.vitallink.api.nutrition.NutritionDtos.WaterOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@Tag(name = "nutrition")
public class NutritionController {

    private final NutritionService service;

    public NutritionController(NutritionService service) {
        this.service = service;
    }

    @GetMapping("/foods/search")
    public List<FoodOut> searchFoods(CurrentUser me, @RequestParam @Size(min = 2, max = 60) String q) {
        return service.searchFoods(me, q);
    }

    @PostMapping("/me/foods")
    @ResponseStatus(HttpStatus.CREATED)
    public FoodOut createFood(CurrentUser me, @Valid @RequestBody FoodIn body) {
        return service.createFood(me, body);
    }

    @PostMapping("/me/meals")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Macros are copied from the food at logging time.")
    public Meal logMeal(CurrentUser me, @Valid @RequestBody MealIn body) {
        return service.logMeal(me, body);
    }

    @DeleteMapping("/me/meals/{id}")
    public ResponseEntity<Void> deleteMeal(CurrentUser me, @PathVariable UUID id) {
        service.deleteMeal(me, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/hydration")
    @ResponseStatus(HttpStatus.CREATED)
    public WaterOut logWater(CurrentUser me, @Valid @RequestBody WaterIn body) {
        return service.logWater(me, body);
    }

    @DeleteMapping("/me/hydration/{id}")
    public ResponseEntity<Void> deleteWater(CurrentUser me, @PathVariable UUID id) {
        service.deleteWater(me, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/nutrition/day")
    @Operation(summary = "Totals, targets, water, energy balance and meals for one local day (default today).")
    public NutritionDay day(CurrentUser me, @RequestParam(required = false) LocalDate date) {
        return service.day(me.id(), me.user().timezone(), date);
    }

    @GetMapping("/me/nutrition/targets")
    public NutritionTargetsOut getTargets(CurrentUser me) {
        return service.getTargets(me);
    }

    @PatchMapping("/me/nutrition/targets")
    public NutritionTargetsOut updateTargets(CurrentUser me, @Valid @RequestBody NutritionTargetsIn body) {
        return service.updateTargets(me, body);
    }
}
