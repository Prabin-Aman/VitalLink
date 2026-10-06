from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_session
from app.modules.account.service import CurrentUser, current_user
from app.modules.health import service
from app.modules.health.schemas import (
    BloodPressureSeries,
    Dashboard,
    DeviceOut,
    IngestIn,
    IngestOut,
    Interval,
    ManualIn,
    ReadingOut,
    Series,
)

router = APIRouter(prefix="/me", tags=["health"])

Session = Annotated[AsyncSession, Depends(get_session)]
Caller = Annotated[CurrentUser, Depends(current_user)]
Days = Annotated[int, Query(ge=1, le=400)]


@router.post("/health/ingest", response_model=IngestOut, status_code=202)
async def ingest(body: IngestIn, session: Session, me: Caller) -> IngestOut:
    """Batch of 5-minute aggregates from HealthKit or Health Connect (≤ 5,000), upserted."""
    return await service.ingest(session, me, body)


@router.post("/health/manual", response_model=ReadingOut, status_code=201)
async def add_manual(body: ManualIn, session: Session, me: Caller) -> ReadingOut:
    return await service.add_manual(session, me, body)


@router.get("/health/dashboard", response_model=Dashboard)
async def dashboard(session: Session, me: Caller) -> Dashboard:
    return await service.dashboard(session, me)


# Declared before /series/{metric} so it wins the match.
@router.get("/health/series/blood_pressure", response_model=BloodPressureSeries)
async def blood_pressure(
    session: Session, me: Caller, days: Days = 7, interval: Interval = "day"
) -> BloodPressureSeries:
    return await service.blood_pressure_series(session, me.id, me.user.timezone, days, interval)


@router.get("/health/series/{metric}", response_model=Series)
async def series(
    session: Session, me: Caller, metric: str, days: Days = 7, interval: Interval = "day"
) -> Series:
    """Buckets in the user's time zone. Totals fill empty buckets with 0; averages skip them."""
    return await service.series(session, me.id, me.user.timezone, metric, days, interval)


@router.get("/health/latest", response_model=list[ReadingOut])
async def latest(
    session: Session, me: Caller, metrics: Annotated[str, Query(min_length=1, max_length=400)]
) -> list[ReadingOut]:
    """Latest reading per metric (comma-separated codes; `blood_pressure` pairs the two values)."""
    codes = [c.strip() for c in metrics.split(",") if c.strip()][:20]
    return await service.latest(session, me.id, me.user.timezone, codes)


@router.get("/devices", response_model=list[DeviceOut])
async def devices(session: Session, me: Caller) -> list[DeviceOut]:
    return await service.devices(session, me)


@router.delete("/devices/{device_id}", status_code=204)
async def forget_device(device_id: UUID, session: Session, me: Caller) -> Response:
    await service.forget_device(session, me, device_id)
    return Response(status_code=204)
