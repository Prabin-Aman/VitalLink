package com.vitallink.api.health;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.health.HealthDtos.ConnectFinishIn;
import com.vitallink.api.health.HealthDtos.ConnectStartOut;
import com.vitallink.api.health.HealthDtos.ConnectionOut;
import com.vitallink.api.health.HealthDtos.SyncOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device clouds connected with OAuth. Today: Google Health (Fitbit and Pixel Watch). The browser opens the
 * consent page from {@code start}, Google sends it back to the portal's callback page, and the page posts the
 * code to {@code finish}, which stores the grant and runs the first sync (30 days).
 */
@RestController
@RequestMapping("/v1/me/connections")
@Tag(name = "health")
public class ConnectionsController {

    private final GoogleHealth google;

    public ConnectionsController(GoogleHealth google) {
        this.google = google;
    }

    @GetMapping
    @Operation(summary = "Device clouds the person can connect, and whether each is connected.")
    public List<ConnectionOut> list(CurrentUser me) {
        return List.of(google.status(me.id()));
    }

    @PostMapping("/google-health/start")
    @Operation(summary = "The Google consent page to open (read-only activity, body measurements and sleep).")
    public ConnectStartOut start(CurrentUser me) {
        return new ConnectStartOut(google.authorizeUrl(me.id()));
    }

    @PostMapping("/google-health/finish")
    @Operation(summary = "The code and state Google returned; stores the grant and syncs the last 30 days.")
    public SyncOut finish(CurrentUser me, @Valid @RequestBody ConnectFinishIn body) {
        return google.finish(me.id(), me.user().timezone(), body.code(), body.state());
    }

    @PostMapping("/google-health/sync")
    @Operation(summary = "Pull readings since the last sync (with a day of overlap). The nightly job does this too.")
    public SyncOut sync(CurrentUser me) {
        return google.sync(me.id(), me.user().timezone());
    }

    @DeleteMapping("/google-health")
    @Operation(summary = "Stop syncing and revoke the grant. Readings already stored are kept.")
    public ResponseEntity<Void> disconnect(CurrentUser me) {
        google.disconnect(me.id());
        return ResponseEntity.noContent().build();
    }
}
