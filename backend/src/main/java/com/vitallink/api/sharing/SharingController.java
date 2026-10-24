package com.vitallink.api.sharing;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.web.RequestIdFilter;
import com.vitallink.api.sharing.SharingDtos.AccessLogEntry;
import com.vitallink.api.sharing.SharingDtos.Share;
import com.vitallink.api.sharing.SharingDtos.ShareIn;
import com.vitallink.api.sharing.SharingDtos.SharedWithMe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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

/** The owner's side of sharing. */
@RestController
@RequestMapping("/v1/me")
@Tag(name = "sharing")
public class SharingController {

    private final SharingService service;
    private final FamilyService family;

    public SharingController(SharingService service, FamilyService family) {
        this.service = service;
        this.family = family;
    }

    @GetMapping("/family")
    @Operation(summary = "Me, then everyone who shares with me; each summary holds only what their share covers.")
    public List<FamilyService.FamilyMember> family(CurrentUser me, HttpServletRequest request) {
        return family.family(me, RequestIdFilter.current(request));
    }

    @PostMapping("/shares")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Share chosen scopes with a person, read-only, from starts_at until expires_at or revocation.")
    public Share createShare(CurrentUser me, @Valid @RequestBody ShareIn body, HttpServletRequest request) {
        return service.createShare(me, body, RequestIdFilter.current(request));
    }

    @GetMapping("/shares")
    public List<Share> listShares(CurrentUser me) {
        return service.listShares(me);
    }

    @DeleteMapping("/shares/{id}")
    public ResponseEntity<Void> revokeShare(CurrentUser me, @PathVariable UUID id, HttpServletRequest request) {
        service.revokeShare(me, id, RequestIdFilter.current(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/shared-with-me")
    public List<SharedWithMe> sharedWithMe(CurrentUser me) {
        return service.sharedWithMe(me);
    }

    @GetMapping("/access-log")
    public List<AccessLogEntry> accessLog(
            CurrentUser me,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return service.accessLog(me, limit, offset);
    }
}
