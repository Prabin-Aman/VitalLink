package com.vitallink.api.account;

import com.vitallink.api.account.AccountDtos.Account;
import com.vitallink.api.account.AccountDtos.Me;
import com.vitallink.api.account.AccountDtos.Sex;
import com.vitallink.api.account.AccountDtos.Targets;
import com.vitallink.api.account.AccountDtos.TargetsPatch;
import com.vitallink.api.account.AccountDtos.Theme;
import com.vitallink.api.account.AccountDtos.Units;
import com.vitallink.api.account.CurrentUser.User;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.Settings;
import com.vitallink.api.core.security.Claims;
import com.vitallink.api.core.security.IdentityAdmin;
import com.vitallink.api.core.web.Patch;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Account rules. Other modules use only this class from the account module (e.g. {@link #people}). */
@Service
public class AccountService {

    private static final Set<String> LANGUAGES = Set.of("en-US", "es-US");
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{6,14}$");
    private static final Pattern ZIP = Pattern.compile("^[0-9]{5}$");
    private static final Set<String> ACCOUNT_FIELDS = Set.of(
            "first_name",
            "last_name",
            "date_of_birth",
            "sex",
            "height_cm",
            "phone",
            "zip",
            "city",
            "language",
            "units",
            "theme",
            "timezone");
    /** Fields that can't be cleared once set. */
    private static final Set<String> REQUIRED_WHEN_SET = Set.of("language", "units", "theme", "timezone");

    private static final String USER_COLUMNS = "id, auth_subject, email, email_confirmed_at, timezone, created_at";

    private final JdbcClient jdbc;
    private final Settings settings;
    private final IdentityAdmin identities;

    public AccountService(JdbcClient jdbc, Settings settings, IdentityAdmin identities) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.identities = identities;
    }

    /**
     * Is the token's email confirmed? Consent rule 2 depends on it. The token's flag lives in user_metadata,
     * which a user can set themselves ({@code auth.updateUser}), so production ignores it and asks Supabase's
     * admin API, whose {@code email_confirmed_at} decides. Asked only while the account isn't confirmed (once,
     * normally). Dev tokens are signed by us, so their flag counts.
     */
    boolean emailConfirmed(Claims claims) {
        return settings.isDev() ? claims.emailVerified() : identities.emailConfirmed(claims.subject(), claims.email());
    }

    static User user(ResultSet rs, int i) throws SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getObject("auth_subject", UUID.class),
                rs.getString("email"),
                rs.getObject("email_confirmed_at", OffsetDateTime.class),
                rs.getString("timezone"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private Optional<User> bySubject(UUID subject) {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM vitallink.users WHERE auth_subject = :s")
                .param("s", subject)
                .query(AccountService::user)
                .optional();
    }

    /** Insert-if-absent: parallel first requests all succeed and create one account (docs/07 §7.1). */
    public User getOrCreate(Claims claims) {
        User user = bySubject(claims.subject()).orElse(null);
        if (user == null) {
            UUID newId;
            try {
                newId = jdbc.sql("""
                                INSERT INTO vitallink.users (id, auth_subject, email, email_confirmed_at)
                                VALUES (:id, :subject, :email, :confirmed)
                                ON CONFLICT (auth_subject) DO NOTHING RETURNING id
                                """)
                        .param("id", Ids.uuid7())
                        .param("subject", claims.subject())
                        .param("email", claims.email())
                        .param("confirmed", emailConfirmed(claims) ? com.vitallink.api.core.Clock.now() : null)
                        .query(UUID.class)
                        .optional()
                        .orElse(null);
            } catch (DuplicateKeyException e) {
                // Another account already has this email (e.g. its sign-in identity was recreated).
                throw new ApiError(409, "email_in_use", "This email belongs to another VitalLink account.");
            }
            if (newId != null) {
                jdbc.sql("""
                                INSERT INTO vitallink.profiles (user_id, first_name, last_name)
                                VALUES (:id, :first, :last) ON CONFLICT DO NOTHING
                                """)
                        .param("id", newId)
                        .param("first", claims.firstName())
                        .param("last", claims.lastName())
                        .update();
            }
            user = bySubject(claims.subject()).orElseThrow();
        }
        // Keep email and confirmation in step with Supabase (an email change is confirmed there).
        boolean changed = !user.email().equals(claims.email());
        if (changed || (user.emailConfirmedAt() == null && emailConfirmed(claims))) {
            OffsetDateTime confirmed = !changed
                    ? com.vitallink.api.core.Clock.now() // was unconfirmed and Supabase now says confirmed
                    : emailConfirmed(claims)
                            ? (user.emailConfirmedAt() != null
                                    ? user.emailConfirmedAt()
                                    : com.vitallink.api.core.Clock.now())
                            : null;
            try {
                jdbc.sql("UPDATE vitallink.users SET email = :email, email_confirmed_at = :confirmed WHERE id = :id")
                        .param("email", claims.email())
                        .param("confirmed", confirmed)
                        .param("id", user.id())
                        .update();
            } catch (DuplicateKeyException e) {
                throw new ApiError(409, "email_in_use", "This email belongs to another VitalLink account.");
            }
            user = new User(
                    user.id(), user.authSubject(), claims.email(), confirmed, user.timezone(), user.createdAt());
        }
        return user;
    }

    // ---------------------------------------------------------------- profile

    /** A row of {@code profiles}. Nullable = the user hasn't told us yet. */
    public record Profile(
            UUID userId,
            String firstName,
            String lastName,
            LocalDate dateOfBirth,
            Sex sex,
            BigDecimal heightCm,
            String phone,
            String zip,
            String city,
            String language,
            Units units,
            Theme theme,
            Targets targets) {

        public String displayName() {
            String name = Stream.of(firstName, lastName)
                    .filter(n -> n != null && !n.isEmpty())
                    .collect(Collectors.joining(" "));
            return name.isEmpty() ? null : name;
        }

        public Integer ageOn(LocalDate day) {
            return dateOfBirth == null
                    ? null
                    : (int) (java.time.temporal.ChronoUnit.DAYS.between(dateOfBirth, day) / 365);
        }
    }

    private static Profile profileRow(ResultSet rs, int i) throws SQLException {
        String sex = rs.getString("sex");
        return new Profile(
                rs.getObject("user_id", UUID.class),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getObject("date_of_birth", LocalDate.class),
                sex == null ? null : Sex.valueOf(sex),
                rs.getBigDecimal("height_cm"),
                rs.getString("phone"),
                rs.getString("zip"),
                rs.getString("city"),
                rs.getString("language"),
                Units.valueOf(rs.getString("units")),
                Theme.valueOf(rs.getString("theme")),
                new Targets(
                        rs.getInt("daily_steps_goal"),
                        rs.getInt("move_kcal_goal"),
                        rs.getInt("exercise_min_goal"),
                        rs.getInt("stand_hours_goal"),
                        rs.getInt("sleep_goal_min")));
    }

    public Profile profile(UUID userId) {
        return jdbc.sql("SELECT * FROM vitallink.profiles WHERE user_id = :id")
                .param("id", userId)
                .query(AccountService::profileRow)
                .single();
    }

    public Me me(CurrentUser me) {
        return new Me(
                me.id(),
                me.user().email(),
                profile(me.id()).displayName(),
                me.user().timezone());
    }

    public Account account(CurrentUser me) {
        Profile p = profile(me.id());
        String timezone = jdbc.sql("SELECT timezone FROM vitallink.users WHERE id = :id")
                .param("id", me.id())
                .query(String.class)
                .single();
        return new Account(
                p.firstName(),
                p.lastName(),
                me.user().email(),
                p.phone(),
                p.zip(),
                p.city(),
                p.language(),
                p.heightCm() == null ? null : p.heightCm().doubleValue(),
                me.user().createdAt(),
                p.units(),
                // Whether this session used a second factor; enrolment itself lives in Supabase Auth.
                "aal2".equals(me.claims().aal()),
                p.theme(),
                p.dateOfBirth(),
                p.sex(),
                timezone);
    }

    public Account updateAccount(CurrentUser me, Map<String, Object> body) {
        Patch patch = Patch.of(body, ACCOUNT_FIELDS);
        for (String field : REQUIRED_WHEN_SET) {
            if (patch.isNull(field)) {
                throw Patch.invalid(field, field + " can't be cleared");
            }
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        Map<String, String> casts =
                Map.of("sex", "vitallink.sex", "units", "vitallink.units", "theme", "vitallink.theme");
        for (String key : patch.keys()) {
            Object value = switch (key) {
                case "first_name", "last_name" -> patch.string(key, 1, 60, null);
                case "city" -> patch.string(key, 1, 80, null);
                case "phone" -> patch.string(key, 0, 16, PHONE);
                case "zip" -> patch.string(key, 0, 5, ZIP);
                case "height_cm" -> patch.number(key, 50, 272);
                case "sex" -> name(patch.enumValue(key, Sex.class));
                case "units" -> name(patch.enumValue(key, Units.class));
                case "theme" -> name(patch.enumValue(key, Theme.class));
                case "language" -> {
                    String lang = patch.string(key, 1, 10, null);
                    if (!LANGUAGES.contains(lang)) {
                        throw Patch.invalid(key, "must be one of " + LANGUAGES);
                    }
                    yield lang;
                }
                case "date_of_birth" -> {
                    LocalDate dob = patch.date(key);
                    if (dob != null && (dob.isBefore(LocalDate.of(1900, 1, 1)) || !dob.isBefore(LocalDate.now()))) {
                        throw Patch.invalid(key, "must be a past date after 1900");
                    }
                    yield dob;
                }
                case "timezone" -> {
                    String zone = patch.string(key, 1, 64, null);
                    if (!ZoneId.getAvailableZoneIds().contains(zone)) {
                        throw Patch.invalid(key, "unknown IANA time zone");
                    }
                    yield zone;
                }
                default -> throw new IllegalStateException(key);
            };
            changes.put(key, value);
        }
        Object timezone = changes.remove("timezone");
        if (timezone != null) {
            jdbc.sql("UPDATE vitallink.users SET timezone = :tz WHERE id = :id")
                    .param("tz", timezone)
                    .param("id", me.id())
                    .update();
        }
        if (!changes.isEmpty()) {
            // Column names come from the allow-list above, never from the request.
            String sets = changes.keySet().stream()
                    .map(k -> casts.containsKey(k) ? k + " = CAST(:" + k + " AS " + casts.get(k) + ")" : k + " = :" + k)
                    .collect(Collectors.joining(", "));
            jdbc.sql("UPDATE vitallink.profiles SET " + sets + ", updated_at = now() WHERE user_id = :id")
                    .params(changes)
                    .param("id", me.id())
                    .update();
        }
        return account(me);
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    public Targets targets(UUID userId) {
        return profile(userId).targets();
    }

    public Targets updateTargets(CurrentUser me, TargetsPatch patch) {
        Map<String, Object> changes = new LinkedHashMap<>();
        if (patch.dailyStepsGoal() != null) changes.put("daily_steps_goal", patch.dailyStepsGoal());
        if (patch.moveKcalGoal() != null) changes.put("move_kcal_goal", patch.moveKcalGoal());
        if (patch.exerciseMinGoal() != null) changes.put("exercise_min_goal", patch.exerciseMinGoal());
        if (patch.standHoursGoal() != null) changes.put("stand_hours_goal", patch.standHoursGoal());
        if (patch.sleepGoalMin() != null) changes.put("sleep_goal_min", patch.sleepGoalMin());
        if (!changes.isEmpty()) {
            String sets = changes.keySet().stream().map(k -> k + " = :" + k).collect(Collectors.joining(", "));
            jdbc.sql("UPDATE vitallink.profiles SET " + sets + ", updated_at = now() WHERE user_id = :id")
                    .params(changes)
                    .param("id", me.id())
                    .update();
        }
        return targets(me.id());
    }

    // ---------------------------------------------------------------- for other modules

    /** Name, email and time zone for users other modules need to show (owners, viewers). */
    public record PersonInfo(UUID id, String email, String name, String timezone, boolean emailConfirmed) {}

    public Map<UUID, PersonInfo> people(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        SELECT u.id, u.email, u.timezone, u.email_confirmed_at, p.first_name, p.last_name
                        FROM vitallink.users u JOIN vitallink.profiles p ON p.user_id = u.id
                        WHERE u.id IN (:ids)
                        """)
                .param("ids", List.copyOf(ids))
                .query((rs, i) -> {
                    String name = Stream.of(rs.getString("first_name"), rs.getString("last_name"))
                            .filter(n -> n != null && !n.isEmpty())
                            .collect(Collectors.joining(" "));
                    return new PersonInfo(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            name.isEmpty() ? null : name,
                            rs.getString("timezone"),
                            rs.getObject("email_confirmed_at") != null);
                })
                .list()
                .stream()
                .collect(Collectors.toMap(PersonInfo::id, p -> p));
    }

    public PersonInfo person(UUID id) {
        return people(List.of(id)).get(id);
    }
}
