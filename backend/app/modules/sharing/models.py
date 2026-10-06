"""consents (docs/04-database.md §4.3). audit_log is written only through app.core.audit."""

import uuid
from datetime import datetime
from enum import StrEnum

from sqlalchemy import ARRAY, CheckConstraint, ForeignKey, Index, Text, Uuid, func, text
from sqlalchemy.dialects.postgresql import TIMESTAMP
from sqlalchemy.orm import Mapped, mapped_column

from app.core.audit import Scope
from app.core.ids import uuid7
from app.core.models import Base, pg_enum


class GranteeKind(StrEnum):
    """No "hospital" (consumers only) and no "app" (no third parties): decision D4."""

    doctor = "doctor"
    family = "family"
    other = "other"


class RevokeReason(StrEnum):
    owner = "owner"
    expired_cleanup = "expired_cleanup"
    grantee_deleted = "grantee_deleted"


class Consent(Base):
    __tablename__ = "consents"
    __table_args__ = (
        CheckConstraint("grantee_email = lower(grantee_email)", name="email_lowercase"),
        CheckConstraint("grantee_user_id IS NULL OR grantee_user_id <> grantor_id", name="not_self"),
        CheckConstraint("cardinality(scopes) > 0", name="scopes_present"),
        CheckConstraint("char_length(grantee_label) BETWEEN 1 AND 80", name="label_length"),
        CheckConstraint("char_length(purpose) <= 200", name="purpose_length"),
        CheckConstraint("expires_at IS NULL OR expires_at > starts_at", name="expiry_after_start"),
        CheckConstraint("(revoked_at IS NULL) = (revoke_reason IS NULL)", name="revocation_has_reason"),
        CheckConstraint("grantee_user_id IS NULL OR bound_at IS NOT NULL", name="binding_has_time"),
        # A grant may lose its viewer only by being revoked (viewer deleted their account).
        CheckConstraint(
            "bound_at IS NULL OR grantee_user_id IS NOT NULL OR revoked_at IS NOT NULL", name="binding_kept"
        ),
        Index("consents_grantor_id_idx", "grantor_id"),
        Index("consents_grantee_user_id_idx", "grantee_user_id"),
        Index(
            "consents_unbound_email_idx", "grantee_email", postgresql_where=text("grantee_user_id IS NULL")
        ),
        # One live share per person: re-sharing with the same email edits or replaces it.
        Index(
            "consents_one_live_per_grantee",
            "grantor_id",
            "grantee_email",
            unique=True,
            postgresql_where=text("revoked_at IS NULL"),
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid7)
    grantor_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id", ondelete="CASCADE"))
    grantee_email: Mapped[str] = mapped_column(Text)
    # RESTRICT: deleting a viewer's account must revoke and unbind their grants first.
    grantee_user_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="RESTRICT")
    )
    grantee_label: Mapped[str] = mapped_column(Text)
    grantee_kind: Mapped[GranteeKind] = mapped_column(pg_enum(GranteeKind, "grantee_kind"))
    scopes: Mapped[list[Scope]] = mapped_column(ARRAY(pg_enum(Scope, "share_scope")))
    purpose: Mapped[str] = mapped_column(Text, server_default="")
    starts_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())
    expires_at: Mapped[datetime | None] = mapped_column(TIMESTAMP(timezone=True))
    created_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())
    revoked_at: Mapped[datetime | None] = mapped_column(TIMESTAMP(timezone=True))
    revoke_reason: Mapped[RevokeReason | None] = mapped_column(pg_enum(RevokeReason, "revoke_reason"))
    bound_at: Mapped[datetime | None] = mapped_column(TIMESTAMP(timezone=True))
