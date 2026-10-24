package com.vitallink.api.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rounding as the API has always done it: half to even ("banker's rounding"), like Python's round(). */
public final class Num {

    private Num() {}

    public static double round(double value, int decimals) {
        return BigDecimal.valueOf(value)
                .setScale(decimals, RoundingMode.HALF_EVEN)
                .doubleValue();
    }

    public static Double round(Double value, int decimals) {
        return value == null ? null : round(value.doubleValue(), decimals);
    }

    public static int roundInt(double value) {
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_EVEN).intValueExact();
    }

    public static Integer roundInt(Double value) {
        return value == null ? null : roundInt(value.doubleValue());
    }
}
