package com.vitallink.api.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All configuration comes from environment variables (or backend/.env locally); see application.yml.
 *
 * @param env local, test or production
 * @param corsOrigin the browser origin allowed to call the API: the web portal in production; locally a
 *     comma-separated list may add the mobile app's web build (native apps don't use CORS)
 * @param supabaseUrl production: tokens are verified against this project's JWKS
 * @param jwtAudience the {@code aud} every token must carry
 * @param devJwtSecret local/test only: signs and verifies HS256 dev tokens
 */
@ConfigurationProperties("vitallink")
public record Settings(String env, String corsOrigin, String supabaseUrl, String jwtAudience, String devJwtSecret) {

    public Settings {
        env = env == null || env.isBlank() ? "local" : env;
        jwtAudience = jwtAudience == null || jwtAudience.isBlank() ? "authenticated" : jwtAudience;
        if (!env.equals("local") && !env.equals("test") && !env.equals("production")) {
            throw new IllegalArgumentException("ENV must be local, test or production, not " + env);
        }
    }

    /** Dev tokens and the demo seed exist only here. */
    public boolean isDev() {
        return env.equals("local") || env.equals("test");
    }
}
