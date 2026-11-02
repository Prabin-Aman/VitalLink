package com.vitallink.api.tools;

import java.sql.DriverManager;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Gives an existing account an email/password sign-in (local only), e.g. the demo account:
 *
 * <pre>
 * ./mvnw -q compile exec:java -Dexec.mainClass=com.vitallink.api.tools.SetPassword -Dexec.args="demo@example.com"
 * </pre>
 *
 * Reads the password from the VITALLINK_PASSWORD environment variable, so it isn't left in shell history;
 * the same rules as sign-up apply (12+ characters with a number and a symbol).
 */
public final class SetPassword {

    private SetPassword() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: VITALLINK_PASSWORD=... SetPassword <email>");
            System.exit(2);
        }
        if (!Env.settings().isDev()) {
            throw new IllegalStateException("Passwords are set this way only in local and test environments.");
        }
        String password = System.getenv("VITALLINK_PASSWORD");
        String problem = com.vitallink.api.account.PasswordAuth.passwordProblem(password);
        if (problem != null) {
            System.err.println("VITALLINK_PASSWORD: " + problem);
            System.exit(2);
        }
        var db = Env.database();
        try (var c = DriverManager.getConnection(db.jdbcUrl(), db.user(), db.password());
                var find = c.prepareStatement("SELECT id FROM vitallink.users WHERE email = ?")) {
            find.setString(1, args[0].strip().toLowerCase());
            UUID id;
            try (var rs = find.executeQuery()) {
                if (!rs.next()) {
                    System.err.println("No account with that email. Sign up, or run DemoSeed first.");
                    System.exit(1);
                    return;
                }
                id = rs.getObject(1, UUID.class);
            }
            try (var up = c.prepareStatement("""
                    INSERT INTO vitallink.credentials (user_id, password_hash) VALUES (?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                      failed_attempts = 0, locked_until = NULL, updated_at = now()
                    """)) {
                up.setObject(1, id);
                up.setString(2, new BCryptPasswordEncoder(12).encode(password));
                up.executeUpdate();
            }
        }
        System.out.println("Password set for " + args[0] + ".");
    }
}
