package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.support.ApiTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Email/password accounts (local and test): sign up, sign in, wrong passwords, lockout. */
class AuthTest extends ApiTest {

    static final String PASSWORD = "Correct-horse-42";

    Map<String, Object> signUpBody(String email) {
        Map<String, Object> b = new HashMap<>();
        b.put("email", email);
        b.put("password", PASSWORD);
        b.put("first_name", "Sudip");
        b.put("last_name", "Ghorasaini");
        b.put("phone", "+12145550148");
        b.put("zip", "75201");
        b.put("date_of_birth", "1998-01-08");
        b.put("height_cm", 178);
        return b;
    }

    static String email() {
        return "auth-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    @Test
    void signUpThenUseTheTokenThenSignIn() {
        String email = email();
        Res up = post(null, "/v1/auth/sign-up", signUpBody(email));
        assertThat(up.status()).as(up.text()).isEqualTo(201);
        String auth = "Bearer " + up.at("/access_token").asString();

        Res account = get(auth, "/v1/me/account");
        assertThat(account.status()).isEqualTo(200);
        assertThat(account.at("/first_name").asString()).isEqualTo("Sudip");
        assertThat(account.at("/date_of_birth").asString()).isEqualTo("1998-01-08");
        assertThat(account.at("/height_cm").asDouble()).isEqualTo(178.0);
        assertThat(account.at("/phone").asString()).isEqualTo("+12145550148");

        Res in = post(null, "/v1/auth/sign-in", Map.of("email", email.toUpperCase(), "password", PASSWORD));
        assertThat(in.status()).as(in.text()).isEqualTo(200);
        String again = "Bearer " + in.at("/access_token").asString();
        assertThat(get(again, "/v1/me").at("/id").asString())
                .isEqualTo(get(auth, "/v1/me").at("/id").asString());

        // The same email can't sign up twice.
        assertThat(post(null, "/v1/auth/sign-up", signUpBody(email)).code()).isEqualTo("email_in_use");
    }

    @Test
    void refusesWeakPasswordsAndBadDetailsWithoutCreatingAnything() {
        String email = email();
        Map<String, Object> weak = signUpBody(email);
        weak.put("password", "short1!");
        assertThat(post(null, "/v1/auth/sign-up", weak).at("/error/message").asString())
                .contains("12 characters");
        weak.put("password", "no-numbers-here!");
        assertThat(post(null, "/v1/auth/sign-up", weak).at("/error/message").asString())
                .contains("number");

        Map<String, Object> badPhone = signUpBody(email);
        badPhone.put("phone", "897895");
        assertThat(post(null, "/v1/auth/sign-up", badPhone).status()).isEqualTo(422);
        // Nothing was kept: the same email can still sign up.
        assertThat(post(null, "/v1/auth/sign-up", signUpBody(email)).status()).isEqualTo(201);
    }

    @Test
    void wrongPasswordsLockAfterFive() {
        String email = email();
        post(null, "/v1/auth/sign-up", signUpBody(email));
        Res unknown = post(null, "/v1/auth/sign-in", Map.of("email", "nobody-" + email, "password", PASSWORD));
        Res wrong = post(null, "/v1/auth/sign-in", Map.of("email", email, "password", "Wrong-password-1"));
        // The same answer whether or not the email exists.
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.at("/error").toString())
                .isEqualTo(unknown.at("/error")
                        .toString()
                        .replace(
                                unknown.at("/error/request_id").asString(),
                                wrong.at("/error/request_id").asString()));
        for (int i = 0; i < 4; i++) {
            post(null, "/v1/auth/sign-in", Map.of("email", email, "password", "Wrong-password-1"));
        }
        // Locked now, even with the right password.
        assertThat(post(null, "/v1/auth/sign-in", Map.of("email", email, "password", PASSWORD))
                        .status())
                .isEqualTo(429);
    }
}
