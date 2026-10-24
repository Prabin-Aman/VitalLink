package com.vitallink.api.health;

import com.vitallink.api.account.AccountService;
import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.health.HealthDtos.BloodPressureSeries;
import com.vitallink.api.health.HealthDtos.Dashboard;
import com.vitallink.api.health.HealthDtos.DeviceOut;
import com.vitallink.api.health.HealthDtos.IngestIn;
import com.vitallink.api.health.HealthDtos.IngestOut;
import com.vitallink.api.health.HealthDtos.Interval;
import com.vitallink.api.health.HealthDtos.ManualIn;
import com.vitallink.api.health.HealthDtos.ReadingOut;
import com.vitallink.api.health.HealthDtos.Series;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
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
@RequestMapping("/v1/me")
@Tag(name = "health")
public class HealthController {

    private final HealthService service;
    private final AccountService accounts;

    public HealthController(HealthService service, AccountService accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    /** Comma-separated metric codes, at most 20. */
    static List<String> codes(String metrics) {
        return Arrays.stream(metrics.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .limit(20)
                .toList();
    }

    @PostMapping("/health/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Batch of 5-minute aggregates from HealthKit or Health Connect (≤ 5,000), upserted.")
    public IngestOut ingest(CurrentUser me, @Valid @RequestBody IngestIn body) {
        return service.ingest(me, body);
    }

    @PostMapping("/health/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingOut addManual(CurrentUser me, @Valid @RequestBody ManualIn body) {
        return service.addManual(me, body);
    }

    @GetMapping("/health/dashboard")
    public Dashboard dashboard(CurrentUser me) {
        int goal = accounts.targets(me.id()).sleepGoalMin();
        return service.dashboard(me.id(), me.user().timezone(), goal, true);
    }

    @GetMapping("/health/series/blood_pressure")
    public BloodPressureSeries bloodPressure(
            CurrentUser me,
            @RequestParam(defaultValue = "7") @Min(1) @Max(400) int days,
            @RequestParam(defaultValue = "day") Interval interval) {
        return service.bloodPressureSeries(me.id(), me.user().timezone(), days, interval);
    }

    @GetMapping("/health/series/{metric}")
    @Operation(summary = "Buckets in the user's time zone. Totals fill empty buckets with 0; averages skip them.")
    public Series series(
            CurrentUser me,
            @PathVariable String metric,
            @RequestParam(defaultValue = "7") @Min(1) @Max(400) int days,
            @RequestParam(defaultValue = "day") Interval interval) {
        return service.series(me.id(), me.user().timezone(), metric, days, interval);
    }

    @GetMapping("/health/latest")
    @Operation(summary = "Latest reading per metric (comma-separated codes; blood_pressure pairs the two values).")
    public List<ReadingOut> latest(CurrentUser me, @RequestParam @Size(min = 1, max = 400) String metrics) {
        return service.latest(me.id(), me.user().timezone(), codes(metrics));
    }

    @GetMapping("/devices")
    public List<DeviceOut> devices(CurrentUser me) {
        return service.devices(me.id(), me.user().timezone());
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> forgetDevice(CurrentUser me, @PathVariable UUID id) {
        service.forgetDevice(me, id);
        return ResponseEntity.noContent().build();
    }
}
