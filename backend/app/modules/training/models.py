"""exercises, workouts, exercise_sets, training_plans, plan_sessions (docs/04-database.md §4.5)."""

import uuid
from datetime import datetime
from decimal import Decimal
from enum import StrEnum

from sqlalchemy import (
    ARRAY,
    BigInteger,
    CheckConstraint,
    ForeignKey,
    Identity,
    Index,
    Integer,
    Numeric,
    SmallInteger,
    Text,
    UniqueConstraint,
    Uuid,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import TIMESTAMP
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.ids import uuid7
from app.core.models import Base, pg_enum
from app.modules.health.models import ReadingSource


class WorkoutType(StrEnum):
    walk = "walk"
    run = "run"
    cycle = "cycle"
    strength = "strength"
    swim = "swim"
    hiit = "hiit"
    yoga = "yoga"
    other = "other"


class SessionKind(StrEnum):
    cardio = "cardio"
    strength = "strength"
    mobility = "mobility"


class MuscleGroup(StrEnum):
    chest = "chest"
    back = "back"
    shoulders = "shoulders"
    arms = "arms"
    core = "core"
    legs = "legs"
    glutes = "glutes"
    full_body = "full_body"


class PlanStatus(StrEnum):
    active = "active"
    archived = "archived"


class Exercise(Base):
    """Seeded catalogue (migration 0005)."""

    __tablename__ = "exercises"

    id: Mapped[int] = mapped_column(SmallInteger, primary_key=True, autoincrement=False)
    name: Mapped[str] = mapped_column(Text, unique=True)
    primary_muscle: Mapped[MuscleGroup] = mapped_column(pg_enum(MuscleGroup, "muscle_group"))


class TrainingPlan(Base):
    __tablename__ = "training_plans"
    __table_args__ = (
        CheckConstraint("weekly_target_sessions BETWEEN 1 AND 14", name="target_range"),
        CheckConstraint("char_length(name) BETWEEN 1 AND 80", name="name_length"),
        Index("training_plans_user_id_idx", "user_id"),
        Index(
            "training_plans_one_active", "user_id", unique=True, postgresql_where=text("status = 'active'")
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid7)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id", ondelete="CASCADE"))
    name: Mapped[str] = mapped_column(Text)
    status: Mapped[PlanStatus] = mapped_column(pg_enum(PlanStatus, "plan_status"))
    weekly_target_sessions: Mapped[int] = mapped_column(SmallInteger)
    created_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())

    sessions: Mapped[list["PlanSession"]] = relationship(
        back_populates="plan", cascade="all, delete-orphan", order_by="PlanSession.scheduled_for"
    )


class PlanSession(Base):
    __tablename__ = "plan_sessions"
    __table_args__ = (
        CheckConstraint("exercises >= 0", name="exercises_nonnegative"),
        CheckConstraint("minutes BETWEEN 1 AND 600", name="minutes_range"),
        CheckConstraint("char_length(title) BETWEEN 1 AND 80", name="title_length"),
        Index("plan_sessions_plan_scheduled_idx", "plan_id", "scheduled_for"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid7)
    plan_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("training_plans.id", ondelete="CASCADE"))
    title: Mapped[str] = mapped_column(Text)
    kind: Mapped[SessionKind] = mapped_column(pg_enum(SessionKind, "session_kind"))
    scheduled_for: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True))
    exercises: Mapped[int] = mapped_column(SmallInteger)
    minutes: Mapped[int] = mapped_column(SmallInteger)

    plan: Mapped[TrainingPlan] = relationship(back_populates="sessions")


class Workout(Base):
    __tablename__ = "workouts"
    __table_args__ = (
        CheckConstraint("duration_s BETWEEN 1 AND 86400", name="duration_range"),
        CheckConstraint("distance_m >= 0", name="distance_nonnegative"),
        CheckConstraint("kcal BETWEEN 0 AND 20000", name="kcal_range"),
        CheckConstraint("avg_hr BETWEEN 25 AND 250", name="avg_hr_range"),
        CheckConstraint("rpe BETWEEN 1 AND 10", name="rpe_range"),
        CheckConstraint(
            "zone_seconds IS NULL OR (cardinality(zone_seconds) = 5 AND 0 <= ALL (zone_seconds))",
            name="zones_shape",
        ),
        CheckConstraint("char_length(title) BETWEEN 1 AND 80", name="title_length"),
        # Synced workouts carry the platform's id (re-sync updates, never duplicates); manual ones don't.
        CheckConstraint("(source = 'manual') = (external_id IS NULL)", name="external_id_matches_source"),
        Index("workouts_user_started_idx", "user_id", text("started_at DESC")),
        Index(
            "workouts_user_source_external_key",
            "user_id",
            "source",
            "external_id",
            unique=True,
            postgresql_where=text("external_id IS NOT NULL"),
        ),
        Index("workouts_plan_session_id_idx", "plan_session_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid7)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id", ondelete="CASCADE"))
    type: Mapped[WorkoutType] = mapped_column(pg_enum(WorkoutType, "workout_type"))
    kind: Mapped[SessionKind] = mapped_column(pg_enum(SessionKind, "session_kind"))
    title: Mapped[str] = mapped_column(Text)
    started_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True))
    duration_s: Mapped[int] = mapped_column(Integer)
    distance_m: Mapped[Decimal | None] = mapped_column(Numeric(9, 1))
    kcal: Mapped[int | None] = mapped_column(Integer)
    avg_hr: Mapped[int | None] = mapped_column(SmallInteger)
    rpe: Mapped[int | None] = mapped_column(SmallInteger)
    zone_seconds: Mapped[list[int] | None] = mapped_column(ARRAY(Integer))
    focus: Mapped[str | None] = mapped_column(Text)  # mobility: body areas covered
    source: Mapped[ReadingSource] = mapped_column(pg_enum(ReadingSource, "reading_source"))
    external_id: Mapped[str | None] = mapped_column(Text)
    plan_session_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("plan_sessions.id", ondelete="SET NULL")
    )
    created_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())

    sets: Mapped[list["ExerciseSet"]] = relationship(
        back_populates="workout", cascade="all, delete-orphan", order_by="ExerciseSet.id"
    )


class ExerciseSet(Base):
    __tablename__ = "exercise_sets"
    __table_args__ = (
        UniqueConstraint(
            "workout_id", "exercise_id", "set_no", name="exercise_sets_workout_exercise_set_key"
        ),
        CheckConstraint("set_no > 0", name="set_no_positive"),
        CheckConstraint("reps BETWEEN 1 AND 1000", name="reps_range"),
        CheckConstraint("weight_kg BETWEEN 0 AND 1000", name="weight_range"),
        CheckConstraint("duration_s > 0", name="duration_positive"),
        CheckConstraint("reps IS NOT NULL OR duration_s IS NOT NULL", name="reps_or_duration"),
        Index("exercise_sets_exercise_id_idx", "exercise_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=True), primary_key=True)
    workout_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("workouts.id", ondelete="CASCADE"))
    exercise_id: Mapped[int] = mapped_column(SmallInteger, ForeignKey("exercises.id", ondelete="RESTRICT"))
    set_no: Mapped[int] = mapped_column(SmallInteger)
    reps: Mapped[int | None] = mapped_column(SmallInteger)
    weight_kg: Mapped[Decimal | None] = mapped_column(Numeric(6, 2))
    duration_s: Mapped[int | None] = mapped_column(Integer)

    workout: Mapped[Workout] = relationship(back_populates="sets")
    exercise: Mapped[Exercise] = relationship(lazy="joined")
