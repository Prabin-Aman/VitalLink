"""Training bodies. Responses match packages/api/src/types.ts (Workout, Training and its parts)."""

from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.modules.training.models import SessionKind, WorkoutType

Title = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)]


class SetIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    exercise: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)]
    reps: Annotated[int, Field(ge=1, le=1000)] | None = None
    weight_kg: Annotated[float, Field(ge=0, le=1000)] | None = None
    duration_s: Annotated[int, Field(ge=1, le=86400)] | None = None

    @model_validator(mode="after")
    def _reps_or_time(self):
        if self.reps is None and self.duration_s is None:
            raise ValueError("a set needs reps or a duration")
        return self


class WorkoutIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: WorkoutType
    kind: SessionKind | None = None  # default from type
    title: Title
    started_at: AwareDatetime
    duration_s: Annotated[int, Field(ge=1, le=86400)]
    distance_m: Annotated[float, Field(ge=0, le=1_000_000)] | None = None
    kcal: Annotated[int, Field(ge=0, le=20000)] | None = None
    avg_hr: Annotated[int, Field(ge=25, le=250)] | None = None
    rpe: Annotated[int, Field(ge=1, le=10)] | None = None
    zone_seconds: Annotated[list[Annotated[int, Field(ge=0)]], Field(min_length=5, max_length=5)] | None = (
        None
    )
    focus: Annotated[str, StringConstraints(strip_whitespace=True, max_length=200)] | None = None
    # Synced workouts: platform + its id, so a re-sync updates instead of duplicating.
    source: Literal["manual", "healthkit", "health_connect"] = "manual"
    external_id: Annotated[str, StringConstraints(min_length=1, max_length=200)] | None = None
    plan_session_id: UUID | None = None
    sets: Annotated[list[SetIn], Field(max_length=200)] = []

    @model_validator(mode="after")
    def _source_and_id(self):
        if (self.source == "manual") != (self.external_id is None):
            raise ValueError("synced workouts need an external_id; manual ones must not have one")
        return self


class WorkoutOut(BaseModel):
    """`Workout` in types.ts (dashboard and Activity lists)."""

    id: UUID
    type: WorkoutType
    title: str
    started_at: datetime
    duration_s: int
    distance_m: float | None
    kcal: int | None
    avg_hr: int | None


class SetOut(BaseModel):
    exercise: str
    set_no: int
    reps: int | None
    weight_kg: float | None
    duration_s: int | None


class WorkoutDetail(WorkoutOut):
    kind: SessionKind
    rpe: int | None
    zone_seconds: list[int] | None
    focus: str | None
    source: str
    sets: list[SetOut]


class PR(BaseModel):
    exercise: str
    weight_kg: float


class TrainingSession(BaseModel):
    id: UUID
    type: WorkoutType
    kind: SessionKind
    title: str
    started_at: datetime
    duration_s: int
    exercises: int | None
    sets: int | None
    volume_kg: float | None
    distance_m: float | None
    pace_s_per_km: int | None
    kcal: int | None
    avg_hr: int | None
    rpe: int | None
    zone: int
    focus: str | None
    pr: PR | None


class TrainingWeek(BaseModel):
    sessions: int
    target_sessions: int
    minutes: int
    volume_kg: float
    active_kcal: int
    streak_days: int
    longest_streak_days: int
    source: str
    load: list[dict[SessionKind, int]]


class MuscleGroupSets(BaseModel):
    group: str
    sets: int


class Muscles(BaseModel):
    days: int
    groups: list[MuscleGroupSets]
    note: str


class PersonalRecord(BaseModel):
    exercise: str
    achieved_at: datetime
    weight_kg: float | None
    reps: int | None
    time_s: int | None


class PlannedSession(BaseModel):
    id: UUID
    title: str
    kind: str
    starts_at: datetime
    exercises: int
    minutes: int


class Training(BaseModel):
    total_sessions: int
    week: TrainingWeek
    sessions: list[TrainingSession]
    muscles: Muscles
    records: list[PersonalRecord]
    next: PlannedSession | None


class PlanSessionIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: Title
    kind: SessionKind
    scheduled_for: AwareDatetime
    exercises: Annotated[int, Field(ge=0, le=50)] = 0
    minutes: Annotated[int, Field(ge=1, le=600)]


class PlanIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: Title
    weekly_target_sessions: Annotated[int, Field(ge=1, le=14)]
    sessions: Annotated[list[PlanSessionIn], Field(max_length=100)] = []


class PlanOut(BaseModel):
    id: UUID
    name: str
    status: str
    weekly_target_sessions: int
    sessions: list[PlannedSession]


class ExerciseOut(BaseModel):
    id: int
    name: str
    primary_muscle: str
