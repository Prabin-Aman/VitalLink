package com.vitallink.api.health;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.health.HealthTabDtos.HealthOverview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me/health")
@Tag(name = "health")
public class HealthTabsController {

    private final HealthTabsService tabs;

    public HealthTabsController(HealthTabsService tabs) {
        this.tabs = tabs;
    }

    @GetMapping("/overview")
    @Operation(summary = "Activity rings, readings by section, data sources and insights.")
    public HealthOverview overview(CurrentUser me) {
        return tabs.overview(me.id(), me.user().timezone());
    }

    @GetMapping("/tabs/{tab}")
    @Operation(summary = "One My Health tab: vitals, sleep, heart, respiratory, body, mental or digital.")
    public Object tab(CurrentUser me, @PathVariable String tab) {
        return tab(tabs, me.id(), me.user().timezone(), tab);
    }

    static Object tab(HealthTabsService tabs, UUID userId, String tz, String tab) {
        return switch (tab) {
            case "vitals" -> tabs.vitals(userId, tz);
            case "sleep" -> tabs.sleep(userId, tz);
            case "heart" -> tabs.heart(userId, tz);
            case "respiratory" -> tabs.respiratory(userId, tz);
            case "body" -> tabs.body(userId, tz);
            case "mental" -> tabs.mental(userId, tz);
            case "digital" -> tabs.digital(userId, tz);
            default -> throw new ApiError(404, "unknown_tab", "There's no tab called '" + tab + "'.");
        };
    }
}
