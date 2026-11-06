package com.vitallink.api.account;

import com.vitallink.api.account.AccountDtos.Account;
import com.vitallink.api.account.AccountDtos.Me;
import com.vitallink.api.account.AccountDtos.Targets;
import com.vitallink.api.account.AccountDtos.TargetsPatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in account: profile, targets, profile photo, setup status, export and delete. */
@RestController
@RequestMapping("/v1/me")
@Tag(name = "account")
public class AccountController {

    private final AccountService service;
    private final DataRightsService rights;

    private final AvatarService avatars;

    public AccountController(AccountService service, DataRightsService rights, AvatarService avatars) {
        this.service = service;
        this.rights = rights;
        this.avatars = avatars;
    }

    @GetMapping
    @Operation(summary = "The signed-in account. The first call creates it.")
    public Me me(CurrentUser me) {
        return service.me(me);
    }

    @PostMapping("/onboarding/complete")
    @Operation(summary = "Create Account's setup steps are finished or left for later, so they aren't asked again.")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void completeOnboarding(CurrentUser me) {
        service.completeOnboarding(me);
    }

    @GetMapping("/account")
    public Account account(CurrentUser me) {
        return service.account(me);
    }

    @PatchMapping("/account")
    @Operation(summary = "Change profile fields; a missing field is left alone, null clears an optional one.")
    public Account updateAccount(CurrentUser me, @RequestBody Map<String, Object> body) {
        return service.updateAccount(me, body);
    }

    @PostMapping("/avatar/upload")
    @Operation(
            summary =
                    "Start a profile photo upload (JPEG, PNG or WebP, at most 2 MB): PUT the image to the URL, then confirm.")
    public AvatarService.UploadOut startAvatar(CurrentUser me, @RequestBody AvatarService.UploadIn body) {
        return avatars.startUpload(me, body);
    }

    @PostMapping("/avatar/confirm")
    @Operation(summary = "Check the uploaded image and make it the profile photo; the old one is deleted.")
    public Me confirmAvatar(CurrentUser me, @RequestBody Map<String, String> body) {
        avatars.confirm(me, body.get("key"));
        return service.me(me);
    }

    @DeleteMapping("/avatar")
    public ResponseEntity<Void> removeAvatar(CurrentUser me) {
        avatars.remove(me);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/targets")
    public Targets targets(CurrentUser me) {
        return service.targets(me.id());
    }

    @PatchMapping("/targets")
    public Targets updateTargets(CurrentUser me, @Valid @RequestBody TargetsPatch body) {
        return service.updateTargets(me, body);
    }

    @GetMapping("/export")
    @Operation(summary = "Every row the account owns, as one JSON download.")
    public ResponseEntity<Map<String, Object>> export(CurrentUser me) {
        String name = "vitallink-export-" + LocalDate.now(me.zone()) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(rights.export(me));
    }

    @DeleteMapping
    @Operation(summary = "Delete the account and everything it owns. Needs a sign-in within the last 10 minutes.")
    public ResponseEntity<Void> delete(CurrentUser me) {
        rights.delete(me);
        return ResponseEntity.noContent().build();
    }
}
