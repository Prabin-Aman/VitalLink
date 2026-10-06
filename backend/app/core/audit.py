"""The audit log (docs/06-security-and-consent.md §6.1): append-only, no foreign keys on purpose.

`write_audit` is the only code that inserts into it. It uses its own connection and transaction, so
a refused read (which raises and rolls back the request) still leaves its entry behind.
"""

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum

from sqlalchemy import BigInteger, CheckConstraint, Identity, Index, Text, Uuid, func, text
from sqlalchemy.dialects.postgresql import TIMESTAMP
from sqlalchemy.orm import Mapped, mapped_column

from app.core.db import get_engine
from app.core.models import Base, pg_enum

log = logging.getLogger(__name__)


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


@dataclass(frozen=True)
class AuditEvent:
    actor_user_id: uuid.UUID | None
    subject_user_id: uuid.UUID
    action: AuditAction
    scope: Scope | None
    outcome: AccessOutcome
    reason: RefusalReason | None = None
    consent_id: uuid.UUID | None = None
    request_id: str | None = None


_INSERT = text(
    """
    INSERT INTO vitallink.audit_log
      (actor_user_id, subject_user_id, action, scope, outcome, reason, consent_id, request_id)
    VALUES (:actor, :subject, CAST(:action AS vitallink.audit_action), CAST(:scope AS vitallink.share_scope),
            CAST(:outcome AS vitallink.access_outcome), CAST(:reason AS vitallink.refusal_reason),
            :consent_id, :request_id)
    """
)


async def write_audit(event: AuditEvent) -> None:
    """Commit one entry on its own connection. A failure is logged, never shown to the user: the access
    decision stands either way (docs/08 failure handling)."""
    try:
        async with get_engine().begin() as conn:
            await conn.execute(
                _INSERT,
                {
                    "actor": event.actor_user_id,
                    "subject": event.subject_user_id,
                    "action": event.action.value,
                    "scope": event.scope.value if event.scope else None,
                    "outcome": event.outcome.value,
                    "reason": event.reason.value if event.reason else None,
                    "consent_id": event.consent_id,
                    "request_id": event.request_id,
                },
            )
    except Exception:
        log.exception("audit write failed for %s on %s", event.action, event.subject_user_id)
