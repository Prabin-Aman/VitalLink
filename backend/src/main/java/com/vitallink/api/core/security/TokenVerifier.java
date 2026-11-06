package com.vitallink.api.core.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Settings;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Who is calling: verifies the Supabase access token on every request (docs/06 §6.3).
 *
 * <p>Production: asymmetric keys from Supabase's JWKS (cached 10 min), issuer and audience checked.
 * Local and test: HS256 dev tokens signed with DEV_JWT_SECRET, so the API runs without Supabase. The two
 * paths never mix: production accepts no HS256 token, whatever its header says.
 */
@Component
public class TokenVerifier {

    public static final String DEV_ISSUER = "vitallink-dev";
    private static final Set<String> REQUIRED = Set.of("exp", "iat", "sub");
    private static final int MAX_NAME = 60;

    private final Settings settings;
    private volatile DefaultJWTProcessor<SecurityContext> production;

    public TokenVerifier(Settings settings) {
        this.settings = settings;
    }

    private static ApiError unauthorized(String message) {
        return new ApiError(401, "unauthorized", message);
    }

    /** The verified caller for an {@code Authorization} header, or a 401/403 {@link ApiError}. */
    public Claims verify(String authorization) {
        String header = authorization == null ? "" : authorization.trim();
        int space = header.indexOf(' ');
        String scheme = space < 0 ? header : header.substring(0, space);
        String token = space < 0 ? "" : header.substring(space + 1).trim();
        if (!scheme.equalsIgnoreCase("bearer") || token.isEmpty()) {
            throw unauthorized("Missing bearer token.");
        }
        JWTClaimsSet claims;
        try {
            claims = processor().process(token, null);
        } catch (BadJWTException e) {
            throw unauthorized(
                    e.getMessage() != null && e.getMessage().contains("Expired")
                            ? "Your session has expired."
                            : "Sign in again.");
        } catch (ParseException | BadJOSEException | JOSEException | IllegalArgumentException e) {
            throw unauthorized("Sign in again.");
        }
        return parse(claims);
    }

    static Claims parse(JWTClaimsSet c) {
        Object email = c.getClaim("email");
        if (!(email instanceof String e) || e.isBlank()) {
            throw new ApiError(403, "email_required", "VitalLink accounts need an email address.");
        }
        if (e.strip().length() > 254) { // longer than any deliverable address (and users_email_shape)
            throw new ApiError(403, "email_required", "VitalLink accounts need a valid email address.");
        }
        UUID subject;
        try {
            subject = UUID.fromString(c.getSubject());
        } catch (IllegalArgumentException ex) {
            throw unauthorized("Sign in again.");
        }
        Map<?, ?> meta = c.getClaim("user_metadata") instanceof Map<?, ?> m ? m : Map.of();
        Object aal = c.getClaim("aal");
        String[] names = names(meta);
        return new Claims(
                subject,
                e.strip().toLowerCase(),
                Boolean.TRUE.equals(meta.get("email_verified")),
                aal instanceof String s ? s : "aal1",
                signedInAt(c),
                names[0],
                names[1]);
    }

    /**
     * When the user last proved who they are. Supabase lists each sign-in method with its time in {@code amr};
     * a refreshed token keeps those times but gets a new {@code iat}. Without {@code amr}, {@code iat}.
     */
    static long signedInAt(JWTClaimsSet c) {
        long latest = -1;
        if (c.getClaim("amr") instanceof List<?> amr) {
            for (Object entry : amr) {
                if (entry instanceof Map<?, ?> m && m.get("timestamp") instanceof Number t) {
                    latest = Math.max(latest, t.longValue());
                }
            }
        }
        return latest >= 0 ? latest : c.getIssueTime().toInstant().getEpochSecond();
    }

    /**
     * First and last name: the sign-up form's fields, else what Google sends through Supabase ({@code given_name}
     * and {@code family_name}, or only {@code full_name} / {@code name}, split at the first space).
     */
    static String[] names(Map<?, ?> meta) {
        String first = name(meta.get("first_name"));
        String last = name(meta.get("last_name"));
        if (first != null || last != null) {
            return new String[] {first, last};
        }
        first = name(meta.get("given_name"));
        last = name(meta.get("family_name"));
        if (first != null || last != null) {
            return new String[] {first, last};
        }
        Object full = meta.get("full_name") != null ? meta.get("full_name") : meta.get("name");
        if (!(full instanceof String s) || s.isBlank()) {
            return new String[] {null, null};
        }
        String[] parts = s.strip().split("\s+", 2);
        return new String[] {name(parts[0]), parts.length > 1 ? name(parts[1]) : null};
    }

    /**
     * A sign-up name from user_metadata, which the user writes freely: stripped and cut to the 60
     * characters the profile allows, so the first request can't store what PATCH /me/account would refuse.
     */
    static String name(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.codePointCount(0, t.length()) <= MAX_NAME
                ? t
                : t.substring(0, t.offsetByCodePoints(0, MAX_NAME)).strip();
    }

    private DefaultJWTProcessor<SecurityContext> processor() {
        return settings.isDev() ? dev() : production();
    }

    private DefaultJWTProcessor<SecurityContext> dev() {
        if (settings.devJwtSecret() == null || settings.devJwtSecret().isBlank()) {
            throw unauthorized("DEV_JWT_SECRET isn't set.");
        }
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.HS256,
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                        settings.devJwtSecret().getBytes(StandardCharsets.UTF_8))));
        p.setJWTClaimsSetVerifier(claimsVerifier(DEV_ISSUER));
        return p;
    }

    private DefaultJWTProcessor<SecurityContext> production() {
        if (settings.supabaseUrl() == null || settings.supabaseUrl().isBlank()) {
            throw new ApiError(503, "auth_unavailable", "Sign-in isn't configured.");
        }
        DefaultJWTProcessor<SecurityContext> p = production;
        if (p == null) {
            String issuer = settings.supabaseUrl().replaceAll("/+$", "") + "/auth/v1";
            JWKSource<SecurityContext> keys;
            try {
                keys = JWKSourceBuilder.create(
                                URI.create(issuer + "/.well-known/jwks.json").toURL(),
                                new DefaultResourceRetriever(5_000, 5_000))
                        .cache(TimeUnit.MINUTES.toMillis(10), TimeUnit.SECONDS.toMillis(15))
                        .build();
            } catch (MalformedURLException e) {
                throw new ApiError(503, "auth_unavailable", "Sign-in isn't configured.");
            }
            p = new DefaultJWTProcessor<>();
            p.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.ES256, JWSAlgorithm.RS256), keys));
            p.setJWTClaimsSetVerifier(claimsVerifier(issuer));
            production = p;
        }
        return p;
    }

    private DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier(String issuer) {
        DefaultJWTClaimsVerifier<SecurityContext> v = new DefaultJWTClaimsVerifier<>(
                settings.jwtAudience(),
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                REQUIRED);
        v.setMaxClockSkew(0); // an expired token is expired; no grace period
        return v;
    }
}
