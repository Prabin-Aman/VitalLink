package com.vitallink.api.core.consent;

import com.vitallink.api.core.audit.Audit.RefusalReason;
import com.vitallink.api.core.audit.Audit.Scope;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The consent engine's rules (docs/06-security-and-consent.md §6.1).
 *
 * <p>{@link #decide} is pure: no database, network or framework, so every rule is tested exhaustively.
 * {@link ConsentGate} does the I/O around it.
 */
public final class Consent {

    private Consent() {}

    public record Viewer(UUID userId, String email, boolean emailConfirmed) {}

    public record Grant(
            UUID id,
            UUID granteeUserId,
            String granteeEmail,
            Set<Scope> scopes,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            OffsetDateTime revokedAt,
            OffsetDateTime createdAt) {

        /** Expiry is exclusive: a grant expiring at 10:00 is dead at 10:00. */
        public boolean isLive(OffsetDateTime now) {
            return revokedAt == null && !startsAt.isAfter(now) && (expiresAt == null || now.isBefore(expiresAt));
        }
    }

    public record Decision(boolean allowed, RefusalReason reason, Grant grant) {

        static Decision allow(Grant grant) {
            return new Decision(true, null, grant);
        }

        static Decision refuse(RefusalReason reason, Grant grant) {
            return new Decision(false, reason, grant);
        }

        /** The first allowed read through a grant that isn't bound to an account yet. */
        public boolean needsBinding() {
            return allowed && grant != null && grant.granteeUserId() == null;
        }
    }

    private static final Comparator<Grant> NEWEST = Comparator.comparing(Grant::createdAt);

    /** May {@code viewer} read {@code scope} of {@code ownerId}'s data at {@code now}? Rules apply in order. */
    public static Decision decide(
            Viewer viewer, UUID ownerId, Scope scope, Collection<Grant> grants, OffsetDateTime now) {
        // 1. Owners read their own data.
        if (viewer.userId().equals(ownerId)) {
            return Decision.allow(null);
        }
        // 2. An unconfirmed email matches no grant: anyone could sign up as someone else's address.
        if (!viewer.emailConfirmed()) {
            return Decision.refuse(RefusalReason.email_unconfirmed, null);
        }
        // 3. Grants to this viewer: bound to their account, or (not yet bound) to their confirmed email.
        //    A grant bound to another account never matches by email again.
        List<Grant> mine = grants.stream()
                .filter(g -> viewer.userId().equals(g.granteeUserId())
                        || (g.granteeUserId() == null && Objects.equals(g.granteeEmail(), viewer.email())))
                .toList();
        if (mine.isEmpty()) {
            return Decision.refuse(RefusalReason.no_grant, null);
        }
        // 4. None of them covers this scope. Name the share that was checked, so the owner's log shows who asked.
        List<Grant> covering =
                mine.stream().filter(g -> g.scopes().contains(scope)).toList();
        if (covering.isEmpty()) {
            return Decision.refuse(
                    RefusalReason.scope_not_granted, mine.stream().max(NEWEST).orElseThrow());
        }
        // 8. Any live covering grant allows the read.
        var live = covering.stream().filter(g -> g.isLive(now)).max(NEWEST);
        if (live.isPresent()) {
            return Decision.allow(live.get());
        }
        // 5–7. Otherwise explain using the newest covering grant.
        Grant newest = covering.stream().max(NEWEST).orElseThrow();
        if (newest.revokedAt() != null) {
            return Decision.refuse(RefusalReason.revoked, newest);
        }
        if (newest.startsAt().isAfter(now)) {
            return Decision.refuse(RefusalReason.not_yet_valid, newest);
        }
        return Decision.refuse(RefusalReason.expired, newest);
    }
}
