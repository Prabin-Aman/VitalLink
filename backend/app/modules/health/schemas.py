"""Health request/response bodies. Responses match packages/api/src/types.ts
(Dashboard, MetricSummary, Series, Reading, Device)."""

from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints

from app.modules.health.models import DeviceKind

Interval = Literal["hour", "day", "week", "month"]
RangeStatus = Literal["normal", "borderline", "high", "low", "no-data"]
Code = Annotated[str, StringConstraints(min_length=1, max_length=40)]


# ---------------------------------------------------------------- ingest


class DeviceIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    external_id: Annotated[str, StringConstraints(min_length=1, max_length=200)]
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=120)]
    kind: DeviceKind
    metrics: Annotated[list[Code], Field(max_length=60)] = []


class BucketIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    metric: Code
    start: AwareDatetime
    value: float
    samples: Annotated[int, Field(ge=1, le=32767)] = 1


class IngestIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    idempotency_key: UUID
    platform: Literal["healthkit", "health_connect"]
    devices: Annotated[list[DeviceIn], Field(max_length=20)] = []
    # The 5,000 cap is checked in the service so an oversized batch gets 413, not 422.
    buckets: Annotated[list[BucketIn], Field(min_length=1)]


class IngestOut(BaseModel):
    batch_id: int
    submitted: int
    inserted: int
    updated: int
    unchanged: int
    replayed: bool


class ManualIn(BaseModel):
    """One reading typed in by the user (weight, mood, a cuff's blood pressure…)."""

    model_config = ConfigDict(extra="forbid")

    metric: Code
    value: float
    at: AwareDatetime | None = None  # default: now


# ---------------------------------------------------------------- reads


class SeriesPoint(BaseModel):
    bucket: datetime
    value: float
    samples: int


class Series(BaseModel):
    metric: str
    unit: str
    interval: Interval
    points: list[SeriesPoint]


class BloodPressureSeries(BaseModel):
    systolic: Series
    diastolic: Series


class MetricSummary(BaseModel):
    metric: str
    unit: str
    latest: float | None
    latest_at: datetime | None
    avg_7d: float | None
    range: RangeStatus


class Dashboard(BaseModel):
    generated_at: datetime
    health_score: int | None
    health_score_delta_7d: int | None
    metrics: list[MetricSummary]


class ReadingOut(BaseModel):
    metric: str
    value: float
    value2: float | None = None
    unit: str
    at: datetime | None
    assessment: str


class DeviceOut(BaseModel):
    id: UUID
    name: str
    kind: DeviceKind
    icon: Literal["devices", "monitor", "droplet", "scale", "health"]
    last_sync_at: datetime
    status: Literal["connected", "overdue"]
    data: list[str]
    short_data: list[str] | None = None
