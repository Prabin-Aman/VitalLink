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

/**
 * Deletes a sign-in identity from Supabase Auth when an account is deleted (admin API, service-role key).
 * Without SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY (local and test) there's nothing to delete.
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

    public boolean configured() {
        return !supabaseUrl.isBlank() && !serviceKey.isBlank();
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
