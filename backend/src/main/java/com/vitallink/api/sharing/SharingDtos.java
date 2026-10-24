package com.vitallink.api.sharing;

import com.vitallink.api.core.audit.Audit.Outcome;
import com.vitallink.api.core.audit.Audit.Scope;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Sharing bodies. Responses match packages/api/src/types.ts (Share, SharedWithMe, AccessLogEntry). */
public final class SharingDtos {

    private SharingDtos() {}

    /** No "hospital" (consumers only) and no "app" (no third parties): decision D4. */
    public enum GranteeKind {
        doctor,
        family,
        other
    }

    public enum RevokeReason {
        owner,
        expired_cleanup,
        grantee_deleted
    }

    /**
     * Share chosen scopes with a person, read-only, from {@code startsAt} (default now) until
     * {@code expiresAt} (null: until revoked) or revocation.
     */
    public record ShareIn(
            @NotBlank @Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") @Size(max = 254)
            String email,

            @NotBlank @Size(max = 80) String label,
            @NotNull GranteeKind kind,
            @NotEmpty @Size(max = 5) List<@NotNull Scope> scopes,
            @Size(max = 200) String purpose,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt) {

        public ShareIn {
            label = label == null ? null : label.strip();
            purpose = purpose == null ? "" : purpose.strip();
        }
    }

    public record Share(
            UUID id,
            String granteeLabel,
            String granteeEmail,
            GranteeKind granteeKind,
            List<Scope> scopes,
            String purpose,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            boolean active) {}

    public record SharedWithMe(UUID id, UUID ownerId, String ownerName, List<Scope> scopes, OffsetDateTime expiresAt) {}

    public record AccessLogEntry(
            OffsetDateTime at, String actor, String actorDetail, String accessed, String purpose, Outcome status) {}
}
