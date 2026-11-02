package com.vitallink.api.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Email/password sign-up and sign-in for local and test runs (see {@link PasswordAuth}); 404 in production. */
@RestController
@RequestMapping("/v1/auth")
@Tag(name = "account")
public class AuthController {

    private final PasswordAuth auth;

    public AuthController(PasswordAuth auth) {
        this.auth = auth;
    }

    @PostMapping("/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account with an email and password; returns an access token (12 hours).")
    public PasswordAuth.Session signUp(@RequestBody PasswordAuth.SignUpIn body) {
        return auth.signUp(body);
    }

    @GetMapping("/config")
    @Operation(summary = "What the sign-in page can offer: email/password, and Google's client id when it's set up.")
    public java.util.Map<String, Object> config() {
        return auth.config();
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary =
                    "Email a password reset link (30 minutes, single use). Answers the same whether or not the email has an account.")
    public void forgotPassword(@RequestBody java.util.Map<String, String> body) {
        auth.forgotPassword(body.get("email"));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Choose a new password with a reset link's token; signs in.")
    public PasswordAuth.Session resetPassword(@RequestBody java.util.Map<String, String> body) {
        return auth.resetPassword(body.get("token"), body.get("password"));
    }

    @PostMapping("/google")
    @Operation(summary = "Sign in (or up) with a Google ID token from Sign in with Google.")
    public PasswordAuth.Session google(@RequestBody java.util.Map<String, String> body) {
        return auth.signInWithGoogle(body.get("credential"));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Settings › Security: change the password (the current one is required).")
    public org.springframework.http.ResponseEntity<Void> changePassword(
            CurrentUser me, @RequestBody java.util.Map<String, String> body) {
        auth.changePassword(me, body.get("current_password"), body.get("new_password"));
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PostMapping("/sign-in")
    @Operation(summary = "Sign in with an email and password; five wrong passwords lock sign-in for 15 minutes.")
    public PasswordAuth.Session signIn(@RequestBody PasswordAuth.SignInIn body) {
        return auth.signIn(body);
    }
}
