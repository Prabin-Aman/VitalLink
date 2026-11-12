package com.vitallink.api.core.security;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Settings;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seals secrets kept in the database (device clouds' refresh tokens) with AES-256-GCM, and signs short-lived
 * values (OAuth state) with HMAC-SHA256. The key is CONNECTION_KEY (32 bytes, base64). Locally and in tests it
 * falls back to a key derived from DEV_JWT_SECRET; production without CONNECTION_KEY can't seal anything.
 */
@Component
public class Sealer {

    private static final int IV = 12;
    private static final int TAG_BITS = 128;

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public Sealer(Settings settings, @Value("${vitallink.connection-key:}") String connectionKey) {
        this.key = keyFrom(settings, connectionKey);
    }

    static byte[] keyFrom(Settings settings, String connectionKey) {
        if (connectionKey != null && !connectionKey.isBlank()) {
            byte[] k = Base64.getDecoder().decode(connectionKey.strip());
            if (k.length != 32) {
                throw new IllegalArgumentException("CONNECTION_KEY must be 32 bytes, base64-encoded.");
            }
            return k;
        }
        if (settings.isDev()
                && settings.devJwtSecret() != null
                && !settings.devJwtSecret().isBlank()) {
            try {
                return MessageDigest.getInstance("SHA-256")
                        .digest(("connections:" + settings.devJwtSecret()).getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }

    public boolean configured() {
        return key != null;
    }

    private byte[] key() {
        if (key == null) {
            throw new ApiError(503, "connections_unavailable", "Connecting devices isn't set up on this server.");
        }
        return key;
    }

    public byte[] seal(String plain) {
        try {
            byte[] iv = new byte[IV];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(IV + body.length).put(iv).put(body).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String open(byte[] sealed) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, sealed, 0, IV));
            return new String(c.doFinal(sealed, IV, sealed.length - IV), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A sealed value couldn't be opened (wrong CONNECTION_KEY?).", e);
        }
    }

    /** {@code value.signature}, both base64url. */
    public String sign(String value) {
        String v = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return v + "." + mac(v);
    }

    /** The signed value, or null when the signature doesn't match. */
    public String verify(String signed) {
        if (signed == null) {
            return null;
        }
        int dot = signed.indexOf('.');
        if (dot < 1) {
            return null;
        }
        String v = signed.substring(0, dot);
        byte[] want = mac(v).getBytes(StandardCharsets.US_ASCII);
        byte[] got = signed.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(want, got)) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(v), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String mac(String v) {
        try {
            // A separate key for signing, derived from the sealing key.
            byte[] macKey = MessageDigest.getInstance("SHA-256")
                    .digest(ByteBuffer.allocate(36)
                            .put("mac:".getBytes(StandardCharsets.US_ASCII))
                            .put(key())
                            .array());
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(macKey, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(m.doFinal(("state:" + v).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
