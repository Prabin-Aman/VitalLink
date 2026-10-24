package com.vitallink.api.core;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The current time at the database's precision. Postgres keeps microseconds and rounds anything finer, so
 * a nanosecond "now" could be stored as a moment slightly in the future (a share starting "now" would not
 * be live yet when read back).
 */
public final class Clock {

    private Clock() {}

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
