package com.vitallink.api.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Fixed one-hour windows per user, ingest counted apart from everything else. */
class RateLimiterTest {

    static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void refusesOverTheLimitAndSaysWhenToRetry() {
        RateLimiter limiter = new RateLimiter(2, 3, at("2026-10-24T10:59:00Z"));
        UUID user = UUID.randomUUID();
        limiter.check(user, true);
        limiter.check(user, true);
        assertThatThrownBy(() -> limiter.check(user, true)).isInstanceOfSatisfying(RateLimiter.Limited.class, e -> {
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.code()).isEqualTo("rate_limited");
            assertThat(e.retryAfterSeconds()).isEqualTo(60);
        });
        // Other requests have their own budget, and other users theirs.
        limiter.check(user, false);
        limiter.check(UUID.randomUUID(), true);
    }

    /** A clock the test moves forward. */
    static final class Moving extends Clock {
        Instant now;

        Moving(String start) {
            now = Instant.parse(start);
        }

        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @Test
    void aNewHourStartsAFreshWindow() {
        UUID user = UUID.randomUUID();
        Moving clock = new Moving("2026-10-24T10:59:59Z");
        RateLimiter limiter = new RateLimiter(1, 1, clock);
        limiter.check(user, false);
        assertThatThrownBy(() -> limiter.check(user, false)).isInstanceOf(RateLimiter.Limited.class);
        clock.now = Instant.parse("2026-10-24T11:00:00Z");
        limiter.check(user, false);
    }
}
