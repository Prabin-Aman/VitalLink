package com.vitallink.api.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The connection pool. {@code DATABASE_URL} may be a JDBC URL (with {@code DATABASE_USER} and
 * {@code DATABASE_PASSWORD}) or the {@code postgresql://user:password@host:port/db} form that Supabase
 * and Render show, so the same value can be pasted from their dashboards.
 */
@Configuration
public class DatabaseConfig {

    public static final String SCHEMA = "vitallink";

    /** A parsed connection string. */
    public record Connection(String jdbcUrl, String user, String password) {

        public static Connection parse(String url, String user, String password) {
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("DATABASE_URL isn't set (see backend/.env.example)");
            }
            if (url.startsWith("jdbc:")) {
                return new Connection(url, blankToNull(user), blankToNull(password));
            }
            // postgresql://, postgres:// or postgresql+asyncpg:// (the old Python form)
            URI uri = URI.create(url.replaceFirst("^postgres(ql)?(\\+\\w+)?://", "postgresql://"));
            String userInfo = uri.getRawUserInfo();
            String u = user;
            String p = password;
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                u = decode(parts[0]);
                p = parts.length > 1 ? decode(parts[1]) : p;
            }
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return new Connection(
                    "jdbc:postgresql://" + uri.getHost() + port + uri.getRawPath() + query,
                    blankToNull(u),
                    blankToNull(p));
        }

        /** The same server, another database (tests use {@code <db>_test}). */
        public Connection withDatabase(String database) {
            return new Connection(jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1"), user, password);
        }

        public String database() {
            String path = jdbcUrl.replaceFirst("^jdbc:postgresql://[^/]+/", "");
            int q = path.indexOf('?');
            return q < 0 ? path : path.substring(0, q);
        }

        private static String decode(String s) {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }

    @Bean
    DataSource dataSource(Environment env) {
        Connection c = Connection.parse(
                env.getProperty("DATABASE_URL"),
                env.getProperty("DATABASE_USER"),
                env.getProperty("DATABASE_PASSWORD"));
        HikariConfig config = new HikariConfig();
        config.setPoolName("vitallink");
        config.setJdbcUrl(c.jdbcUrl());
        config.setUsername(c.user());
        config.setPassword(c.password());
        // Supabase free tier, session pooler: keep the pool small (docs/08-operations-and-testing.md).
        config.setMaximumPoolSize(env.getProperty("DATABASE_POOL_SIZE", Integer.class, 10));
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        // Start even when the database is down: /ready then says so instead of the process crashing.
        config.setInitializationFailTimeout(-1);
        // Unqualified names never resolve into another schema by accident.
        config.setConnectionInitSql("SET search_path TO public");
        return new HikariDataSource(config);
    }
}
