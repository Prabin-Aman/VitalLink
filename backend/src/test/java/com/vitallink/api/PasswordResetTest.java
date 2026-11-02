package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vitallink.api.account.GoogleSignIn;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Mailer;
import com.vitallink.api.support.ApiTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Forgot password (reset link by email) and Sign in with Google; email and Google are stand-ins. */
class PasswordResetTest extends ApiTest {

    @MockitoBean
    Mailer mailer;

    @MockitoBean
    GoogleSignIn google;

    static final String PASSWORD = "Correct-horse-42";

    String signUp(String email) {
        Map<String, Object> b = new HashMap<>();
        b.put("email", email);
        b.put("password", PASSWORD);
        b.put("first_name", "Sudip");
        b.put("last_name", "Ghorasaini");
        assertThat(post(null, "/v1/auth/sign-up", b).status()).isEqualTo(201);
        return email;
    }

    static String email() {
        return "reset-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** The token in the last reset email sent to that address. */
    String sentToken(String email) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailer, org.mockito.Mockito.atLeastOnce()).send(eq(email), anyString(), body.capture());
        Matcher m = Pattern.compile("reset-password\\?token=([A-Za-z0-9_-]+)").matcher(body.getValue());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void forgotThenResetThenSignInWithTheNewPassword() {
        String email = signUp(email());
        assertThat(post(null, "/v1/auth/forgot-password", Map.of("email", email.toUpperCase()))
                        .status())
                .isEqualTo(202);
        String token = sentToken(email);
        assertThat(token).hasSizeGreaterThan(40);

        assertThat(post(null, "/v1/auth/reset-password", Map.of("token", token, "password", "short"))
                        .status())
                .isEqualTo(422);
        Res reset = post(null, "/v1/auth/reset-password", Map.of("token", token, "password", "New-password-2026!"));
        assertThat(reset.status()).as(reset.text()).isEqualTo(200);
        assertThat(get("Bearer " + reset.at("/access_token").asString(), "/v1/me")
                        .status())
                .isEqualTo(200);

        // The link works once; the old password stops working, the new one signs in.
        assertThat(post(null, "/v1/auth/reset-password", Map.of("token", token, "password", "Another-pass-2026!"))
                        .code())
                .isEqualTo("reset_link_expired");
        assertThat(post(null, "/v1/auth/sign-in", Map.of("email", email, "password", PASSWORD))
                        .status())
                .isEqualTo(401);
        assertThat(post(null, "/v1/auth/sign-in", Map.of("email", email, "password", "New-password-2026!"))
                        .status())
                .isEqualTo(200);
    }

    @Test
    void unknownEmailsGetTheSameAnswerAndNoEmail() {
        String nobody = email();
        assertThat(post(null, "/v1/auth/forgot-password", Map.of("email", nobody))
                        .status())
                .isEqualTo(202);
        verify(mailer, never()).send(eq(nobody), anyString(), anyString());
        assertThat(post(null, "/v1/auth/reset-password", Map.of("token", "made-up", "password", "New-password-2026!"))
                        .status())
                .isEqualTo(410);
    }

    @Test
    void atMostThreeResetEmailsAnHour() {
        String email = signUp(email());
        for (int i = 0; i < 5; i++) {
            post(null, "/v1/auth/forgot-password", Map.of("email", email));
        }
        verify(mailer, org.mockito.Mockito.times(3)).send(eq(email), anyString(), anyString());
    }

    @Test
    void googleSignsUpConfirmedThenSignsTheSameAccountIn() {
        String email = email();
        when(google.verify("good-token")).thenReturn(new GoogleSignIn.GoogleUser("g-" + email, email, "Asha", "Rai"));
        Res first = post(null, "/v1/auth/google", Map.of("credential", "good-token"));
        assertThat(first.status()).as(first.text()).isEqualTo(200);
        String auth = "Bearer " + first.at("/access_token").asString();
        assertThat(get(auth, "/v1/me/account").at("/first_name").asString()).isEqualTo("Asha");
        Res again = post(null, "/v1/auth/google", Map.of("credential", "good-token"));
        assertThat(get("Bearer " + again.at("/access_token").asString(), "/v1/me")
                        .at("/id")
                        .asString())
                .isEqualTo(get(auth, "/v1/me").at("/id").asString());

        // An existing email/password account with the same email signs in through Google too.
        String both = signUp(email());
        when(google.verify("both-token")).thenReturn(new GoogleSignIn.GoogleUser("g-both", both, "Sudip", "G"));
        Res linked = post(null, "/v1/auth/google", Map.of("credential", "both-token"));
        assertThat(get("Bearer " + linked.at("/access_token").asString(), "/v1/me/account")
                        .at("/first_name")
                        .asString())
                .isEqualTo("Sudip");

        when(google.verify("bad")).thenThrow(new ApiError(401, "invalid_google_token", "Google sign-in didn't work."));
        assertThat(post(null, "/v1/auth/google", Map.of("credential", "bad")).status())
                .isEqualTo(401);
    }
}
