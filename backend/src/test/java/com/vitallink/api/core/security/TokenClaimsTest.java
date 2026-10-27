package com.vitallink.api.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import com.vitallink.api.core.ApiError;
import java.util.Date;
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
}
