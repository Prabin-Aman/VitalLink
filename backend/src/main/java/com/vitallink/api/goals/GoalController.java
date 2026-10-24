package com.vitallink.api.goals;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.goals.GoalDtos.GoalIn;
import com.vitallink.api.goals.GoalDtos.GoalOut;
import com.vitallink.api.goals.GoalDtos.GoalProgress;
import com.vitallink.api.goals.GoalDtos.GoalsOverview;
import com.vitallink.api.goals.GoalDtos.StreakOut;
import com.vitallink.api.goals.GoalMetrics.GoalMetric;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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

@RestController
@RequestMapping("/v1/me")
@Tag(name = "goals")
public class GoalController {

    private final GoalService service;

    public GoalController(GoalService service) {
        this.service = service;
    }

    @GetMapping("/goals/overview")
    @Operation(
            summary =
                    "Goals with status and progress, achievements, the longest step streak and 5 weeks of consistency.")
    public GoalsOverview overview(CurrentUser me) {
        return service.overview(me.id(), me.user().timezone());
    }

    @GetMapping("/goals/today")
    @Operation(summary = "Today's progress on the daily targets and on active goals (dashboard, Activity).")
    public List<GoalProgress> today(CurrentUser me) {
        return service.today(me.id(), me.user().timezone());
    }

    @GetMapping("/goals/metrics")
    @Operation(summary = "What a goal can track, with its kind, unit and default area.")
    public List<GoalMetric> metrics(CurrentUser me) {
        return GoalMetrics.ALL;
    }

    @GetMapping("/goals")
    public List<GoalOut> list(CurrentUser me) {
        return service.list(me);
    }

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public GoalOut create(CurrentUser me, @Valid @RequestBody GoalIn body) {
        return service.create(me, body);
    }

    @GetMapping("/goals/{id}")
    public GoalOut get(CurrentUser me, @PathVariable UUID id) {
        return service.get(me, id);
    }

    @PatchMapping("/goals/{id}")
    @Operation(summary = "Edit, or change the lifecycle: active, paused, achieved, abandoned.")
    public GoalOut update(CurrentUser me, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return service.update(me, id, body);
    }

    @DeleteMapping("/goals/{id}")
    public ResponseEntity<Void> delete(CurrentUser me, @PathVariable UUID id) {
        service.delete(me, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/streaks")
    @Operation(summary = "Move-ring streak, the longest this year, and the stand streak.")
    public List<StreakOut> streaks(CurrentUser me) {
        return service.streaks(me.id(), me.user().timezone());
    }
}
