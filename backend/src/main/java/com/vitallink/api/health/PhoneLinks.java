package com.vitallink.api.health;

import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Clock;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.security.Claims;
import com.vitallink.api.core.userdata.UserData;
import com.vitallink.api.health.HealthDtos.PhoneCodeOut;
import com.vitallink.api.health.HealthDtos.PhoneLinkOut;
import com.vitallink.api.health.HealthDtos.PhoneOut;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Links the Android helper app (Health Connect) to an account without a password: the signed-in person asks for a
 * code (8 characters, 10 minutes, single use), types it into the app, and the app gets a token that can only upload
 * readings through {@code POST /v1/phone/ingest}. Only SHA-256 hashes are stored; removing the phone voids its token.
 */
@Service
public class PhoneLinks implements UserData {

    static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    static final int MAX_PHONES = 5;
    /** No 0/O, 1/I/L: easy to read off a screen and type on a phone. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public PhoneLinks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "ABCD-EFGH" → "ABCDEFGH"; spaces, dashes and case don't matter. */
    static String normalise(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- the signed-in person

    public PhoneCodeOut newCode(CurrentUser me) {
        requireRoom(me.id());
        // One waiting code at a time: a new one replaces the old.
        jdbc.sql("DELETE FROM vitallink.phone_links WHERE user_id = :u AND token_hash IS NULL")
                .param("u", me.id())
                .update();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        OffsetDateTime expires = Clock.now().plus(CODE_LIFETIME);
        jdbc.sql("""
                        INSERT INTO vitallink.phone_links (id, user_id, code_hash, code_expires_at)
                        VALUES (:id, :u, :h, :exp)
                        """)
                .param("id", Ids.uuid7())
                .param("u", me.id())
                .param("h", sha256(code.toString()))
                .param("exp", expires)
                .update();
        return new PhoneCodeOut(code.substring(0, 4) + "-" + code.substring(4), expires);
    }

    /**
     * For the iPhone Shortcut: a linked entry straight away, and its token to paste into the Shortcut (shown once).
     * Shortcuts can't run a code exchange, and the person is already signed in on the website.
     */
    public PhoneLinkOut newShortcutToken(CurrentUser me) {
        requireRoom(me.id());
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        jdbc.sql("""
                        INSERT INTO vitallink.phone_links (id, user_id, token_hash, code_expires_at, name, linked_at)
                        VALUES (:id, :u, :t, now(), 'iPhone (Shortcut)', now())
                        """)
                .param("id", Ids.uuid7())
                .param("u", me.id())
                .param("t", sha256(token))
                .update();
        return new PhoneLinkOut(token, me.user().email());
    }

    private void requireRoom(UUID userId) {
        int linked = jdbc.sql(
                        "SELECT count(*) FROM vitallink.phone_links WHERE user_id = :u AND token_hash IS NOT NULL")
                .param("u", userId)
                .query(Integer.class)
                .single();
        if (linked >= MAX_PHONES) {
            throw new ApiError(
                    409, "too_many_phones", "Remove a phone before linking another (at most " + MAX_PHONES + ").");
        }
    }

    public List<PhoneOut> list(UUID userId) {
        return jdbc.sql("""
                        SELECT id, name, linked_at, last_upload_at FROM vitallink.phone_links
                        WHERE user_id = :u AND token_hash IS NOT NULL ORDER BY linked_at, id
                        """)
                .param("u", userId)
                .query((rs, i) -> new PhoneOut(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("linked_at", OffsetDateTime.class),
                        rs.getObject("last_upload_at", OffsetDateTime.class)))
                .list();
    }

    /** The phone's token stops working at once, and its device card goes. Readings it uploaded stay. */
    public void remove(UUID userId, UUID id) {
        int n = jdbc.sql("DELETE FROM vitallink.phone_links WHERE id = :id AND user_id = :u AND token_hash IS NOT NULL")
                .param("id", id)
                .param("u", userId)
                .update();
        if (n == 0) {
            throw ApiError.notFound("No such phone.");
        }
        jdbc.sql(
                        "DELETE FROM vitallink.devices WHERE user_id = :u AND platform IN ('health_connect', 'healthkit') AND external_id = :id")
                .param("u", userId)
                .param("id", id.toString())
                .update();
    }

    /** Disconnect on a phone's device card also unlinks the phone (its device is named by the link id). */
    public void unlinkDevice(UUID userId, UUID deviceId) {
        jdbc.sql("""
                        DELETE FROM vitallink.phone_links p USING vitallink.devices d
                        WHERE d.id = :dev AND d.user_id = :u AND d.platform IN ('health_connect', 'healthkit')
                          AND p.user_id = :u AND p.id::text = d.external_id
                        """).param("dev", deviceId).param("u", userId).update();
    }

    // ---------------------------------------------------------------- the app

    /** Exchanges a code for an upload token. Wrong, used and expired codes all get the same answer. */
    public PhoneLinkOut link(String code, String deviceName) {
        String c = normalise(code);
        String name = deviceName == null || deviceName.isBlank() ? "Android phone" : deviceName.strip();
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        var row = jdbc.sql("""
                        UPDATE vitallink.phone_links
                        SET code_hash = NULL, token_hash = :t, name = :name, linked_at = now()
                        WHERE code_hash = :h AND code_expires_at > now()
                        RETURNING user_id
                        """)
                .param("t", sha256(token))
                .param("name", name.length() > 80 ? name.substring(0, 80) : name)
                .param("h", sha256(c))
                .query(UUID.class)
                .optional();
        UUID userId = row.orElseThrow(() -> new ApiError(
                400, "invalid_code", "That code isn't right or has expired. Get a new one on the website."));
        String email = jdbc.sql("SELECT email FROM vitallink.users WHERE id = :u")
                .param("u", userId)
                .query(String.class)
                .single();
        return new PhoneLinkOut(token, email);
    }

    /** The account and link behind an upload token; 401 when the token is unknown. */
    public PhoneCaller caller(String authorization) {
        String token = authorization != null && authorization.startsWith("Phone ")
                ? authorization.substring(6).strip()
                : "";
        if (token.isEmpty()) {
            throw new ApiError(
                    401, "unauthorized", "This phone isn't linked. Link it again with a code from the website.");
        }
        return jdbc.sql("""
                        UPDATE vitallink.phone_links SET last_upload_at = now()
                        WHERE token_hash = :h
                        RETURNING id, user_id
                        """)
                .param("h", sha256(token))
                .query((rs, i) -> new UUID[] {rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)})
                .optional()
                .flatMap(ids -> jdbc.sql("""
                                SELECT id, auth_subject, email, email_confirmed_at, timezone, created_at
                                FROM vitallink.users WHERE id = :u
                                """)
                        .param("u", ids[1])
                        .query((rs, i) -> new CurrentUser.User(
                                rs.getObject("id", UUID.class),
                                rs.getObject("auth_subject", UUID.class),
                                rs.getString("email"),
                                rs.getObject("email_confirmed_at", OffsetDateTime.class),
                                rs.getString("timezone"),
                                rs.getObject("created_at", OffsetDateTime.class)))
                        .optional()
                        .map(u -> new PhoneCaller(
                                new CurrentUser(
                                        u, new Claims(u.authSubject(), u.email(), true, "phone", 0, null, null)),
                                ids[0])))
                .orElseThrow(() -> new ApiError(
                        401, "unauthorized", "This phone isn't linked. Link it again with a code from the website."));
    }

    /** Nightly: codes nobody used. */
    public int deleteExpiredCodes() {
        return jdbc.sql("DELETE FROM vitallink.phone_links WHERE token_hash IS NULL AND code_expires_at < now()")
                .update();
    }

    @Override
    public Map<String, String> exportQueries() {
        return Map.of(
                "phone_links",
                "SELECT id, name, created_at, linked_at, last_upload_at FROM vitallink.phone_links "
                        + "WHERE user_id = :u AND token_hash IS NOT NULL ORDER BY created_at, id");
    }
}
