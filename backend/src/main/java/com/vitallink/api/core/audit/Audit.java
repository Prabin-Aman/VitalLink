package com.vitallink.api.core.audit;

import java.util.UUID;

/**
 * Closed sets shared by sharing and the audit log (docs/04-database.md §4.9). Constant names are the
 * Postgres enum values and the JSON values, so no mapping layer is needed.
 */
public final class Audit {

    private Audit() {}

    /** What a share can cover. Records never opens through any other scope. */
    public enum Scope {
        vitals,
        activity,
        sleep,
        nutrition,
        records
    }

    public enum Action {
        read,
        share_granted,
        share_revoked,
        share_bound
    }

    public enum Outcome {
        authorised,
        limited,
        blocked
    }

    public enum RefusalReason {
        no_grant,
        scope_not_granted,
        revoked,
        not_yet_valid,
        expired,
        email_unconfirmed
    }

    /**
     * One audit entry.
     *
     * @param actorUserId null = the system (nightly job)
     */
    public record Event(
            UUID actorUserId,
            UUID subjectUserId,
            Action action,
            Scope scope,
            Outcome outcome,
            RefusalReason reason,
            UUID consentId,
            String requestId) {}
}
