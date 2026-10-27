package com.vitallink.api.jobs;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Settings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** For the scheduler only (GitHub Actions cron). Not under /v1: no user token, a shared secret instead. */
@RestController
@Tag(name = "ops")
public class JobsController {

    /** In production the secret must be long enough not to be guessed (e.g. {@code openssl rand -hex 32}). */
    static final int MIN_SECRET = 32;

    private final NightlyJob nightly;
    private final String secret;
    private final ReentrantLock running = new ReentrantLock();

    public JobsController(NightlyJob nightly, Settings settings, @Value("${JOB_SECRET:}") String secret) {
        this.nightly = nightly;
        // A short secret in production counts as none: the endpoint stays shut rather than guessable.
        this.secret = !settings.isDev() && secret.strip().length() < MIN_SECRET ? "" : secret;
    }

    @PostMapping("/internal/jobs/nightly")
    @Operation(
            summary = "Run the nightly job now (X-Job-Secret header). Synchronous; returns a summary, with "
                    + "HTTP 500 when any user or step failed so the scheduled workflow fails visibly.")
    public ResponseEntity<Map<String, Object>> runNightly(
            @RequestHeader(value = "X-Job-Secret", required = false) String given) {
        if (secret.isBlank()) {
            throw new ApiError(503, "jobs_not_configured", "JOB_SECRET isn't set.");
        }
        byte[] a = secret.getBytes(StandardCharsets.UTF_8);
        byte[] b = given == null ? new byte[0] : given.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) { // constant time
            throw new ApiError(401, "unauthorized", "Wrong job secret.");
        }
        if (!running.tryLock()) {
            throw new ApiError(409, "job_running", "The nightly job is already running.");
        }
        try {
            Map<String, Object> summary = nightly.run();
            boolean failed = ((Number) summary.get("users_failed")).intValue() > 0
                    || !((java.util.List<?>) summary.get("steps_failed")).isEmpty();
            return ResponseEntity.status(failed ? 500 : 200).body(summary);
        } finally {
            running.unlock();
        }
    }
}
