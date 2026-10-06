"""users and profiles (docs/04-database.md §4.3). Every user-owned table cascades from users."""

import uuid
from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum

from sqlalchemy import CheckConstraint, Date, ForeignKey, Integer, Numeric, SmallInteger, Text, Uuid, func
from sqlalchemy.dialects.postgresql import TIMESTAMP
from sqlalchemy.orm import Mapped, mapped_column

from app.core.ids import uuid7
from app.core.models import Base, pg_enum


class Sex(StrEnum):
    female = "female"
    male = "male"
    other = "other"
    undisclosed = "undisclosed"


class Units(StrEnum):
    metric = "metric"
    imperial = "imperial"


class Theme(StrEnum):
    light = "light"
    dark = "dark"
    system = "system"


class User(Base):
    __tablename__ = "users"
    __table_args__ = (CheckConstraint("email = lower(email)", name="email_lowercase"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid7)
    # Supabase's `sub`. Unique, so racing first requests create one account (insert-if-absent).
    auth_subject: Mapped[uuid.UUID] = mapped_column(Uuid, unique=True)
    email: Mapped[str] = mapped_column(Text, unique=True)
    # null = not confirmed yet; shares never match an unconfirmed email (docs/06 §6.1 rule 2).
    email_confirmed_at: Mapped[datetime | None] = mapped_column(TIMESTAMP(timezone=True))
    timezone: Mapped[str] = mapped_column(Text, server_default="America/Chicago")
    created_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())


class Profile(Base):
    __tablename__ = "profiles"
    __table_args__ = (
        CheckConstraint("height_cm BETWEEN 50 AND 272", name="height_range"),
        CheckConstraint("zip ~ '^[0-9]{5}$'", name="zip_format"),
        CheckConstraint("phone ~ '^\\+[1-9][0-9]{6,14}$'", name="phone_e164"),
        CheckConstraint("date_of_birth >= '1900-01-01'", name="dob_range"),
        CheckConstraint("daily_steps_goal BETWEEN 1000 AND 100000", name="steps_goal_range"),
        CheckConstraint("move_kcal_goal BETWEEN 50 AND 5000", name="move_goal_range"),
        CheckConstraint("exercise_min_goal BETWEEN 5 AND 600", name="exercise_goal_range"),
        CheckConstraint("stand_hours_goal BETWEEN 1 AND 24", name="stand_goal_range"),
        CheckConstraint("sleep_goal_min BETWEEN 180 AND 900", name="sleep_goal_range"),
    )

    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    # Nullable = the user hasn't told us yet.
    first_name: Mapped[str | None] = mapped_column(Text)
    last_name: Mapped[str | None] = mapped_column(Text)
    date_of_birth: Mapped[date | None] = mapped_column(Date)
    sex: Mapped[Sex | None] = mapped_column(pg_enum(Sex, "sex"))
    height_cm: Mapped[Decimal | None] = mapped_column(Numeric(4, 1))
    phone: Mapped[str | None] = mapped_column(Text)  # E.164
    zip: Mapped[str | None] = mapped_column(Text)
    city: Mapped[str | None] = mapped_column(Text)
    language: Mapped[str] = mapped_column(Text, server_default="en-US")  # BCP-47
    units: Mapped[Units] = mapped_column(pg_enum(Units, "units"), server_default="imperial")
    theme: Mapped[Theme] = mapped_column(pg_enum(Theme, "theme"), server_default="system")
    # Daily targets: the one source for the dashboard, rings and goals (decision D12).
    daily_steps_goal: Mapped[int] = mapped_column(Integer, server_default="10000")
    move_kcal_goal: Mapped[int] = mapped_column(Integer, server_default="500")
    exercise_min_goal: Mapped[int] = mapped_column(SmallInteger, server_default="30")
    stand_hours_goal: Mapped[int] = mapped_column(SmallInteger, server_default="12")
    sleep_goal_min: Mapped[int] = mapped_column(SmallInteger, server_default="480")
    updated_at: Mapped[datetime] = mapped_column(
        TIMESTAMP(timezone=True), server_default=func.now(), onupdate=func.now()
    )
