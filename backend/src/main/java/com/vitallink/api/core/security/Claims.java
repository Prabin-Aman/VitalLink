package com.vitallink.api.core.security;

import java.time.Instant;
import java.util.UUID;

/**
 * The verified caller, from the access token.
 *
 * @param subject Supabase's user id ({@code sub})
 * @param emailVerified Supabase sets this once the address is confirmed
 * @param aal "aal1" password/OAuth, "aal2" with a second factor
 */
public record Claims(
        UUID subject,
        String email,
        boolean emailVerified,
        String aal,
        long issuedAt,
        String firstName,
        String lastName) {

    /** For sensitive actions (delete account): signed in within the last {@code seconds}. */
    public boolean isRecent(long seconds) {
        return Instant.now().getEpochSecond() - issuedAt <= seconds;
    }
}
