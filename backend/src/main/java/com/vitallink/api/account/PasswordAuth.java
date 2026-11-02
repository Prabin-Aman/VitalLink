package com.vitallink.api.account;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.Mailer;
import com.vitallink.api.core.Settings;
import com.vitallink.api.core.security.Claims;
import com.vitallink.api.core.security.DevTokens;
import com.vitallink.api.core.userdata.UserData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email/password accounts for local and test runs, until the group's Supabase project exists (docs/06 §6.3).
 * Sign-up creates the account and its profile; sign-in checks a BCrypt hash and returns a token shaped like
 * Supabase's, signed with DEV_JWT_SECRET, so every endpoint works unchanged. Production signs in with Supabase
 * only: these endpoints answer 404 there.
 *
 * <p>Wrong passwords are counted in their own transaction (the request's rolls back on the 401); five in a row
 * lock sign-in for 15 minutes. The answer never says whether the email exists.
 *
 * <p>Forgot password emails a single-use link (30 minutes; only its SHA-256 is stored; at most three an hour).
 * Google sign-in ({@link GoogleSignIn}) signs in the account with that verified email, or creates one, and
 * counts as email confirmation.
 */
@Service
public class PasswordAuth implements UserData {

    static final long TOKEN_TTL_SECONDS = 12 * 3600;
    static final int MAX_FAILURES = 5;
    static final long LOCK_SECONDS = 15 * 60;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

    public record SignUpIn(
            String email,
            String password,
            String firstName,
            String lastName,
            String phone,
            String zip,
            String dateOfBirth,
            Double heightCm) {}

    public record SignInIn(String email, String password) {}

    public record Session(String accessToken, OffsetDateTime expiresAt) {}

    private final JdbcClient jdbc;
    private final Settings settings;
    private final AccountService accounts;
    private final TransactionTemplate separate;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
    private final Mailer mailer;
    private final GoogleSignIn google;
    private final String webUrl;
    private final SecureRandom random = new SecureRandom();

    static final Duration RESET_TTL = Duration.ofMinutes(30);
    static final int RESETS_PER_HOUR = 3;

    public PasswordAuth(
            JdbcClient jdbc,
            Settings settings,
            AccountService accounts,
            PlatformTransactionManager transactions,
            Mailer mailer,
            GoogleSignIn google,
            @Value("${vitallink.web-url}") String webUrl) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.accounts = accounts;
        this.mailer = mailer;
        this.google = google;
        this.webUrl = webUrl.replaceAll("/+$", "");
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private void available() {
        if (!settings.isDev()) {
            throw ApiError.notFound("Sign in with your VitalLink account.");
        }
    }

    /** At least 12 characters with a number and a symbol; BCrypt reads at most 72 bytes. */
    public static String passwordProblem(String p) {
        if (p == null || p.length() < 12) return "Use at least 12 characters.";
        if (p.getBytes(StandardCharsets.UTF_8).length > 72) return "Use at most 72 characters.";
        if (!p.matches(".*\\d.*")) return "Include at least one number.";
        if (!p.matches(".*[^A-Za-z0-9].*")) return "Include at least one symbol.";
        return null;
    }

    private static String email(String raw) {
        String e = raw == null ? "" : raw.strip().toLowerCase();
        if (!EMAIL.matcher(e).matches() || e.length() > 254) {
            throw ApiError.invalid("Enter a valid email address.");
        }
        return e;
    }

    public Session signUp(SignUpIn body) {
        available();
        String email = email(body.email());
        String problem = passwordProblem(body.password());
        if (problem != null) {
            throw ApiError.invalid(problem);
        }
        String first = body.firstName() == null ? "" : body.firstName().strip();
        String last = body.lastName() == null ? "" : body.lastName().strip();
        if (first.isEmpty() || last.isEmpty() || first.length() > 60 || last.length() > 60) {
            throw ApiError.invalid("Enter your first and last name (up to 60 characters each).");
        }
        boolean taken = jdbc.sql("SELECT EXISTS (SELECT 1 FROM vitallink.users WHERE email = :e)")
                .param("e", email)
                .query(Boolean.class)
                .single();
        if (taken) {
            throw new ApiError(409, "email_in_use", "An account with this email already exists. Sign in instead.");
        }
        UUID subject = UUID.randomUUID();
        Claims claims = new Claims(subject, email, false, "aal1", Instant.now().getEpochSecond(), first, last);
        CurrentUser me = new CurrentUser(accounts.getOrCreate(claims), claims);
        // The optional details go through the account's own rules (phone shape, ZIP, height range…).
        Map<String, Object> details = new LinkedHashMap<>();
        if (body.phone() != null && !body.phone().isBlank()) details.put("phone", body.phone());
        if (body.zip() != null && !body.zip().isBlank()) details.put("zip", body.zip());
        if (body.dateOfBirth() != null && !body.dateOfBirth().isBlank())
            details.put("date_of_birth", body.dateOfBirth());
        if (body.heightCm() != null) details.put("height_cm", body.heightCm());
        if (!details.isEmpty()) {
            accounts.updateAccount(me, details);
        }
        jdbc.sql("INSERT INTO vitallink.credentials (user_id, password_hash) VALUES (:u, :h)")
                .param("u", me.id())
                .param("h", bcrypt.encode(body.password()))
                .update();
        return session(subject, email, first, last);
    }

    private record Login(UUID userId, UUID subject, String hash, int failures, OffsetDateTime lockedUntil) {}

    public Session signIn(SignInIn body) {
        available();
        String email = body.email() == null ? "" : body.email().strip().toLowerCase();
        Optional<Login> found = jdbc.sql("""
                        SELECT u.id, u.auth_subject, c.password_hash, c.failed_attempts, c.locked_until
                        FROM vitallink.users u JOIN vitallink.credentials c ON c.user_id = u.id
                        WHERE u.email = :e
                        """)
                .param("e", email)
                .query((rs, i) -> new Login(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getObject(5, OffsetDateTime.class)))
                .optional();
        ApiError wrong = new ApiError(401, "invalid_credentials", "That email and password don't match an account.");
        if (found.isEmpty()) {
            bcrypt.matches(body.password() == null ? "" : body.password(), DUMMY); // same time as a real check
            throw wrong;
        }
        Login l = found.get();
        if (l.lockedUntil() != null && l.lockedUntil().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ApiError(429, "too_many_attempts", "Too many wrong passwords. Try again in 15 minutes.");
        }
        if (body.password() == null || !bcrypt.matches(body.password(), l.hash())) {
            separate.executeWithoutResult(s -> jdbc.sql("""
                            UPDATE vitallink.credentials
                            SET failed_attempts = failed_attempts + 1,
                                locked_until = CASE WHEN failed_attempts + 1 >= :max
                                                    THEN now() + make_interval(secs => :lock) END,
                                updated_at = now()
                            WHERE user_id = :u
                            """)
                    .param("max", MAX_FAILURES)
                    .param("lock", LOCK_SECONDS)
                    .param("u", l.userId())
                    .update());
            throw wrong;
        }
        jdbc.sql("UPDATE vitallink.credentials SET failed_attempts = 0, locked_until = NULL WHERE user_id = :u")
                .param("u", l.userId())
                .update();
        Map<String, Object> names = jdbc.sql("SELECT first_name, last_name FROM vitallink.profiles WHERE user_id = :u")
                .param("u", l.userId())
                .query()
                .singleRow();
        return session(l.subject(), email, (String) names.get("first_name"), (String) names.get("last_name"));
    }

    /** Sets (or replaces) a password for an existing account: local tools and tests (e.g. the demo account). */
    public void setPassword(UUID userId, String password) {
        String problem = passwordProblem(password);
        if (problem != null) {
            throw ApiError.invalid(problem);
        }
        jdbc.sql("""
                        INSERT INTO vitallink.credentials (user_id, password_hash) VALUES (:u, :h)
                        ON CONFLICT (user_id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                          failed_attempts = 0, locked_until = NULL, updated_at = now()
                        """).param("u", userId).param("h", bcrypt.encode(password)).update();
    }

    // ---------------------------------------------------------------- forgot password

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Emails a reset link if an account has this email. Always answers the same way, so it can't be used to
     * find out who has an account.
     */
    public void forgotPassword(String rawEmail) {
        available();
        String email = rawEmail == null ? "" : rawEmail.strip().toLowerCase();
        Optional<Map<String, Object>> user =
                jdbc.sql("""
                        SELECT u.id, p.first_name FROM vitallink.users u
                        JOIN vitallink.profiles p ON p.user_id = u.id WHERE u.email = :e
                        """).param("e", email).query().listOfRows().stream().findFirst();
        if (user.isEmpty()) {
            return;
        }
        UUID id = (UUID) user.get().get("id");
        int recent = jdbc.sql("""
                        SELECT count(*) FROM vitallink.password_resets
                        WHERE user_id = :u AND created_at > now() - interval '1 hour'
                        """).param("u", id).query(Integer.class).single();
        if (recent >= RESETS_PER_HOUR) {
            return;
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("""
                        INSERT INTO vitallink.password_resets (token_hash, user_id, expires_at)
                        VALUES (:h, :u, now() + make_interval(secs => :ttl))
                        """)
                .param("h", sha256(token))
                .param("u", id)
                .param("ttl", RESET_TTL.toSeconds())
                .update();
        String first = (String) user.get().get("first_name");
        mailer.send(
                email, "Reset your VitalLink password", """
                Hi%s,

                Someone asked to reset the password for this VitalLink account. To choose a new one, open:

                %s/reset-password?token=%s

                The link works once, for 30 minutes. If you didn't ask, ignore this email: your password stays the same.

                VitalLink
                """.formatted(first == null ? "" : " " + first, webUrl, token));
    }

    /** Sets a new password from a reset link, ends any lockout and signs in. */
    public Session resetPassword(String token, String password) {
        available();
        String problem = passwordProblem(password);
        if (problem != null) {
            throw ApiError.invalid(problem);
        }
        UUID id = jdbc.sql("""
                        UPDATE vitallink.password_resets SET used_at = now()
                        WHERE token_hash = :h AND used_at IS NULL AND expires_at > now()
                        RETURNING user_id
                        """)
                .param("h", sha256(token == null ? "" : token))
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ApiError(
                        410,
                        "reset_link_expired",
                        "This reset link has expired or was already used. Ask for a new one."));
        // Any other links sent before stop working too.
        jdbc.sql("UPDATE vitallink.password_resets SET used_at = now() WHERE user_id = :u AND used_at IS NULL")
                .param("u", id)
                .update();
        setPassword(id, password);
        return sessionFor(id, false);
    }

    // ---------------------------------------------------------------- change password

    /** Settings › Security: the current password, then a new one. Old reset links stop working. */
    public void changePassword(CurrentUser me, String current, String next) {
        available();
        String hash = jdbc.sql("SELECT password_hash FROM vitallink.credentials WHERE user_id = :u")
                .param("u", me.id())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ApiError(
                        409,
                        "no_password",
                        "This account has no password yet (it signs in with Google). Use Forgot password to set one."));
        // 422, not 401: a wrong current password isn't an expired session.
        if (current == null || !bcrypt.matches(current, hash)) {
            throw new ApiError(422, "wrong_password", "Your current password isn't right.");
        }
        if (next != null && next.equals(current)) {
            throw ApiError.invalid("Choose a password different from the current one.");
        }
        setPassword(me.id(), next);
        jdbc.sql("UPDATE vitallink.password_resets SET used_at = now() WHERE user_id = :u AND used_at IS NULL")
                .param("u", me.id())
                .update();
    }

    // ---------------------------------------------------------------- Google

    private static String name(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        return t.length() <= 60 ? t : t.substring(0, 60).strip();
    }

    /** Signs in (or up) with a Google ID token; Google has verified the email, so the account counts as confirmed. */
    public Session signInWithGoogle(String idToken) {
        available();
        GoogleSignIn.GoogleUser g = google.verify(idToken);
        Optional<UUID> existing = jdbc.sql("SELECT id FROM vitallink.users WHERE email = :e")
                .param("e", g.email())
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            jdbc.sql(
                            "UPDATE vitallink.users SET email_confirmed_at = now() WHERE id = :u AND email_confirmed_at IS NULL")
                    .param("u", existing.get())
                    .update();
            return sessionFor(existing.get(), true);
        }
        UUID subject = Ids.uuid5(Ids.NAMESPACE_URL, "google:" + g.subject());
        String first = name(g.firstName());
        String last = name(g.lastName());
        Claims claims =
                new Claims(subject, g.email(), true, "aal1", Instant.now().getEpochSecond(), first, last);
        accounts.getOrCreate(claims);
        return session(subject, g.email(), first, last, true);
    }

    /** What the sign-in page can offer: email/password (local/test) and Google (when a client id is set). */
    public Map<String, Object> config() {
        return Map.of("password", settings.isDev(), "google_client_id", settings.isDev() ? google.clientId() : "");
    }

    private Session sessionFor(UUID userId, boolean emailVerified) {
        Map<String, Object> row = jdbc.sql("""
                        SELECT u.auth_subject, u.email, p.first_name, p.last_name FROM vitallink.users u
                        JOIN vitallink.profiles p ON p.user_id = u.id WHERE u.id = :u
                        """).param("u", userId).query().singleRow();
        return session(
                (UUID) row.get("auth_subject"),
                (String) row.get("email"),
                (String) row.get("first_name"),
                (String) row.get("last_name"),
                emailVerified);
    }

    private Session session(UUID subject, String email, String first, String last) {
        return session(subject, email, first, last, false);
    }

    private Session session(UUID subject, String email, String first, String last, boolean emailVerified) {
        long now = Instant.now().getEpochSecond();
        String token = DevTokens.mint(settings, subject, email, emailVerified, first, last, TOKEN_TTL_SECONDS, now);
        return new Session(token, Instant.ofEpochSecond(now + TOKEN_TTL_SECONDS).atOffset(ZoneOffset.UTC));
    }

    /** A valid hash of a random value, so an unknown email costs the same as a wrong password. */
    private static final String DUMMY =
            new BCryptPasswordEncoder(12).encode(UUID.randomUUID().toString());

    // ---------------------------------------------------------------- data rights

    /** Never a hash: only that a password exists, when it changed, and when reset links were sent. */
    @Override
    public Map<String, String> exportQueries() {
        return Map.of(
                "credentials",
                "SELECT user_id, created_at, updated_at FROM vitallink.credentials WHERE user_id = :u ORDER BY user_id",
                "password_resets",
                "SELECT user_id, created_at, expires_at, used_at FROM vitallink.password_resets WHERE user_id = :u"
                        + " ORDER BY created_at");
    }
}
