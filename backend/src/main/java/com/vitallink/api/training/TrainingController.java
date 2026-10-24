package com.vitallink.api.training;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.training.TrainingDtos.ExerciseOut;
import com.vitallink.api.training.TrainingDtos.PlanIn;
import com.vitallink.api.training.TrainingDtos.PlanOut;
import com.vitallink.api.training.TrainingDtos.Training;
import com.vitallink.api.training.TrainingDtos.WorkoutDetail;
import com.vitallink.api.training.TrainingDtos.WorkoutIn;
import com.vitallink.api.training.TrainingDtos.WorkoutOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@Tag(name = "training")
public class TrainingController {

    private final TrainingService service;

    public TrainingController(TrainingService service) {
        this.service = service;
    }

    @PostMapping("/me/workouts")
    @Operation(summary = "Log a workout with its sets. A synced workout with a known platform id is updated (200).")
    public ResponseEntity<WorkoutDetail> saveWorkout(CurrentUser me, @Valid @RequestBody WorkoutIn body) {
        var saved = service.saveWorkout(me, body);
        return ResponseEntity.status(saved.getValue() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(saved.getKey());
    }

    @GetMapping("/me/workouts")
    public List<WorkoutOut> listWorkouts(CurrentUser me, @RequestParam(defaultValue = "7") @Min(1) @Max(400) int days) {
        return service.listWorkouts(me.id(), me.user().timezone(), days);
    }

    @GetMapping("/me/workouts/{id}")
    public WorkoutDetail getWorkout(CurrentUser me, @PathVariable UUID id) {
        return service.getWorkout(me, id);
    }

    @DeleteMapping("/me/workouts/{id}")
    public ResponseEntity<Void> deleteWorkout(CurrentUser me, @PathVariable UUID id) {
        service.deleteWorkout(me, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/training")
    @Operation(
            summary =
                    "This week's load, recent sessions, muscle balance, personal records and the next planned session.")
    public Training training(CurrentUser me) {
        return service.training(me.id(), me.user().timezone());
    }

    @PostMapping("/me/training/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanOut createPlan(CurrentUser me, @Valid @RequestBody PlanIn body) {
        return service.createPlan(me, body);
    }

    @GetMapping("/me/training/plans")
    public List<PlanOut> plans(CurrentUser me) {
        return service.plans(me);
    }

    @GetMapping("/exercises")
    public List<ExerciseOut> exercises(CurrentUser me) {
        return service.exercises();
    }
}
