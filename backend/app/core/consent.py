"""The consent engine (docs/06-security-and-consent.md §6.1).

`decide()` is pure: no database, network or framework, so every rule is tested exhaustively.
`ConsentGate` does the I/O around it. It loads the owner's grants for this viewer, decides, binds a
grant to the viewer on first allowed use, and writes the audit entry in its own transaction before
returning or raising. Allowed or refused, every decision is recorded.
"""

import logging
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.audit import AccessOutcome, AuditAction, AuditEvent, RefusalReason, Scope, write_audit
from app.core.errors import ApiError

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Viewer:
    user_id: uuid.UUID
    email: str
    email_confirmed: bool


@dataclass(frozen=True)
class Grant:
    id: uuid.UUID
    grantee_user_id: uuid.UUID | None
    grantee_email: str
    scopes: frozenset[Scope]
    starts_at: datetime
    expires_at: datetime | None
    revoked_at: datetime | None
    created_at: datetime

    def is_live(self, now: datetime) -> bool:
        """Expiry is exclusive: a grant expiring at 10:00 is dead at 10:00."""
        return (
            self.revoked_at is None
            and self.starts_at <= now
            and (self.expires_at is None or now < self.expires_at)
        )


@dataclass(frozen=True)
class Decision:
    allowed: bool
    reason: RefusalReason | None = None
    grant: Grant | None = None

    @property
    def needs_binding(self) -> bool:
        return self.allowed and self.grant is not None and self.grant.grantee_user_id is None


def decide(
    viewer: Viewer, owner_id: uuid.UUID, scope: Scope, grants: Sequence[Grant], now: datetime
) -> Decision:
    """May `viewer` read `scope` of `owner_id`'s data at `now`? Rules apply in order."""
    # 1. Owners read their own data.
    if viewer.user_id == owner_id:
        return Decision(True)
    # 2. An unconfirmed email matches no grant: anyone could sign up as someone else's address.
    if not viewer.email_confirmed:
        return Decision(False, RefusalReason.email_unconfirmed)
    # 3. Grants to this viewer: bound to their account, or (not yet bound) to their confirmed email.
    #    A grant bound to another account never matches by email again.
    mine = [
        g
        for g in grants
        if g.grantee_user_id == viewer.user_id
        or (g.grantee_user_id is None and g.grantee_email == viewer.email)
    ]
    if not mine:
        return Decision(False, RefusalReason.no_grant)
    # 4. None of them covers this scope.
    covering = [g for g in mine if scope in g.scopes]
    if not covering:
        # Name the share that was checked, so the owner's log shows who asked.
        return Decision(False, RefusalReason.scope_not_granted, max(mine, key=lambda g: g.created_at))
    # 8. Any live covering grant allows the read.
    live = [g for g in covering if g.is_live(now)]
    if live:
        return Decision(True, grant=max(live, key=lambda g: g.created_at))
    # 5–7. Otherwise explain using the newest covering grant.
    newest = max(covering, key=lambda g: g.created_at)
    if newest.revoked_at is not None:
        return Decision(False, RefusalReason.revoked, newest)
    if newest.starts_at > now:
        return Decision(False, RefusalReason.not_yet_valid, newest)
    return Decision(False, RefusalReason.expired, newest)


# Refusals that must not reveal whether the account exists or shares anything.
HIDDEN = {RefusalReason.no_grant, RefusalReason.email_unconfirmed}

REFUSAL_MESSAGE = {
    RefusalReason.scope_not_granted: "This share doesn't include {scope} data.",
    RefusalReason.revoked: "This share was revoked.",
    RefusalReason.not_yet_valid: "This share hasn't started yet.",
    RefusalReason.expired: "This share has expired.",
}

_GRANTS = text(
    """
    SELECT id, grantee_user_id, grantee_email, scopes::text[] AS scopes, starts_at, expires_at, revoked_at,
           created_at
    FROM vitallink.consents
    WHERE grantor_id = :owner
      AND (grantee_user_id = :viewer OR (grantee_user_id IS NULL AND grantee_email = :email))
    """
)


class ConsentGate:
    """Checks one viewer's access to one owner's data, one scope at a time, within a request."""

    def __init__(self, session: AsyncSession, viewer: Viewer, owner_id: uuid.UUID, request_id: str | None):
        self.session, self.viewer, self.owner_id, self.request_id = session, viewer, owner_id, request_id
        self._grants: list[Grant] | None = None
        self._last: Decision | None = None

    async def _load(self) -> list[Grant]:
        if self._grants is None:
            rows = await self.session.execute(
                _GRANTS, {"owner": self.owner_id, "viewer": self.viewer.user_id, "email": self.viewer.email}
            )
            self._grants = [
                Grant(
                    id=r.id,
                    grantee_user_id=r.grantee_user_id,
                    grantee_email=r.grantee_email,
                    scopes=frozenset(Scope(s) for s in r.scopes),
                    starts_at=r.starts_at,
                    expires_at=r.expires_at,
                    revoked_at=r.revoked_at,
                    created_at=r.created_at,
                )
                for r in rows
            ]
        return self._grants

    async def allows(self, scope: Scope) -> bool:
        """Decide and audit without raising (for responses that drop what isn't shared)."""
        if self.viewer.user_id == self.owner_id:
            return True
        decision = decide(self.viewer, self.owner_id, scope, await self._load(), datetime.now(UTC))
        if decision.needs_binding:
            await self.session.execute(
                text(
                    "UPDATE vitallink.consents SET grantee_user_id = :viewer, bound_at = now() "
                    "WHERE id = :id AND grantee_user_id IS NULL"
                ),
                {"viewer": self.viewer.user_id, "id": decision.grant.id},
            )
            self._grants = None
            await write_audit(
                AuditEvent(
                    actor_user_id=self.viewer.user_id,
                    subject_user_id=self.owner_id,
                    action=AuditAction.share_bound,
                    scope=None,
                    outcome=AccessOutcome.authorised,
                    consent_id=decision.grant.id,
                    request_id=self.request_id,
                )
            )
        await write_audit(
            AuditEvent(
                actor_user_id=self.viewer.user_id,
                subject_user_id=self.owner_id,
                action=AuditAction.read,
                scope=scope,
                outcome=AccessOutcome.authorised if decision.allowed else AccessOutcome.blocked,
                reason=decision.reason,
                consent_id=decision.grant.id if decision.grant else None,
                request_id=self.request_id,
            )
        )
        self._last = decision
        return decision.allowed

    async def require(self, scope: Scope) -> None:
        """Decide and audit; raise 404 (existence hidden) or 403 when refused."""
        if await self.allows(scope):
            return
        reason = self._last.reason
        if reason in HIDDEN:
            raise ApiError(404, "not_found", "Not found.")
        raise ApiError(
            403, reason.value, REFUSAL_MESSAGE[reason].format(scope=scope.value), {"scope": scope.value}
        )
