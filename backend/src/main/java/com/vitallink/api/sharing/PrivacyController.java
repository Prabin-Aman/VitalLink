package com.vitallink.api.sharing;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.care.CareService;
import com.vitallink.api.core.web.Patch;
import com.vitallink.api.health.HealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The privacy centre's switches and counts (shares and the access log have their own endpoints). */
@RestController
@RequestMapping("/v1/me/privacy")
@Tag(name = "sharing")
public class PrivacyController {

    public record DeviceCounts(int total, int syncing, int overdue) {}

    /**
     * @param emergencyOnLockScreen whether the phone may show the medical ID on its lock screen (the
     *     phone applies it; nothing is published)
     */
    public record Privacy(boolean emergencyOnLockScreen, DeviceCounts devices) {}

    private final CareService care;
    private final HealthService health;

    public PrivacyController(CareService care, HealthService health) {
        this.care = care;
        this.health = health;
    }

    @GetMapping
    public Privacy privacy(CurrentUser me) {
        int[] d = health.deviceCounts(me.id());
        return new Privacy(care.showOnLockScreen(me.id()), new DeviceCounts(d[0], d[1], d[2]));
    }

    @PatchMapping
    @Operation(summary = "Turn the lock-screen medical ID on or off.")
    public Privacy update(CurrentUser me, @RequestBody Map<String, Object> body) {
        Patch p = Patch.of(body, Set.of("emergency_on_lock_screen"));
        if (p.has("emergency_on_lock_screen")) {
            Boolean on = p.bool("emergency_on_lock_screen");
            if (on == null) {
                throw Patch.invalid("emergency_on_lock_screen", "must be true or false");
            }
            care.updateEmergency(me, Map.of("show_on_lock_screen", on));
        }
        return privacy(me);
    }
}
