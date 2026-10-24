package com.vitallink.api.sharing;

import com.vitallink.api.account.AccountService;
import com.vitallink.api.account.AccountService.PersonInfo;
import com.vitallink.api.care.CareDtos.Appointment;
import com.vitallink.api.care.CareDtos.Condition;
import com.vitallink.api.care.CareDtos.MedicationsOverview;
import com.vitallink.api.care.CareService;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.audit.Audit.Scope;
import com.vitallink.api.core.consent.ConsentGate;
import com.vitallink.api.health.Catalogue;
import com.vitallink.api.health.HealthDtos.BloodPressureSeries;
import com.vitallink.api.health.HealthDtos.Dashboard;
import com.vitallink.api.health.HealthDtos.Interval;
import com.vitallink.api.health.HealthDtos.ReadingOut;
import com.vitallink.api.health.HealthDtos.Series;
import com.vitallink.api.health.HealthService;
import com.vitallink.api.nutrition.NutritionDtos.NutritionDay;
import com.vitallink.api.nutrition.NutritionService;
import com.vitallink.api.records.RecordDtos.DownloadOut;
import com.vitallink.api.records.RecordDtos.HealthRecord;
import com.vitallink.api.records.RecordDtos.LabPanel;
import com.vitallink.api.records.RecordService;
import com.vitallink.api.training.TrainingDtos.Training;
import com.vitallink.api.training.TrainingDtos.WorkoutOut;
import com.vitallink.api.training.TrainingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shared views: someone else's data, read-only, through the consent gate. Every handler takes a
 * {@link ConsentGate} and calls {@code require(scope)} before reading; there are no writes here (guardrail
 * tests enforce both).
 */
@RestController
@RequestMapping("/v1/users/{user_id}")
@Tag(name = "shared views")
public class SharedViewsController {

    private final AccountService accounts;
    private final HealthService health;
    private final TrainingService training;
    private final NutritionService nutrition;
    private final CareService care;
    private final RecordService records;
    private final FamilyService family;
    private final com.vitallink.api.health.HealthTabsService tabs;

    public SharedViewsController(
            AccountService accounts,
            HealthService health,
            TrainingService training,
            NutritionService nutrition,
            CareService care,
            RecordService records,
            FamilyService family,
            com.vitallink.api.health.HealthTabsService tabs) {
        this.tabs = tabs;
        this.records = records;
        this.family = family;
        this.accounts = accounts;
        this.health = health;
        this.training = training;
        this.nutrition = nutrition;
        this.care = care;
    }

    /** Only reached after the gate allowed a read, so the owner exists. */
    private PersonInfo owner(ConsentGate gate) {
        return accounts.person(gate.ownerId());
    }

    @GetMapping("/health/dashboard")
    @Operation(summary = "Vitals only; the readiness score is left out because it also uses sleep data.")
    public Dashboard dashboard(ConsentGate gate) {
        gate.require(Scope.vitals);
        return health.dashboard(gate.ownerId(), owner(gate).timezone(), 480, false);
    }

    @GetMapping("/health/series/blood_pressure")
    public BloodPressureSeries bloodPressure(
            ConsentGate gate,
            @RequestParam(defaultValue = "7") @Min(1) @Max(400) int days,
            @RequestParam(defaultValue = "day") Interval interval) {
        gate.require(Scope.vitals);
        return health.bloodPressureSeries(gate.ownerId(), owner(gate).timezone(), days, interval);
    }

    @GetMapping("/health/series/{metric}")
    public Series series(
            ConsentGate gate,
            @PathVariable String metric,
            @RequestParam(defaultValue = "7") @Min(1) @Max(400) int days,
            @RequestParam(defaultValue = "day") Interval interval) {
        var m = Catalogue.get(metric);
        if (m == null) {
            throw new ApiError(404, "unknown_metric", "There's no metric called '" + metric + "'.");
        }
        gate.require(m.scope());
        return health.series(gate.ownerId(), owner(gate).timezone(), metric, days, interval);
    }

    @GetMapping("/health/latest")
    public List<ReadingOut> latest(ConsentGate gate, @RequestParam @Size(min = 1, max = 400) String metrics) {
        List<String> codes = Arrays.stream(metrics.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .limit(20)
                .toList();
        for (String c : codes) {
            if (!c.equals(Catalogue.BLOOD_PRESSURE) && Catalogue.get(c) == null) {
                throw new ApiError(404, "unknown_metric", "There's no metric called '" + c + "'.");
            }
        }
        TreeSet<Scope> scopes = new TreeSet<>(java.util.Comparator.comparing(Scope::name));
        codes.forEach(c -> scopes.add(
                c.equals(Catalogue.BLOOD_PRESSURE)
                        ? Scope.vitals
                        : Catalogue.get(c).scope()));
        scopes.forEach(gate::require);
        return health.latest(gate.ownerId(), owner(gate).timezone(), codes);
    }

    @GetMapping("/workouts")
    public List<WorkoutOut> workouts(ConsentGate gate, @RequestParam(defaultValue = "7") @Min(1) @Max(400) int days) {
        gate.require(Scope.activity);
        return training.listWorkouts(gate.ownerId(), owner(gate).timezone(), days);
    }

    @GetMapping("/training")
    public Training training(ConsentGate gate) {
        gate.require(Scope.activity);
        return training.training(gate.ownerId(), owner(gate).timezone());
    }

    @GetMapping("/nutrition/day")
    public NutritionDay nutritionDay(ConsentGate gate, @RequestParam(required = false) LocalDate date) {
        gate.require(Scope.nutrition);
        return nutrition.day(gate.ownerId(), owner(gate).timezone(), date);
    }

    // ---------------------------------------------------------------- care (records scope)

    @GetMapping("/medications")
    public MedicationsOverview medications(ConsentGate gate) {
        gate.require(Scope.records);
        return care.overview(gate.ownerId(), owner(gate).timezone());
    }

    @GetMapping("/appointments")
    public List<Appointment> appointments(ConsentGate gate, @RequestParam(defaultValue = "false") boolean upcoming) {
        gate.require(Scope.records);
        return care.appointments(gate.ownerId(), owner(gate).timezone(), null, null, upcoming, 200);
    }

    @GetMapping("/conditions")
    public List<Condition> conditions(ConsentGate gate) {
        gate.require(Scope.records);
        return care.conditions(gate.ownerId());
    }

    // ---------------------------------------------------------------- records (records scope)

    @GetMapping("/records")
    public List<HealthRecord> records(ConsentGate gate) {
        gate.require(Scope.records);
        return records.list(gate.ownerId(), owner(gate).timezone(), null);
    }

    @GetMapping("/records/{recordId}/download")
    public DownloadOut download(ConsentGate gate, @PathVariable UUID recordId) {
        gate.require(Scope.records);
        return records.download(gate.ownerId(), recordId);
    }

    @GetMapping("/labs")
    public List<LabPanel> labs(ConsentGate gate) {
        gate.require(Scope.records);
        return records.labs(gate.ownerId(), owner(gate).timezone());
    }

    @GetMapping("/summary")
    @Operation(
            summary = "The family card: resting HR (vitals); conditions, medication count, next appointment (records).")
    public FamilyService.Summary summary(com.vitallink.api.account.CurrentUser me, ConsentGate gate) {
        return family.summaryFor(me, gate);
    }

    // ---------------------------------------------------------------- My Health tabs

    /**
     * A tab needs its own scope (sleep → sleep; mental and digital → activity; the rest → vitals). Parts that
     * belong to another scope are dropped unless the share covers that too.
     */
    @GetMapping("/health/tabs/{tab}")
    public Object tab(ConsentGate gate, @PathVariable String tab) {
        Scope main = switch (tab) {
            case "sleep" -> Scope.sleep;
            case "mental", "digital" -> Scope.activity;
            case "vitals", "heart", "respiratory", "body" -> Scope.vitals;
            default -> throw new ApiError(404, "unknown_tab", "There's no tab called '" + tab + "'.");
        };
        gate.require(main);
        String tz = owner(gate).timezone();
        UUID owner = gate.ownerId();
        return switch (tab) {
            case "heart" -> {
                var h = tabs.heart(owner, tz);
                yield gate.allows(Scope.activity)
                        ? h
                        : new com.vitallink.api.health.HealthTabDtos.HeartTab(
                                h.resting(), h.readings(), List.of(), h.alerts());
            }
            case "mental" -> {
                var m = tabs.mental(owner, tz);
                boolean vitals = gate.allows(Scope.vitals);
                boolean sleep = gate.allows(Scope.sleep);
                var readings = m.readings().stream()
                        .filter(r -> (vitals || !r.metric().equals("hrv"))
                                && (sleep || !r.metric().equals("sleep_quality")))
                        .toList();
                yield new com.vitallink.api.health.HealthTabDtos.MentalTab(m.mood(), readings, m.mindful(), List.of());
            }
            case "digital" -> {
                var d = tabs.digital(owner, tz);
                yield gate.allows(Scope.sleep)
                        ? d
                        : new com.vitallink.api.health.HealthTabDtos.DigitalTab(
                                d.averageMin(),
                                d.changeMin(),
                                d.week(),
                                d.readings(),
                                d.categories(),
                                null,
                                d.limits());
            }
            case "vitals" -> tabs.vitals(owner, tz);
            case "sleep" -> {
                var sl = tabs.sleep(owner, tz);
                // Sleep insights compare against phone use (activity): only with that scope too.
                yield gate.allows(Scope.activity)
                        ? sl
                        : new com.vitallink.api.health.HealthTabDtos.SleepTab(
                                sl.last(), sl.week(), sl.consistency(), List.of());
            }
            case "respiratory" -> tabs.respiratory(owner, tz);
            default -> tabs.body(owner, tz);
        };
    }
}
