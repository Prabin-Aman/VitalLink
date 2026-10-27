package com.vitallink.api.core.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supabase Auth's admin API (service-role key): whether an address is really confirmed, and deleting a
 * sign-in identity when an account is deleted. Without SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY (local
 * and test) there's nothing to ask and nothing to delete.
 */
@Component
public class IdentityAdmin {

    private static final Logger log = LoggerFactory.getLogger(IdentityAdmin.class);

    private final String supabaseUrl;
    private final String serviceKey;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public IdentityAdmin(
            @Value("${SUPABASE_URL:}") String supabaseUrl, @Value("${SUPABASE_SERVICE_ROLE_KEY:}") String serviceKey) {
        this.supabaseUrl = supabaseUrl.replaceAll("/+$", "");
        this.serviceKey = serviceKey;
    }

    private static final JsonMapper JSON = new JsonMapper();

    public boolean configured() {
        return !supabaseUrl.isBlank() && !serviceKey.isBlank();
    }

    /**
     * True only when Supabase's own record of the user has {@code email_confirmed_at} for this address. Fails
     * closed: not configured, unreachable or any other answer is false (the next request asks again).
     */
    public boolean emailConfirmed(UUID subject, String email) {
        if (!configured()) {
            return false;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(supabaseUrl + "/auth/v1/admin/users/" + subject))
                .timeout(Duration.ofSeconds(10))
                .header("apikey", serviceKey)
                .header("Authorization", "Bearer " + serviceKey)
                .GET()
                .build();
        try {
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Supabase user lookup for {} answered HTTP {}", subject, res.statusCode());
                return false;
            }
            JsonNode user = JSON.readTree(res.body());
            JsonNode confirmed = user.path("email_confirmed_at");
            return user.path("email").asString("").equalsIgnoreCase(email)
                    && !confirmed.isMissingNode()
                    && !confirmed.isNull()
                    && !confirmed.asString("").isBlank();
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Supabase user lookup failed for {}", subject, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /** True when the identity is gone (deleted now, or already absent). Failures are logged, not thrown. */
    public boolean deleteIdentity(UUID subject) {
        if (!configured()) {
            return true;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(supabaseUrl + "/auth/v1/admin/users/" + subject))
                .timeout(Duration.ofSeconds(10))
                .header("apikey", serviceKey)
                .header("Authorization", "Bearer " + serviceKey)
                .DELETE()
                .build();
        try {
            int status =
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status / 100 == 2 || status == 404) {
                return true;
            }
            log.error("Supabase refused to delete identity {}: HTTP {}", subject, status);
        } catch (java.io.IOException e) {
            log.error("Supabase identity delete failed for {}", subject, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
