package com.vitallink.api.core;

/** camelCase (Java) ↔ snake_case (JSON, SQL). */
public final class Naming {

    private Naming() {}

    public static String snake(String camel) {
        if (camel == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** "full_body" → "Full body". */
    public static String label(String value) {
        String spaced = value.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
