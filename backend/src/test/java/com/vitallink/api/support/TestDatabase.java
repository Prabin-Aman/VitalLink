package com.vitallink.api.support;

import com.vitallink.api.core.db.DatabaseConfig.Connection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * Tests run against a real Postgres (docs/08-operations-and-testing.md §8.7): {@code <your db>_test}, next
 * to the database in DATABASE_URL. It's created if missing; Flyway migrates it when the context starts.
 * Without Postgres the tests are skipped, or fail when REQUIRE_DB is set (CI).
 */
public final class TestDatabase {

    private static Connection connection;
    private static String problem;

    private TestDatabase() {}

    /** Environment variables, with backend/.env filling the gaps (as the app does). */
    public static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(file)) {
                p.load(reader);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            p.forEach((k, v) -> env.put(k.toString(), v.toString().strip()));
        }
        env.putAll(System.getenv());
        return env;
    }

    public static synchronized Connection connection() {
        if (connection == null && problem == null) {
            Map<String, String> env = env();
            try {
                Connection base = Connection.parse(
                        env.get("DATABASE_URL"), env.get("DATABASE_USER"), env.get("DATABASE_PASSWORD"));
                Connection test =
                        base.database().endsWith("_test") ? base : base.withDatabase(base.database() + "_test");
                ensure(base, test.database());
                connection = test;
            } catch (IllegalStateException | SQLException e) {
                problem = "Postgres isn't reachable (" + e.getMessage() + "); run `docker compose up`";
            }
        }
        if (problem != null) {
            if (env().containsKey("REQUIRE_DB")) {
                Assertions.fail(problem);
            }
            Assumptions.abort(problem);
        }
        return connection;
    }

    private static void ensure(Connection base, String name) throws SQLException {
        try (var admin = DriverManager.getConnection(
                        base.withDatabase("postgres").jdbcUrl(), base.user(), base.password());
                var st = admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            st.setString(1, name);
            if (!st.executeQuery().next()) {
                try (var create = admin.createStatement()) {
                    create.execute("CREATE DATABASE \"" + name.replace("\"", "") + "\"");
                }
            }
        }
        // A test database left by the Python/Alembic backend has no Flyway history: start it afresh.
        var test = base.withDatabase(name);
        try (var conn = DriverManager.getConnection(test.jdbcUrl(), test.user(), test.password());
                var st = conn.createStatement()) {
            var rs = st.executeQuery("SELECT to_regclass('vitallink.alembic_version') IS NOT NULL");
            rs.next();
            if (rs.getBoolean(1)) {
                st.execute("DROP SCHEMA vitallink CASCADE");
            }
        }
    }

    /** A plain JDBC connection to the test database, for checks below the API. */
    public static java.sql.Connection open() throws SQLException {
        Connection c = connection();
        return DriverManager.getConnection(c.jdbcUrl(), c.user(), c.password());
    }
}
