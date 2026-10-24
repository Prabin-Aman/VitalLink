package com.vitallink.api.core.web;

import com.vitallink.api.core.ApiError;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A PATCH body, where a missing field means "leave it" and {@code null} means "clear it". Records can't
 * tell the two apart, so PATCH handlers take the raw JSON object and read each field through here.
 * Unknown fields are refused (mass assignment).
 */
public final class Patch {

    private final Map<String, Object> body;

    private Patch(Map<String, Object> body) {
        this.body = body;
    }

    public static Patch of(Map<String, Object> body, Set<String> allowed) {
        Map<String, Object> b = body == null ? Map.of() : body;
        for (String key : b.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(key, "extra fields are not permitted");
            }
        }
        return new Patch(b);
    }

    public static ApiError invalid(String field, String msg) {
        return new ApiError(
                422,
                "invalid_request",
                "The request body or query is invalid.",
                List.of(Map.of("loc", List.of("body", field), "msg", msg)));
    }

    public boolean has(String key) {
        return body.containsKey(key);
    }

    public boolean isNull(String key) {
        return body.containsKey(key) && body.get(key) == null;
    }

    public Set<String> keys() {
        return body.keySet();
    }

    /** A string, stripped, of {@code min}–{@code max} characters, optionally matching a pattern; or null. */
    public String string(String key, int min, int max, Pattern pattern) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw invalid(key, "must be a string");
        }
        String t = pattern == null ? s.strip() : s;
        if (t.length() < min || t.length() > max) {
            throw invalid(key, "must be %d–%d characters".formatted(min, max));
        }
        if (pattern != null && !pattern.matcher(t).matches()) {
            throw invalid(key, "has the wrong format");
        }
        return t;
    }

    public Double number(String key, double min, double max) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Number n) || v instanceof Boolean) {
            throw invalid(key, "must be a number");
        }
        double d = n.doubleValue();
        if (d < min || d > max) {
            throw invalid(key, "must be between %s and %s".formatted(trim(min), trim(max)));
        }
        return d;
    }

    public Integer integer(String key, int min, int max) {
        Double d = number(key, min, max);
        if (d == null) {
            return null;
        }
        if (d != Math.rint(d)) {
            throw invalid(key, "must be a whole number");
        }
        return d.intValue();
    }

    public Boolean bool(String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Boolean b)) {
            throw invalid(key, "must be true or false");
        }
        return b;
    }

    public <E extends Enum<E>> E enumValue(String key, Class<E> type) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, String.valueOf(v));
        } catch (IllegalArgumentException e) {
            throw invalid(key, "is not one of the allowed values");
        }
    }

    public LocalDate date(String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(v));
        } catch (DateTimeParseException e) {
            throw invalid(key, "must be a date (YYYY-MM-DD)");
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
