package com.vitallink.api.ops;

import com.vitallink.api.core.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated health checks: {@code /health} says the process is up, {@code /ready} that the database is reachable. */
@RestController
@Tag(name = "ops")
public class OpsController {

    private final JdbcClient jdbc;

    public OpsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    @Operation(summary = "The process is up.")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/ready")
    @Operation(summary = "The database is reachable. The uptime monitor pings this, which also keeps Supabase awake.")
    public Map<String, String> ready() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
        } catch (RuntimeException e) {
            throw new ApiError(503, "db_unavailable", "The database isn't reachable.");
        }
        return Map.of("status", "ready");
    }
}
