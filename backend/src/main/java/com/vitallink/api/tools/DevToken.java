package com.vitallink.api.tools;

import com.vitallink.api.core.security.DevTokens;

/**
 * Prints a dev bearer token for local testing (refused outside ENV=local/test).
 *
 * <pre>
 * ./mvnw -q exec:java -Dexec.mainClass=com.vitallink.api.tools.DevToken -Dexec.args="alex@example.com --first Alex --last Carter"
 * curl -H "Authorization: Bearer &lt;token&gt;" http://localhost:8000/v1/me
 * </pre>
 *
 * The same email always maps to the same account (the subject is derived from the email).
 */
public final class DevToken {

    private DevToken() {}

    public static void main(String[] args) {
        if (args.length == 0 || args[0].startsWith("-")) {
            System.err.println("usage: DevToken <email> [--first NAME] [--last NAME] [--unconfirmed] [--hours N]");
            System.exit(2);
        }
        String email = args[0];
        String first = null;
        String last = null;
        boolean confirmed = true;
        int hours = 12;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--first" -> first = args[++i];
                case "--last" -> last = args[++i];
                case "--unconfirmed" -> confirmed = false;
                case "--hours" -> hours = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        System.out.println(DevTokens.mint(
                Env.settings(), DevTokens.subjectFor(email), email, confirmed, first, last, hours * 3600L, null));
    }
}
