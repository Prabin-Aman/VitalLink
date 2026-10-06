"""Sharing rules: create, list and revoke shares; shares received; the owner's access log.

Every cross-user read goes through `shared_access` (a ConsentGate per request), never around it.
"""

import uuid
from datetime import UTC, datetime
from typing import Annotated

from fastapi import Depends, Path, Request
from sqlalchemy import or_, select, text, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.audit import AccessOutcome, AuditAction, AuditEvent, RefusalReason, Scope, write_audit
from app.core.consent import ConsentGate, Viewer
from app.core.db import get_session
from app.core.errors import ApiError
from app.modules.account import service as account
from app.modules.account.service import CurrentUser, current_user
from app.modules.sharing.models import Consent, RevokeReason
from app.modules.sharing.schemas import AccessLogEntry, Share, SharedWithMe, ShareIn

SCOPE_LABEL = {
    Scope.vitals: "Vitals",
    Scope.activity: "Activity",
    Scope.sleep: "Sleep",
    Scope.nutrition: "Nutrition",
    Scope.records: "Records",
}
KIND_LABEL = {"doctor": "Doctor", "family": "Family", "other": "Shared with"}
REFUSAL_PURPOSE = {
    RefusalReason.no_grant: "No share",
    RefusalReason.email_unconfirmed: "Email not confirmed",
    RefusalReason.scope_not_granted: "Not part of the share",
    RefusalReason.revoked: "Share revoked",
    RefusalReason.not_yet_valid: "Share not started yet",
    RefusalReason.expired: "Share expired",
}


def _now() -> datetime:
    return datetime.now(UTC)


def _is_live(c: Consent, now: datetime) -> bool:
    return c.revoked_at is None and c.starts_at <= now and (c.expires_at is None or now < c.expires_at)


def _share(c: Consent, now: datetime) -> Share:
    return Share(
        id=c.id,
        grantee_label=c.grantee_label,
        grantee_email=c.grantee_email,
        grantee_kind=c.grantee_kind,
        scopes=list(c.scopes),
        purpose=c.purpose,
        starts_at=c.starts_at,
        expires_at=c.expires_at,
        active=_is_live(c, now),
    )


def _audit(me: CurrentUser, action: AuditAction, consent_id: uuid.UUID, request_id: str | None) -> AuditEvent:
    return AuditEvent(
        actor_user_id=me.id,
        subject_user_id=me.id,
        action=action,
        scope=None,
        outcome=AccessOutcome.authorised,
        consent_id=consent_id,
        request_id=request_id,
    )


# ---------------------------------------------------------------- owner side


async def create_share(
    session: AsyncSession, me: CurrentUser, body: ShareIn, request_id: str | None
) -> Share:
    now = _now()
    email = body.email.lower()
    if email == me.user.email:
        raise ApiError(422, "cannot_share_with_self", "You already have access to your own data.")
    starts_at = body.starts_at or now
    if body.expires_at is not None and (body.expires_at <= starts_at or body.expires_at <= now):
        raise ApiError(422, "invalid_request", "The expiry must be in the future and after the start.")

    # An expired share for this person no longer counts; close it so the new one can take its place.
    await session.execute(
        update(Consent)
        .where(
            Consent.grantor_id == me.id,
            Consent.grantee_email == email,
            Consent.revoked_at.is_(None),
            Consent.expires_at <= now,
        )
        .values(revoked_at=now, revoke_reason=RevokeReason.expired_cleanup)
    )
    live = await session.scalar(
        select(Consent.id).where(
            Consent.grantor_id == me.id, Consent.grantee_email == email, Consent.revoked_at.is_(None)
        )
    )
    if live is not None:
        raise ApiError(
            409,
            "share_exists",
            "You're already sharing with this person. Revoke that share first.",
            str(live),
        )
    consent = Consent(
        grantor_id=me.id,
        grantee_email=email,
        grantee_label=body.label,
        grantee_kind=body.kind,
        scopes=sorted(set(body.scopes)),
        purpose=body.purpose,
        starts_at=starts_at,
        expires_at=body.expires_at,
    )
    session.add(consent)
    await session.flush()
    await session.refresh(consent)
    await write_audit(_audit(me, AuditAction.share_granted, consent.id, request_id))
    return _share(consent, now)


async def list_shares(session: AsyncSession, me: CurrentUser) -> list[Share]:
    """Shares not revoked, newest first; `active` is false once one expires."""
    rows = await session.scalars(
        select(Consent)
        .where(Consent.grantor_id == me.id, Consent.revoked_at.is_(None))
        .order_by(Consent.created_at.desc())
    )
    now = _now()
    return [_share(c, now) for c in rows]


async def revoke_share(
    session: AsyncSession, me: CurrentUser, share_id: uuid.UUID, request_id: str | None
) -> None:
    """Takes effect on the viewer's next request; nothing is cached between requests."""
    result = await session.execute(
        update(Consent)
        .where(Consent.id == share_id, Consent.grantor_id == me.id, Consent.revoked_at.is_(None))
        .values(revoked_at=_now(), revoke_reason=RevokeReason.owner)
    )
    if result.rowcount == 0:
        raise ApiError(404, "not_found", "No such share.")
    await write_audit(_audit(me, AuditAction.share_revoked, share_id, request_id))


async def access_log(session: AsyncSession, me: CurrentUser, limit: int, offset: int) -> list[AccessLogEntry]:
    """Every read of my data by someone else, allowed or refused, newest first."""
    rows = (
        await session.execute(
            text(
                """
                SELECT at, actor_user_id, scope, outcome, reason, consent_id
                FROM vitallink.audit_log
                WHERE subject_user_id = :me AND action = 'read' AND actor_user_id <> :me
                ORDER BY at DESC LIMIT :limit OFFSET :offset
                """
            ),
            {"me": me.id, "limit": limit, "offset": offset},
        )
    ).all()
    consent_ids = {r.consent_id for r in rows if r.consent_id}
    consents = {
        c.id: c
        for c in await session.scalars(
            select(Consent).where(Consent.id.in_(consent_ids), Consent.grantor_id == me.id)
        )
    }
    viewers = await account.people(session, {r.actor_user_id for r in rows})
    out = []
    for r in rows:
        consent = consents.get(r.consent_id)
        viewer = viewers.get(r.actor_user_id)
        email = viewer.email if viewer else "deleted account"
        reason = RefusalReason(r.reason) if r.reason else None
        out.append(
            AccessLogEntry(
                at=r.at,
                actor=consent.grantee_label
                if consent
                else (viewer.name if viewer and viewer.name else email),
                actor_detail=f"{KIND_LABEL[consent.grantee_kind]} · {email}" if consent else email,
                accessed=SCOPE_LABEL[Scope(r.scope)] if r.outcome != "blocked" else "None — blocked",
                purpose=(consent.purpose if consent and consent.purpose else "")
                if reason is None
                else REFUSAL_PURPOSE[reason],
                status=r.outcome,
            )
        )
    return out


# ---------------------------------------------------------------- viewer side


def _viewer(me: CurrentUser) -> Viewer:
    return Viewer(me.id, me.user.email, me.user.email_confirmed_at is not None)


async def shared_with_me(session: AsyncSession, me: CurrentUser) -> list[SharedWithMe]:
    """Live grants others gave me, so the client knows what it can open without probing."""
    if me.user.email_confirmed_at is None:
        return []
    now = _now()
    rows = await session.scalars(
        select(Consent)
        .where(
            or_(
                Consent.grantee_user_id == me.id,
                (Consent.grantee_user_id.is_(None)) & (Consent.grantee_email == me.user.email),
            ),
            Consent.revoked_at.is_(None),
        )
        .order_by(Consent.created_at.desc())
    )
    live = [c for c in rows if _is_live(c, now)]
    owners = await account.people(session, {c.grantor_id for c in live})
    return [
        SharedWithMe(
            id=c.id,
            owner_id=c.grantor_id,
            owner_name=(owners[c.grantor_id].name or owners[c.grantor_id].email),
            scopes=list(c.scopes),
            expires_at=c.expires_at,
        )
        for c in live
    ]


async def shared_access(
    request: Request,
    user_id: Annotated[uuid.UUID, Path()],
    session: Annotated[AsyncSession, Depends(get_session)],
    me: Annotated[CurrentUser, Depends(current_user)],
) -> ConsentGate:
    """Dependency for every /users/{user_id}/… route (a guardrail test enforces it)."""
    return ConsentGate(session, _viewer(me), user_id, getattr(request.state, "request_id", None))
