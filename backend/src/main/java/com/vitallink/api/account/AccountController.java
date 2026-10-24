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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me")
@Tag(name = "account")
public class AccountController {

    private final AccountService service;
    private final DataRightsService rights;

    public AccountController(AccountService service, DataRightsService rights) {
        this.service = service;
        this.rights = rights;
    }

    @GetMapping
    @Operation(summary = "The signed-in account. The first call creates it.")
    public Me me(CurrentUser me) {
        return service.me(me);
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
