package com.vitallink.api.jobs;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Settings;
import org.junit.jupiter.api.Test;

/** Review finding R3: in production a short JOB_SECRET leaves the endpoint shut instead of guessable. */
class JobSecretTest {

    static final Settings PRODUCTION =
            new Settings("production", "https://app.example.com", "https://p.supabase.co", null, null);
    static final Settings LOCAL = new Settings("local", "http://localhost:5173", null, null, "secret");

    static void refusedWith(JobsController jobs, String given, int status) {
        assertThatThrownBy(() -> jobs.runNightly(given))
                .isInstanceOfSatisfying(
                        ApiError.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.status())
                                .isEqualTo(status));
    }

    @Test
    void productionNeedsALongSecret() {
        refusedWith(new JobsController(null, PRODUCTION, "change-me"), "change-me", 503);
        refusedWith(new JobsController(null, PRODUCTION, "x".repeat(31)), "x".repeat(31), 503);
        // Long enough: the secret is checked (and a wrong one refused) as usual.
        refusedWith(new JobsController(null, PRODUCTION, "x".repeat(32)), "y".repeat(32), 401);
    }

    @Test
    void localRunsKeepShortSecrets() {
        refusedWith(new JobsController(null, LOCAL, "change-me"), "guess", 401);
    }
}
