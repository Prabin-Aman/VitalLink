package com.vitallink.api.account;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.vitallink.api.core.ApiError;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks a Google ID token from the "Sign in with Google" button (Google Identity Services): signature against
 * Google's published keys, issuer, audience (our OAuth client id), expiry, and a verified email. Free: it needs
 * only an OAuth client id from Google Cloud Console (GOOGLE_CLIENT_ID).
 */
@Component
public class GoogleSignIn {

    /** What VitalLink uses from a verified Google account. */
    public record GoogleUser(String subject, String email, String firstName, String lastName) {}

    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final String clientId;
    private volatile DefaultJWTProcessor<SecurityContext> processor;

    public GoogleSignIn(@Value("${vitallink.google-client-id:}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.strip();
    }

    public boolean configured() {
        return !clientId.isEmpty();
    }

    public String clientId() {
        return clientId;
    }

    public GoogleUser verify(String idToken) {
        if (!configured()) {
            throw new ApiError(503, "google_unavailable", "Google sign-in isn't set up on this server.");
        }
        JWTClaimsSet c;
        try {
            c = processor().process(idToken, null);
        } catch (Exception e) {
            throw new ApiError(401, "invalid_google_token", "Google sign-in didn't work. Try again.");
        }
        if (!ISSUERS.contains(c.getIssuer())
                || c.getAudience() == null
                || !c.getAudience().contains(clientId)) {
            throw new ApiError(401, "invalid_google_token", "Google sign-in didn't work. Try again.");
        }
        Object verified = c.getClaim("email_verified");
        Object email = c.getClaim("email");
        if (!(email instanceof String e) || !(Boolean.TRUE.equals(verified) || "true".equals(verified))) {
            throw new ApiError(403, "email_unverified", "Your Google account's email isn't verified.");
        }
        return new GoogleUser(
                c.getSubject(),
                e.strip().toLowerCase(),
                c.getClaim("given_name") instanceof String g ? g : null,
                c.getClaim("family_name") instanceof String f ? f : null);
    }

    private DefaultJWTProcessor<SecurityContext> processor() {
        DefaultJWTProcessor<SecurityContext> p = processor;
        if (p == null) {
            try {
                JWKSource<SecurityContext> keys = JWKSourceBuilder.create(
                                URI.create("https://www.googleapis.com/oauth2/v3/certs")
                                        .toURL())
                        .build();
                p = new DefaultJWTProcessor<>();
                p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
                processor = p;
            } catch (java.net.MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
        return p;
    }
}
