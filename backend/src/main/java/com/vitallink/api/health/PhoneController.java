package com.vitallink.api.health;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.health.HealthDtos.IngestIn;
import com.vitallink.api.health.HealthDtos.IngestOut;
import com.vitallink.api.health.HealthDtos.PhoneCodeOut;
import com.vitallink.api.health.HealthDtos.PhoneLinkIn;
import com.vitallink.api.health.HealthDtos.PhoneLinkOut;
import com.vitallink.api.health.HealthDtos.PhoneOut;
import com.vitallink.api.health.HealthDtos.PhoneStatusOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Android helper app (Health Connect): the person links a phone with a code from Connected Devices, and the
 * app uploads 5-minute buckets with the token it got. The token can only upload and check whose account it is.
 */
@RestController
@Tag(name = "health")
public class PhoneController {

    private final PhoneLinks phones;
    private final HealthService health;

    public PhoneController(PhoneLinks phones, HealthService health) {
        this.phones = phones;
        this.health = health;
    }

    @PostMapping("/v1/me/phones/code")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "A one-time code (10 minutes) to type into the Android app. A new code replaces the last.")
    public PhoneCodeOut code(CurrentUser me) {
        return phones.newCode(me);
    }

    @GetMapping("/v1/me/phones")
    public List<PhoneOut> list(CurrentUser me) {
        return phones.list(me.id());
    }

    @DeleteMapping("/v1/me/phones/{id}")
    @Operation(summary = "Unlink a phone: its token stops working. Readings it uploaded stay.")
    public ResponseEntity<Void> remove(CurrentUser me, @PathVariable UUID id) {
        phones.remove(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/phone/link")
    @Operation(summary = "The Android app exchanges a code for an upload token (no account token). 400 invalid_code.")
    public PhoneLinkOut link(@Valid @RequestBody PhoneLinkIn body) {
        return phones.link(body.code(), body.deviceName());
    }

    @GetMapping("/v1/phone/status")
    @Operation(summary = "Whose account this phone token uploads to (Authorization: Phone <token>).")
    public PhoneStatusOut status(PhoneCaller phone) {
        return new PhoneStatusOut(phone.me().user().email());
    }

    @PostMapping("/v1/phone/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary =
                    "Health Connect readings from the Android app, as 5-minute buckets (same body as /me/health/ingest).")
    public IngestOut ingest(PhoneCaller phone, @Valid @RequestBody IngestIn body) {
        if (!body.platform().equals("health_connect")) {
            throw ApiError.invalid("platform must be health_connect.");
        }
        // The device is named by the link, so Disconnect on its card can unlink the phone.
        var devices = body.devices().stream()
                .limit(1)
                .map(d -> new HealthDtos.DeviceIn(phone.linkId().toString(), d.name(), d.kind(), d.metrics()))
                .toList();
        return health.ingest(phone.me(), new IngestIn(body.idempotencyKey(), body.platform(), devices, body.buckets()));
    }
}
