package com.vitallink.api.core.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.Settings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Tokens shaped like Supabase's, for local runs and tests. Refused outside local/test. */
public final class DevTokens {

    private DevTokens() {}

    /** The same email always maps to the same account (the subject is derived from the email). */
    public static UUID subjectFor(String email) {
        return Ids.uuid5(Ids.NAMESPACE_URL, "vitallink-dev:" + email.toLowerCase());
    }

    public static String mint(
            Settings settings,
            UUID subject,
            String email,
            boolean emailVerified,
            String firstName,
            String lastName,
            long ttlSeconds,
            Long issuedAt) {
        if (!settings.isDev()
                || settings.devJwtSecret() == null
                || settings.devJwtSecret().isBlank()) {
            throw new IllegalStateException("Dev tokens exist only in local and test environments.");
        }
        long now = issuedAt != null ? issuedAt : Instant.now().getEpochSecond();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("email_verified", emailVerified);
        if (firstName != null) {
            meta.put("first_name", firstName);
        }
        if (lastName != null) {
            meta.put("last_name", lastName);
        }
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject.toString())
                .claim("email", email)
                .audience(settings.jwtAudience())
                .issuer(TokenVerifier.DEV_ISSUER)
                .issueTime(new Date(now * 1000))
                .expirationTime(new Date((now + ttlSeconds) * 1000))
                .claim("aal", "aal1")
                .claim("user_metadata", meta)
                .build();
        return sign(claims, settings.devJwtSecret());
    }

    /** Signs any claims with HS256 (tests use this to build broken tokens). */
    public static String sign(JWTClaimsSet claims, String secret) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("can't sign a dev token: " + e.getMessage(), e);
        }
    }
}
