package com.vitallink.api.nutrition;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.nutrition.NutritionDtos.Meal;
import com.vitallink.api.nutrition.RecipeService.RecipeIn;
import com.vitallink.api.nutrition.RecipeService.RecipeLogIn;
import com.vitallink.api.nutrition.RecipeService.RecipeOut;
import com.vitallink.api.nutrition.RecipeService.RecipePatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Nutrition › Recipes (Figma page 15, "Nutrition · Recipes"). */
@RestController
@RequestMapping("/v1/me/recipes")
@Tag(name = "nutrition")
public class RecipeController {

    private final RecipeService service;

    public RecipeController(RecipeService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "The user's recipes by name, with per-serving macros computed from their foods.")
    public List<RecipeOut> list(CurrentUser me) {
        return service.list(me);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecipeOut create(CurrentUser me, @Valid @RequestBody RecipeIn body) {
        return service.create(me, body);
    }

    @GetMapping("/{id}")
    public RecipeOut get(CurrentUser me, @PathVariable UUID id) {
        return service.get(me, id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename, change servings or notes; `items` replaces the whole ingredient list.")
    public RecipeOut update(CurrentUser me, @PathVariable UUID id, @Valid @RequestBody RecipePatch body) {
        return service.update(me, id, body);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Meals already logged from the recipe stay.")
    public ResponseEntity<Void> delete(CurrentUser me, @PathVariable UUID id) {
        service.delete(me, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/log")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Log servings as a meal named after the recipe; macros are copied as for any meal.")
    public Meal log(CurrentUser me, @PathVariable UUID id, @Valid @RequestBody RecipeLogIn body) {
        return service.log(me, id, body);
    }
}
