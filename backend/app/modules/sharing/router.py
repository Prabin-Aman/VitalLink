from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.audit import Scope
from app.core.consent import ConsentGate
from app.core.db import get_session
from app.core.errors import ApiError
from app.modules.account import service as account
from app.modules.account.service import CurrentUser, current_user
from app.modules.health import service as health
from app.modules.health.catalogue import BLOOD_PRESSURE, BY_CODE
from app.modules.health.schemas import BloodPressureSeries, Dashboard, Interval, ReadingOut, Series
from app.modules.sharing import service
from app.modules.sharing.schemas import AccessLogEntry, Share, SharedWithMe, ShareIn
from app.modules.sharing.service import shared_access
from app.modules.training import service as training
from app.modules.training.schemas import Training, WorkoutOut

Session = Annotated[AsyncSession, Depends(get_session)]
Caller = Annotated[CurrentUser, Depends(current_user)]
Gate = Annotated[ConsentGate, Depends(shared_access)]
Days = Annotated[int, Query(ge=1, le=400)]


def _request_id(request: Request) -> str | None:
    return getattr(request.state, "request_id", None)


# ---------------------------------------------------------------- /me: the owner's side

me_router = APIRouter(prefix="/me", tags=["sharing"])


@me_router.post("/shares", response_model=Share, status_code=201)
async def create_share(body: ShareIn, request: Request, session: Session, me: Caller) -> Share:
    """Share chosen scopes with a person, read-only, from `starts_at` until `expires_at` or revocation."""
    return await service.create_share(session, me, body, _request_id(request))


@me_router.get("/shares", response_model=list[Share])
async def list_shares(session: Session, me: Caller) -> list[Share]:
    return await service.list_shares(session, me)


@me_router.delete("/shares/{share_id}", status_code=204)
async def revoke_share(share_id: UUID, request: Request, session: Session, me: Caller) -> Response:
    await service.revoke_share(session, me, share_id, _request_id(request))
    return Response(status_code=204)


@me_router.get("/shared-with-me", response_model=list[SharedWithMe])
async def shared_with_me(session: Session, me: Caller) -> list[SharedWithMe]:
    return await service.shared_with_me(session, me)


@me_router.get("/access-log", response_model=list[AccessLogEntry])
async def access_log(
    session: Session,
    me: Caller,
    limit: Annotated[int, Query(ge=1, le=200)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> list[AccessLogEntry]:
    return await service.access_log(session, me, limit, offset)


# ---------------------------------------------------------------- /users/{id}: shared views (read-only)

users_router = APIRouter(prefix="/users/{user_id}", tags=["shared views"])


async def _owner(session: AsyncSession, gate: ConsentGate):
    # Only reached after the gate allowed a read, so the owner exists.
    return (await account.people(session, {gate.owner_id}))[gate.owner_id]


@users_router.get("/health/dashboard", response_model=Dashboard)
async def shared_dashboard(session: Session, gate: Gate) -> Dashboard:
    """Vitals only; the readiness score is left out because it also uses sleep data."""
    await gate.require(Scope.vitals)
    owner = await _owner(session, gate)
    return await health.dashboard(session, gate.owner_id, owner.timezone, 480, include_score=False)


@users_router.get("/health/series/blood_pressure", response_model=BloodPressureSeries)
async def shared_blood_pressure(
    session: Session, gate: Gate, days: Days = 7, interval: Interval = "day"
) -> BloodPressureSeries:
    await gate.require(Scope.vitals)
    owner = await _owner(session, gate)
    return await health.blood_pressure_series(session, gate.owner_id, owner.timezone, days, interval)


@users_router.get("/health/series/{metric}", response_model=Series)
async def shared_series(
    metric: str, session: Session, gate: Gate, days: Days = 7, interval: Interval = "day"
) -> Series:
    if metric not in BY_CODE:
        raise ApiError(404, "unknown_metric", f"There's no metric called {metric!r}.")
    await gate.require(BY_CODE[metric].scope)
    owner = await _owner(session, gate)
    return await health.series(session, gate.owner_id, owner.timezone, metric, days, interval)


@users_router.get("/health/latest", response_model=list[ReadingOut])
async def shared_latest(
    session: Session, gate: Gate, metrics: Annotated[str, Query(min_length=1, max_length=400)]
) -> list[ReadingOut]:
    codes = [c.strip() for c in metrics.split(",") if c.strip()][:20]
    unknown = [c for c in codes if c != BLOOD_PRESSURE and c not in BY_CODE]
    if unknown:
        raise ApiError(404, "unknown_metric", f"There's no metric called {unknown[0]!r}.")
    for scope in sorted({Scope.vitals if c == BLOOD_PRESSURE else BY_CODE[c].scope for c in codes}):
        await gate.require(scope)
    owner = await _owner(session, gate)
    return await health.latest(session, gate.owner_id, owner.timezone, codes)


@users_router.get("/workouts", response_model=list[WorkoutOut])
async def shared_workouts(session: Session, gate: Gate, days: Days = 7) -> list[WorkoutOut]:
    await gate.require(Scope.activity)
    owner = await _owner(session, gate)
    return await training.list_workouts(session, gate.owner_id, owner.timezone, days)


@users_router.get("/training", response_model=Training)
async def shared_training(session: Session, gate: Gate) -> Training:
    await gate.require(Scope.activity)
    owner = await _owner(session, gate)
    return await training.training(session, gate.owner_id, owner.timezone)
