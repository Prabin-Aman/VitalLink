package com.vitallink.api.account;

import com.vitallink.api.core.security.Claims;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * The verified caller's account, created on first use. Every {@code /v1} handler takes one as a
 * parameter; {@code CurrentUserResolver} supplies it.
 */
public record CurrentUser(User user, Claims claims) {

    /** A row of {@code users}. */
    public record User(
            UUID id,
            UUID authSubject,
            String email,
            OffsetDateTime emailConfirmedAt,
            String timezone,
            OffsetDateTime createdAt) {}

    public UUID id() {
        return user.id();
    }

    public ZoneId zone() {
        return ZoneId.of(user.timezone());
    }
}
