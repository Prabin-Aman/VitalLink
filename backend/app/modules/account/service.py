"""Account rules. Other modules import only this file (e.g. `current_user`)."""

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Annotated

from fastapi import Depends
from sqlalchemy import select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_session
from app.core.errors import ApiError
from app.core.ids import uuid7
from app.core.security import Claims, get_claims
from app.modules.account.models import Profile, User
from app.modules.account.schemas import REQUIRED_WHEN_SET, Account, AccountPatch, Me, Targets, TargetsPatch


@dataclass(frozen=True)
class CurrentUser:
    user: User
    claims: Claims

    @property
    def id(self):
        return self.user.id


async def get_or_create(session: AsyncSession, claims: Claims) -> User:
    """Insert-if-absent: parallel first requests all succeed and create one account (docs/07 §7.1)."""
    user = await session.scalar(select(User).where(User.auth_subject == claims.subject))
    if user is None:
        confirmed = datetime.now(UTC) if claims.email_verified else None
        try:
            async with session.begin_nested():
                new_id = await session.scalar(
                    insert(User)
                    .values(
                        id=uuid7(),
                        auth_subject=claims.subject,
                        email=claims.email,
                        email_confirmed_at=confirmed,
                    )
                    .on_conflict_do_nothing(index_elements=[User.auth_subject])
                    .returning(User.id)
                )
        except IntegrityError:
            # Another account already has this email (e.g. its sign-in identity was recreated).
            raise ApiError(409, "email_in_use", "This email belongs to another VitalLink account.") from None
        if new_id is not None:
            await session.execute(
                insert(Profile)
                .values(user_id=new_id, first_name=claims.first_name, last_name=claims.last_name)
                .on_conflict_do_nothing()
            )
        user = await session.scalar(select(User).where(User.auth_subject == claims.subject))
        assert user is not None
    # Keep email and confirmation in step with Supabase (an email change is confirmed there).
    if user.email != claims.email or (claims.email_verified and user.email_confirmed_at is None):
        user.email = claims.email
        user.email_confirmed_at = (
            (user.email_confirmed_at or datetime.now(UTC)) if claims.email_verified else None
        )
        await session.flush()
    return user


async def current_user(
    claims: Annotated[Claims, Depends(get_claims)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> CurrentUser:
    """FastAPI dependency for every /me route: the verified caller's account, created on first use."""
    return CurrentUser(await get_or_create(session, claims), claims)


async def _profile(session: AsyncSession, me: CurrentUser) -> Profile:
    # populate_existing: always the row as stored, including updates made earlier in this request.
    profile = await session.get(Profile, me.id, populate_existing=True)
    assert profile is not None  # created with the user
    return profile


def _display_name(profile: Profile) -> str | None:
    name = " ".join(n for n in (profile.first_name, profile.last_name) if n)
    return name or None


async def get_me(session: AsyncSession, me: CurrentUser) -> Me:
    profile = await _profile(session, me)
    return Me(id=me.id, email=me.user.email, display_name=_display_name(profile), timezone=me.user.timezone)


async def get_account(session: AsyncSession, me: CurrentUser) -> Account:
    p = await _profile(session, me)
    return Account(
        first_name=p.first_name,
        last_name=p.last_name,
        email=me.user.email,
        phone=p.phone,
        zip=p.zip,
        city=p.city,
        language=p.language,
        height_cm=float(p.height_cm) if p.height_cm is not None else None,
        member_since=me.user.created_at,
        units=p.units,
        # Whether this session used a second factor; enrolment itself lives in Supabase Auth.
        two_factor=me.claims.aal == "aal2",
        theme=p.theme,
        date_of_birth=p.date_of_birth,
        sex=p.sex,
        timezone=me.user.timezone,
    )


async def update_account(session: AsyncSession, me: CurrentUser, patch: AccountPatch) -> Account:
    changes = patch.model_dump(exclude_unset=True)
    for field in REQUIRED_WHEN_SET:
        if field in changes and changes[field] is None:
            raise ApiError(422, "invalid_request", f"{field} can't be cleared.", [{"loc": [field]}])
    timezone = changes.pop("timezone", None)
    if timezone:
        await session.execute(update(User).where(User.id == me.id).values(timezone=timezone))
        me.user.timezone = timezone
    if changes:
        await session.execute(update(Profile).where(Profile.user_id == me.id).values(**changes))
    return await get_account(session, me)


async def get_targets(session: AsyncSession, me: CurrentUser) -> Targets:
    return Targets.model_validate(await _profile(session, me), from_attributes=True)


async def update_targets(session: AsyncSession, me: CurrentUser, patch: TargetsPatch) -> Targets:
    changes = {k: v for k, v in patch.model_dump(exclude_unset=True).items() if v is not None}
    if changes:
        await session.execute(update(Profile).where(Profile.user_id == me.id).values(**changes))
    return await get_targets(session, me)
