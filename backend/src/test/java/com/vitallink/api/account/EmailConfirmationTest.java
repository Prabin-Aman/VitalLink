package com.vitallink.api.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.core.Settings;
import com.vitallink.api.core.security.Claims;
import com.vitallink.api.core.security.IdentityAdmin;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Review finding R1: {@code user_metadata.email_verified} is written by the user, so in production it can't
 * confirm an email on its own (consent rule 2 rests on it). Supabase's admin API decides.
 */
class EmailConfirmationTest {

    static final Settings PRODUCTION =
            new Settings("production", "https://app.example.com", "https://p.supabase.co", null, null);
    static final Settings TEST = new Settings("test", "http://localhost:5173", null, null, "secret");

    static Claims claims(boolean metadataSaysVerified) {
        return new Claims(
                UUID.randomUUID(),
                "ana@example.com",
                metadataSaysVerified,
                "aal1",
                Instant.now().getEpochSecond(),
                null,
                null);
    }

    /** Supabase's answer, without a network. */
    static IdentityAdmin supabaseSays(boolean confirmed) {
        return new IdentityAdmin("https://p.supabase.co", "service-key") {
            @Override
            public boolean emailConfirmed(UUID subject, String email) {
                return confirmed;
            }
        };
    }

    @Test
    void productionAsksSupabaseInsteadOfTrustingTheToken() {
        assertThat(new AccountService(null, PRODUCTION, supabaseSays(false)).emailConfirmed(claims(true)))
                .isFalse();
        assertThat(new AccountService(null, PRODUCTION, supabaseSays(true)).emailConfirmed(claims(true)))
                .isTrue();
        // The token's flag doesn't matter either way.
        assertThat(new AccountService(null, PRODUCTION, supabaseSays(true)).emailConfirmed(claims(false)))
                .isTrue();
    }

    @Test
    void devTokensAreSignedByUsSoTheirFlagCounts() {
        assertThat(new AccountService(null, TEST, supabaseSays(false)).emailConfirmed(claims(true)))
                .isTrue();
        assertThat(new AccountService(null, TEST, supabaseSays(true)).emailConfirmed(claims(false)))
                .isFalse();
    }
}
