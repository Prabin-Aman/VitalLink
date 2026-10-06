"""Request and response bodies. Responses match packages/api/src/types.ts (Me, AccountProfile).

Input models forbid unknown fields, so a client can never set id, email or member_since (mass
assignment). Email changes go through Supabase Auth, not this API.
"""

from datetime import date, datetime
from functools import cache
from typing import Annotated, Literal
from uuid import UUID
from zoneinfo import available_timezones

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, StringConstraints, field_validator

from app.modules.account.models import Sex, Theme, Units

LANGUAGES = ("en-US", "es-US")

Name = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=60)]


@cache
def _zones() -> frozenset[str]:
    return frozenset(available_timezones())  # from the tzdata package, so it works on Windows and slim images


def _past(value: date) -> date:
    if not date(1900, 1, 1) <= value < date.today():
        raise ValueError("must be a past date after 1900")
    return value


class Me(BaseModel):
    id: UUID
    email: str
    display_name: str | None
    timezone: str


class Account(BaseModel):
    first_name: str | None
    last_name: str | None
    email: str
    phone: str | None
    zip: str | None
    city: str | None
    language: str
    height_cm: float | None
    member_since: datetime
    units: Units
    two_factor: bool
    theme: Theme
    date_of_birth: date | None
    sex: Sex | None
    timezone: str


class AccountPatch(BaseModel):
    """Every field optional; `null` clears an optional field."""

    model_config = ConfigDict(extra="forbid")

    first_name: Name | None = None
    last_name: Name | None = None
    date_of_birth: Annotated[date, AfterValidator(_past)] | None = None
    sex: Sex | None = None
    height_cm: Annotated[float, Field(ge=50, le=272)] | None = None
    phone: Annotated[str, StringConstraints(pattern=r"^\+[1-9][0-9]{6,14}$")] | None = None
    zip: Annotated[str, StringConstraints(pattern=r"^[0-9]{5}$")] | None = None
    city: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)] | None = None
    language: Literal[LANGUAGES] | None = None
    units: Units | None = None
    theme: Theme | None = None
    timezone: str | None = None

    @field_validator("timezone")
    @classmethod
    def _known_zone(cls, value: str | None) -> str | None:
        if value is not None and value not in _zones():
            raise ValueError("unknown IANA time zone")
        return value


# Fields that can't be cleared once set.
REQUIRED_WHEN_SET = ("language", "units", "theme", "timezone")


class Targets(BaseModel):
    daily_steps_goal: int
    move_kcal_goal: int
    exercise_min_goal: int
    stand_hours_goal: int
    sleep_goal_min: int


class TargetsPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")

    daily_steps_goal: Annotated[int, Field(ge=1000, le=100000)] | None = None
    move_kcal_goal: Annotated[int, Field(ge=50, le=5000)] | None = None
    exercise_min_goal: Annotated[int, Field(ge=5, le=600)] | None = None
    stand_hours_goal: Annotated[int, Field(ge=1, le=24)] | None = None
    sleep_goal_min: Annotated[int, Field(ge=180, le=900)] | None = None
