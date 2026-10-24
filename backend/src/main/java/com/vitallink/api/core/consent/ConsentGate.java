package com.vitallink.api.core.consent;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.audit.Audit;
import com.vitallink.api.core.audit.Audit.Outcome;
import com.vitallink.api.core.audit.Audit.RefusalReason;
import com.vitallink.api.core.audit.Audit.Scope;
import com.vitallink.api.core.audit.AuditLog;
import com.vitallink.api.core.consent.Consent.Decision;
import com.vitallink.api.core.consent.Consent.Grant;
import com.vitallink.api.core.consent.Consent.Viewer;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks one viewer's access to one owner's data, one scope at a time, within a request. It loads the
 * owner's grants for this viewer, decides, binds a grant to the viewer on first allowed use, and writes
 * the audit entry in its own transaction before returning or failing. Allowed or refused, every decision
 * is recorded.
 *
 * <p>Every {@code /v1/users/{user_id}/…} handler takes one as a parameter (a guardrail test enforces it).
 */
public class ConsentGate {

    /** Refusals that must not reveal whether the account exists or shares anything. */
    private static final Set<RefusalReason> HIDDEN =
            EnumSet.of(RefusalReason.no_grant, RefusalReason.email_unconfirmed);

    private static final Map<RefusalReason, String> MESSAGE = Map.of(
            RefusalReason.scope_not_granted, "This share doesn't include %s data.",
            RefusalReason.revoked, "This share was revoked.",
            RefusalReason.not_yet_valid, "This share hasn't started yet.",
            RefusalReason.expired, "This share has expired.");

    private static final String GRANTS = """
            SELECT id, grantee_user_id, grantee_email, scopes::text[] AS scopes, starts_at, expires_at,
                   revoked_at, created_at
            FROM vitallink.consents
            WHERE grantor_id = :owner
              AND (grantee_user_id = :viewer OR (grantee_user_id IS NULL AND grantee_email = :email))
              -- A grant whose viewer deleted their account is dead for good: it never matches by email again,
              -- so whoever signs up with that address later learns nothing, not even that it existed.
              AND revoke_reason IS DISTINCT FROM 'grantee_deleted'
            """;

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Viewer viewer;
    private final UUID ownerId;
    private final String requestId;
    private final List<AccessListener> listeners;
    private List<Grant> grants;
    private Decision last;

    public ConsentGate(
            JdbcClient jdbc,
            AuditLog audit,
            Viewer viewer,
            UUID ownerId,
            String requestId,
            List<AccessListener> listeners) {
        this.listeners = listeners;
        this.jdbc = jdbc;
        this.audit = audit;
        this.viewer = viewer;
        this.ownerId = ownerId;
        this.requestId = requestId;
    }

    public UUID ownerId() {
        return ownerId;
    }

    private List<Grant> load() {
        if (grants == null) {
            grants = jdbc.sql(GRANTS)
                    .param("owner", ownerId)
                    .param("viewer", viewer.userId())
                    .param("email", viewer.email())
                    .query((rs, i) -> new Grant(
                            rs.getObject("id", UUID.class),
                            rs.getObject("grantee_user_id", UUID.class),
                            rs.getString("grantee_email"),
                            Arrays.stream((String[]) rs.getArray("scopes").getArray())
                                    .map(Scope::valueOf)
                                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(Scope.class))),
                            rs.getObject("starts_at", OffsetDateTime.class),
                            rs.getObject("expires_at", OffsetDateTime.class),
                            rs.getObject("revoked_at", OffsetDateTime.class),
                            rs.getObject("created_at", OffsetDateTime.class)))
                    .list();
        }
        return grants;
    }

    /** Decide and audit without failing (for responses that drop what isn't shared). */
    public boolean allows(Scope scope) {
        if (viewer.userId().equals(ownerId)) {
            return true;
        }
        Decision decision = Consent.decide(viewer, ownerId, scope, load(), com.vitallink.api.core.Clock.now());
        if (decision.needsBinding()) {
            jdbc.sql("UPDATE vitallink.consents SET grantee_user_id = :viewer, bound_at = now() "
                            + "WHERE id = :id AND grantee_user_id IS NULL")
                    .param("viewer", viewer.userId())
                    .param("id", decision.grant().id())
                    .update();
            grants = null;
            audit.write(new Audit.Event(
                    viewer.userId(),
                    ownerId,
                    Audit.Action.share_bound,
                    null,
                    Outcome.authorised,
                    null,
                    decision.grant().id(),
                    requestId));
        }
        audit.write(new Audit.Event(
                viewer.userId(),
                ownerId,
                Audit.Action.read,
                scope,
                decision.allowed() ? Outcome.authorised : Outcome.blocked,
                decision.reason(),
                decision.grant() == null ? null : decision.grant().id(),
                requestId));
        last = decision;
        if (decision.allowed() && decision.grant() != null) {
            listeners.forEach(l ->
                    l.allowed(ownerId, viewer.userId(), scope, decision.grant().id()));
        }
        return decision.allowed();
    }

    /** Decide and audit; fail with 404 (existence hidden) or 403 when refused. */
    public void require(Scope scope) {
        if (allows(scope)) {
            return;
        }
        RefusalReason reason = last.reason();
        if (HIDDEN.contains(reason)) {
            throw ApiError.notFound("Not found.");
        }
        throw new ApiError(
                403, reason.name(), MESSAGE.get(reason).formatted(scope.name()), Map.of("scope", scope.name()));
    }
}
