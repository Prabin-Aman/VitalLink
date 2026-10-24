package com.vitallink.api.tools;

import com.vitallink.api.core.Settings;
import com.vitallink.api.core.db.DatabaseConfig.Connection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Configuration for the command-line tools: environment variables, with backend/.env filling the gaps. */
final class Env {

    private Env() {}

    static Map<String, String> read() {
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

    static Settings settings() {
        Map<String, String> env = read();
        return new Settings(
                env.get("ENV"), env.get("CORS_ORIGIN"), env.get("SUPABASE_URL"), null, env.get("DEV_JWT_SECRET"));
    }

    static Connection database() {
        Map<String, String> env = read();
        return Connection.parse(env.get("DATABASE_URL"), env.get("DATABASE_USER"), env.get("DATABASE_PASSWORD"));
    }
}
