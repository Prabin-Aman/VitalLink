package com.vitallink.api.core.security;

import java.time.Instant;
import java.util.UUID;

/**
 * The verified caller, from the access token.
 *
 * @param subject Supabase's user id ({@code sub})
 * @param emailVerified {@code user_metadata.email_verified}: trusted for dev tokens only: users can write
 *     their own user_metadata, so in production {@code AccountService} asks Supabase instead
 * @param aal "aal1" password/OAuth, "aal2" with a second factor
 * @param signedInAt when the user last signed in: Supabase's latest {@code amr} timestamp, else the token's
 *     {@code iat} (dev tokens). Not just {@code iat}: Supabase reissues tokens every hour without a sign-in.
 */
public record Claims(
        UUID subject,
        String email,
        boolean emailVerified,
        String aal,
        long signedInAt,
        String firstName,
        String lastName) {

    /** For sensitive actions (delete account): signed in within the last {@code seconds}. */
    public boolean isRecent(long seconds) {
        return Instant.now().getEpochSecond() - signedInAt <= seconds;
    }
}
