package com.vitallink.api.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import com.vitallink.api.core.ApiError;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the API takes from a verified token's claims (review finding R2). */
class TokenClaimsTest {

    static JWTClaimsSet claims(String email, Map<String, Object> meta) {
        return new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", email)
                .issueTime(new Date())
                .claim("user_metadata", meta)
                .build();
    }

    @Test
    void signUpNamesAreStrippedAndCutToTheProfileLimit() {
        Claims c = TokenVerifier.parse(
                claims("ana@example.com", Map.of("first_name", "  " + "A".repeat(200) + " ", "last_name", "   ")));
        assertThat(c.firstName()).hasSize(60);
        assertThat(c.lastName()).isNull();
        assertThat(TokenVerifier.parse(claims("ana@example.com", Map.of("first_name", " Ana ")))
                        .firstName())
                .isEqualTo("Ana");
        assertThat(TokenVerifier.parse(claims("ana@example.com", Map.of("first_name", 42)))
                        .firstName())
                .isNull();
    }

    @Test
    void emailsLongerThanAnAddressCanBeAreRefused() {
        String tooLong = "a".repeat(250) + "@example.com";
        assertThatThrownBy(() -> TokenVerifier.parse(claims(tooLong, Map.of())))
                .isInstanceOfSatisfying(
                        ApiError.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    /** A refreshed Supabase token has a new iat but keeps the sign-in times in amr: delete account needs the latter. */
    @Test
    void recentSignInComesFromAmrNotTheRefreshedIssueTime() {
        long now = Instant.now().getEpochSecond();
        JWTClaimsSet refreshed = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "ana@example.com")
                .issueTime(new Date())
                .claim("amr", List.of(Map.of("method", "password", "timestamp", now - 3600)))
                .build();
        assertThat(TokenVerifier.parse(refreshed).isRecent(600)).isFalse();

        JWTClaimsSet fresh = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "ana@example.com")
                .issueTime(new Date())
                .claim(
                        "amr",
                        List.of(
                                Map.of("method", "otp", "timestamp", now - 7200),
                                Map.of("method", "password", "timestamp", now - 30)))
                .build();
        assertThat(TokenVerifier.parse(fresh).isRecent(600)).isTrue();
        // Dev tokens have no amr: their iat counts.
        assertThat(TokenVerifier.parse(claims("ana@example.com", Map.of())).isRecent(600))
                .isTrue();
    }

    /** Google through Supabase has no first_name/last_name: names come from what it does send. */
    @Test
    void googleSignUpsGetTheirNameFromTheFullNameOrGivenAndFamilyNames() {
        Claims full = TokenVerifier.parse(claims("ana@example.com", Map.of("full_name", "  Ana  de la Cruz ")));
        assertThat(full.firstName()).isEqualTo("Ana");
        assertThat(full.lastName()).isEqualTo("de la Cruz");

        Claims nameOnly = TokenVerifier.parse(claims("ana@example.com", Map.of("name", "Ana")));
        assertThat(nameOnly.firstName()).isEqualTo("Ana");
        assertThat(nameOnly.lastName()).isNull();

        Claims given = TokenVerifier.parse(
                claims("ana@example.com", Map.of("given_name", "Ana", "family_name", "Cruz", "full_name", "A C")));
        assertThat(given.firstName()).isEqualTo("Ana");
        assertThat(given.lastName()).isEqualTo("Cruz");

        // The sign-up form's own fields win, and a full name that isn't text is ignored.
        Claims form = TokenVerifier.parse(claims("ana@example.com", Map.of("first_name", "Ann", "full_name", "A C")));
        assertThat(form.firstName()).isEqualTo("Ann");
        assertThat(form.lastName()).isNull();
        assertThat(TokenVerifier.parse(claims("ana@example.com", Map.of("full_name", 42)))
                        .firstName())
                .isNull();
    }
}
