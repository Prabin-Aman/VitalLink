package com.vitallink.api.sharing;

import com.vitallink.api.account.AccountService;
import com.vitallink.api.account.AccountService.PersonInfo;
import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Ids;
import com.vitallink.api.core.audit.Audit;
import com.vitallink.api.core.audit.Audit.Action;
import com.vitallink.api.core.audit.Audit.Outcome;
import com.vitallink.api.core.audit.Audit.RefusalReason;
import com.vitallink.api.core.audit.Audit.Scope;
import com.vitallink.api.core.audit.AuditLog;
import com.vitallink.api.sharing.SharingDtos.AccessLogEntry;
import com.vitallink.api.sharing.SharingDtos.GranteeKind;
import com.vitallink.api.sharing.SharingDtos.Share;
import com.vitallink.api.sharing.SharingDtos.ShareIn;
import com.vitallink.api.sharing.SharingDtos.SharedWithMe;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Sharing rules: create, list and revoke shares; shares received; the owner's access log.
 *
 * <p>Every cross-user read goes through a {@code ConsentGate}, never around it.
 */
@Service
public class SharingService {

    static final Map<Scope, String> SCOPE_LABEL = Map.of(
            Scope.vitals, "Vitals",
            Scope.activity, "Activity",
            Scope.sleep, "Sleep",
            Scope.nutrition, "Nutrition",
            Scope.records, "Records");
    static final Map<GranteeKind, String> KIND_LABEL =
            Map.of(GranteeKind.doctor, "Doctor", GranteeKind.family, "Family", GranteeKind.other, "Shared with");
    static final Map<RefusalReason, String> REFUSAL_PURPOSE = Map.of(
            RefusalReason.no_grant, "No share",
            RefusalReason.email_unconfirmed, "Email not confirmed",
            RefusalReason.scope_not_granted, "Not part of the share",
            RefusalReason.revoked, "Share revoked",
            RefusalReason.not_yet_valid, "Share not started yet",
            RefusalReason.expired, "Share expired");

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final AuditLog audit;

    public SharingService(JdbcClient jdbc, AccountService accounts, AuditLog audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.audit = audit;
    }

    static OffsetDateTime now() {
        return com.vitallink.api.core.Clock.now();
    }

    /** A row of {@code consents}. */
    public record ConsentRow(
            UUID id,
            UUID grantorId,
            String granteeEmail,
            UUID granteeUserId,
            String granteeLabel,
            GranteeKind granteeKind,
            List<Scope> scopes,
            String purpose,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt,
            OffsetDateTime revokedAt) {

        public boolean isLive(OffsetDateTime now) {
            return revokedAt == null && !startsAt.isAfter(now) && (expiresAt == null || now.isBefore(expiresAt));
        }
    }

    private static final String CONSENT_COLUMNS =
            "id, grantor_id, grantee_email, grantee_user_id, grantee_label, grantee_kind, scopes::text[] AS scopes, "
                    + "purpose, starts_at, expires_at, created_at, revoked_at";

    static ConsentRow consentRow(ResultSet rs, int i) throws SQLException {
        return new ConsentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("grantor_id", UUID.class),
                rs.getString("grantee_email"),
                rs.getObject("grantee_user_id", UUID.class),
                rs.getString("grantee_label"),
                GranteeKind.valueOf(rs.getString("grantee_kind")),
                Arrays.stream((String[]) rs.getArray("scopes").getArray())
                        .map(Scope::valueOf)
                        .toList(),
                rs.getString("purpose"),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class));
    }

    static Share share(ConsentRow c, OffsetDateTime now) {
        return new Share(
                c.id(),
                c.granteeLabel(),
                c.granteeEmail(),
                c.granteeKind(),
                c.scopes(),
                c.purpose(),
                c.startsAt(),
                c.expiresAt(),
                c.isLive(now));
    }

    private void ownerAudit(CurrentUser me, Action action, UUID consentId, String requestId) {
        audit.write(new Audit.Event(me.id(), me.id(), action, null, Outcome.authorised, null, consentId, requestId));
    }

    // ---------------------------------------------------------------- owner side

    public Share createShare(CurrentUser me, ShareIn body, String requestId) {
        OffsetDateTime now = now();
        String email = body.email().toLowerCase();
        if (email.equals(me.user().email())) {
            throw new ApiError(422, "cannot_share_with_self", "You already have access to your own data.");
        }
        OffsetDateTime startsAt = body.startsAt() != null ? body.startsAt() : now;
        if (body.expiresAt() != null
                && (!body.expiresAt().isAfter(startsAt) || !body.expiresAt().isAfter(now))) {
            throw ApiError.invalid("The expiry must be in the future and after the start.");
        }
        // An expired share for this person no longer counts; close it so the new one can take its place.
        jdbc.sql("""
                        UPDATE vitallink.consents SET revoked_at = :now, revoke_reason = 'expired_cleanup'
                        WHERE grantor_id = :u AND grantee_email = :email AND revoked_at IS NULL AND expires_at <= :now
                        """)
                .param("now", now)
                .param("u", me.id())
                .param("email", email)
                .update();
        var live = jdbc.sql("""
                        SELECT id FROM vitallink.consents
                        WHERE grantor_id = :u AND grantee_email = :email AND revoked_at IS NULL
                        """)
                .param("u", me.id())
                .param("email", email)
                .query(UUID.class)
                .optional();
        if (live.isPresent()) {
            throw new ApiError(
                    409,
                    "share_exists",
                    "You're already sharing with this person. Revoke that share first.",
                    live.get().toString());
        }
        UUID id = Ids.uuid7();
        String[] scopes =
                EnumSet.copyOf(body.scopes()).stream().map(Enum::name).sorted().toArray(String[]::new);
        jdbc.sql("""
                        INSERT INTO vitallink.consents (id, grantor_id, grantee_email, grantee_label, grantee_kind,
                                                        scopes, purpose, starts_at, expires_at)
                        VALUES (:id, :u, :email, :label, CAST(:kind AS vitallink.grantee_kind),
                                CAST(:scopes AS vitallink.share_scope[]), :purpose, :starts, :expires)
                        """)
                .param("id", id)
                .param("u", me.id())
                .param("email", email)
                .param("label", body.label())
                .param("kind", body.kind().name())
                .param("scopes", scopes)
                .param("purpose", body.purpose())
                .param("starts", startsAt)
                .param("expires", body.expiresAt())
                .update();
        ConsentRow c = jdbc.sql("SELECT " + CONSENT_COLUMNS + " FROM vitallink.consents WHERE id = :id")
                .param("id", id)
                .query(SharingService::consentRow)
                .single();
        ownerAudit(me, Action.share_granted, id, requestId);
        return share(c, now);
    }

    /** Shares not revoked, newest first; {@code active} is false once one expires. */
    public List<Share> listShares(CurrentUser me) {
        OffsetDateTime now = now();
        return liveConsents(me.id()).stream().map(c -> share(c, now)).toList();
    }

    /** Not revoked (live or expired), newest first: used by the privacy centre and records overview. */
    public List<ConsentRow> liveConsents(UUID ownerId) {
        return jdbc.sql("SELECT " + CONSENT_COLUMNS + " FROM vitallink.consents WHERE grantor_id = :u "
                        + "AND revoked_at IS NULL ORDER BY created_at DESC, id DESC")
                .param("u", ownerId)
                .query(SharingService::consentRow)
                .list();
    }

    /** Takes effect on the viewer's next request; nothing is cached between requests. */
    public void revokeShare(CurrentUser me, UUID shareId, String requestId) {
        int n = jdbc.sql("""
                        UPDATE vitallink.consents SET revoked_at = now(), revoke_reason = 'owner'
                        WHERE id = :id AND grantor_id = :u AND revoked_at IS NULL
                        """).param("id", shareId).param("u", me.id()).update();
        if (n == 0) {
            throw ApiError.notFound("No such share.");
        }
        ownerAudit(me, Action.share_revoked, shareId, requestId);
    }

    record LogRow(OffsetDateTime at, UUID actor, String scope, String outcome, String reason, UUID consentId) {}

    /** Every read of my data by someone else, allowed or refused, newest first. */
    public List<AccessLogEntry> accessLog(CurrentUser me, int limit, int offset) {
        List<LogRow> rows = jdbc.sql("""
                        SELECT at, actor_user_id, scope, outcome, reason, consent_id
                        FROM vitallink.audit_log
                        WHERE subject_user_id = :me AND action = 'read' AND actor_user_id <> :me
                        ORDER BY at DESC, id DESC LIMIT :limit OFFSET :offset
                        """)
                .param("me", me.id())
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, i) -> new LogRow(
                        rs.getObject("at", OffsetDateTime.class),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("scope"),
                        rs.getString("outcome"),
                        rs.getString("reason"),
                        rs.getObject("consent_id", UUID.class)))
                .list();
        Set<UUID> consentIds =
                rows.stream().map(LogRow::consentId).filter(id -> id != null).collect(Collectors.toSet());
        Map<UUID, ConsentRow> consents = consentIds.isEmpty()
                ? Map.of()
                : jdbc
                        .sql("SELECT " + CONSENT_COLUMNS
                                + " FROM vitallink.consents WHERE id IN (:ids) AND grantor_id = :u")
                        .param("ids", List.copyOf(consentIds))
                        .param("u", me.id())
                        .query(SharingService::consentRow)
                        .list()
                        .stream()
                        .collect(Collectors.toMap(ConsentRow::id, Function.identity()));
        Map<UUID, PersonInfo> viewers =
                accounts.people(rows.stream().map(LogRow::actor).collect(Collectors.toSet()));
        OffsetDateTime now = OffsetDateTime.now();
        return rows.stream()
                .map(r -> {
                    ConsentRow consent = r.consentId() == null ? null : consents.get(r.consentId());
                    PersonInfo viewer = viewers.get(r.actor());
                    String email = viewer != null ? viewer.email() : "deleted account";
                    RefusalReason reason = r.reason() == null ? null : RefusalReason.valueOf(r.reason());
                    String actor = consent != null
                            ? consent.granteeLabel()
                            : (viewer != null && viewer.name() != null ? viewer.name() : email);
                    return new AccessLogEntry(
                            r.at(),
                            actor,
                            consent != null ? KIND_LABEL.get(consent.granteeKind()) + " · " + email : email,
                            !r.outcome().equals("blocked")
                                    ? SCOPE_LABEL.get(Scope.valueOf(r.scope()))
                                    : "None — blocked",
                            reason == null
                                    ? (consent != null && consent.purpose() != null ? consent.purpose() : "")
                                    : REFUSAL_PURPOSE.get(reason),
                            Outcome.valueOf(r.outcome()),
                            consent != null ? consent.id() : null,
                            consent != null && consent.isLive(now));
                })
                .toList();
    }

    // ---------------------------------------------------------------- viewer side

    /** Grants to me that are live now: bound to my account, or (unbound) to my confirmed email. */
    public List<ConsentRow> receivedLive(CurrentUser me) {
        if (me.user().emailConfirmedAt() == null) {
            return List.of();
        }
        OffsetDateTime now = now();
        return jdbc
                .sql("SELECT " + CONSENT_COLUMNS + " FROM vitallink.consents WHERE "
                        + "(grantee_user_id = :u OR (grantee_user_id IS NULL AND grantee_email = :email)) "
                        + "AND revoked_at IS NULL ORDER BY created_at DESC, id DESC")
                .param("u", me.id())
                .param("email", me.user().email())
                .query(SharingService::consentRow)
                .list()
                .stream()
                .filter(c -> c.isLive(now))
                .toList();
    }

    /** Live grants others gave me, so the client knows what it can open without probing. */
    public List<SharedWithMe> sharedWithMe(CurrentUser me) {
        List<ConsentRow> live = receivedLive(me);
        Map<UUID, PersonInfo> owners =
                accounts.people(live.stream().map(ConsentRow::grantorId).collect(Collectors.toSet()));
        return live.stream()
                .map(c -> {
                    PersonInfo o = owners.get(c.grantorId());
                    return new SharedWithMe(
                            c.id(), c.grantorId(), o.name() != null ? o.name() : o.email(), c.scopes(), c.expiresAt());
                })
                .toList();
    }
}
