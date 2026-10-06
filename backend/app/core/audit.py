"""The audit log (docs/06-security-and-consent.md §6.1). Append-only; the writer arrives with sharing.

The table is mapped here so the schema drift check covers it. No foreign keys on purpose.
"""

import uuid
from datetime import datetime
from enum import StrEnum

from sqlalchemy import BigInteger, CheckConstraint, Identity, Index, Text, Uuid, func, text
from sqlalchemy.dialects.postgresql import TIMESTAMP
from sqlalchemy.orm import Mapped, mapped_column

from app.core.models import Base, pg_enum


class Scope(StrEnum):
    vitals = "vitals"
    activity = "activity"
    sleep = "sleep"
    nutrition = "nutrition"
    records = "records"


class AuditAction(StrEnum):
    read = "read"
    share_granted = "share_granted"
    share_revoked = "share_revoked"
    share_bound = "share_bound"


class AccessOutcome(StrEnum):
    authorised = "authorised"
    limited = "limited"
    blocked = "blocked"


class RefusalReason(StrEnum):
    no_grant = "no_grant"
    scope_not_granted = "scope_not_granted"
    revoked = "revoked"
    not_yet_valid = "not_yet_valid"
    expired = "expired"
    email_unconfirmed = "email_unconfirmed"


class AuditEntry(Base):
    __tablename__ = "audit_log"
    __table_args__ = (
        CheckConstraint(
            "(outcome = 'blocked') = (reason IS NOT NULL)", name="audit_reason_only_when_blocked"
        ),
        CheckConstraint("action <> 'read' OR scope IS NOT NULL", name="audit_read_has_scope"),
        CheckConstraint("action <> 'read' OR actor_user_id IS NOT NULL", name="audit_read_has_actor"),
        Index("audit_log_subject_at", "subject_user_id", text("at DESC")),
        Index("audit_log_actor_at", "actor_user_id", text("at DESC")),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=True), primary_key=True)
    at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())
    actor_user_id: Mapped[uuid.UUID | None] = mapped_column(Uuid)  # null = the system
    subject_user_id: Mapped[uuid.UUID] = mapped_column(Uuid)
    action: Mapped[AuditAction] = mapped_column(pg_enum(AuditAction, "audit_action"))
    scope: Mapped[Scope | None] = mapped_column(pg_enum(Scope, "share_scope"))
    outcome: Mapped[AccessOutcome] = mapped_column(pg_enum(AccessOutcome, "access_outcome"))
    reason: Mapped[RefusalReason | None] = mapped_column(pg_enum(RefusalReason, "refusal_reason"))
    consent_id: Mapped[uuid.UUID | None] = mapped_column(Uuid)
    request_id: Mapped[str | None] = mapped_column(Text)
