package com.vitallink.api.core.web;

import com.vitallink.api.core.ApiError;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-user request limits (docs/05-api.md §5.1): sync ingest and everything else are counted separately,
 * in fixed one-hour windows. Counts live in memory, which is right while Render runs one instance; a
 * restart forgets them, which only ever errs towards letting a user through.
 */
@Component
public class RateLimiter {

    /** Thrown when a limit is reached; the handler adds {@code Retry-After}. */
    public static class Limited extends ApiError {
        private final long retryAfterSeconds;

        Limited(long retryAfterSeconds) {
            super(
                    429,
                    "rate_limited",
                    "Too many requests. Try again in a few minutes.",
                    Map.of("retry_after_s", retryAfterSeconds));
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private record Window(long hour, int count) {}

    private final int ingestPerHour;
    private final int otherPerHour;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(
            @Value("${vitallink.rate-limit.ingest-per-hour:60}") int ingestPerHour,
            @Value("${vitallink.rate-limit.other-per-hour:3000}") int otherPerHour) {
        this(ingestPerHour, otherPerHour, Clock.systemUTC());
    }

    RateLimiter(int ingestPerHour, int otherPerHour, Clock clock) {
        this.ingestPerHour = ingestPerHour;
        this.otherPerHour = otherPerHour;
        this.clock = clock;
    }

    /** Counts one request for this caller, or refuses it with 429. */
    public void check(UUID subject, boolean ingest) {
        long nowS = clock.instant().getEpochSecond();
        long hour = nowS / 3600;
        String key = subject + (ingest ? ":ingest" : ":other");
        int limit = ingest ? ingestPerHour : otherPerHour;
        Window w = windows.compute(
                key,
                (k, old) ->
                        old == null || old.hour() != hour ? new Window(hour, 1) : new Window(hour, old.count() + 1));
        if (windows.size() > 50_000) {
            windows.values().removeIf(x -> x.hour() < hour); // forget finished windows
        }
        if (w.count() > limit) {
            throw new Limited((hour + 1) * 3600 - nowS);
        }
    }
}
