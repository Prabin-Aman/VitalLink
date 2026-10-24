package com.vitallink.api.jobs;

import com.vitallink.api.core.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** For the scheduler only (GitHub Actions cron). Not under /v1: no user token, a shared secret instead. */
@RestController
@Tag(name = "ops")
public class JobsController {

    private final NightlyJob nightly;
    private final String secret;
    private final ReentrantLock running = new ReentrantLock();

    public JobsController(NightlyJob nightly, @Value("${JOB_SECRET:}") String secret) {
        this.nightly = nightly;
        this.secret = secret;
    }

    @PostMapping("/internal/jobs/nightly")
    @Operation(summary = "Run the nightly job now (X-Job-Secret header). Synchronous; returns a summary.")
    public Map<String, Object> runNightly(@RequestHeader(value = "X-Job-Secret", required = false) String given) {
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
            return nightly.run();
        } finally {
            running.unlock();
        }
    }
}
