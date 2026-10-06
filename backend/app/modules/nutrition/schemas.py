"""Nutrition bodies. Responses match packages/api/src/types.ts (NutritionDay, Meal)."""

from datetime import datetime
from typing import Annotated
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.modules.nutrition.models import MealSlot

Grams = Annotated[float, Field(ge=0, le=5000)]
Label = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)]


class FoodOut(BaseModel):
    id: UUID
    name: str
    brand: str | None
    serving_g: float
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float
    fibre_g: float


class FoodIn(BaseModel):
    """A user's own food, per 100 g."""

    model_config = ConfigDict(extra="forbid")

    name: Label
    brand: Label | None = None
    serving_g: Annotated[float, Field(gt=0, le=5000)] = 100
    kcal: Annotated[float, Field(ge=0, le=900)]
    protein_g: Annotated[float, Field(ge=0, le=100)]
    carbs_g: Annotated[float, Field(ge=0, le=100)]
    fat_g: Annotated[float, Field(ge=0, le=100)]
    fibre_g: Annotated[float, Field(ge=0, le=100)] = 0


class ItemIn(BaseModel):
    """Either a food from the catalogue (macros computed) or a one-off item with its own macros."""

    model_config = ConfigDict(extra="forbid")

    food_id: UUID | None = None
    quantity_g: Annotated[float, Field(gt=0, le=5000)]
    label: Label | None = None
    kcal: Annotated[float, Field(ge=0, le=20000)] | None = None
    protein_g: Grams | None = None
    carbs_g: Grams | None = None
    fat_g: Grams | None = None
    fibre_g: Grams | None = None

    @model_validator(mode="after")
    def _food_or_macros(self):
        custom = (self.label, self.kcal, self.protein_g, self.carbs_g, self.fat_g)
        if self.food_id is None and any(v is None for v in custom):
            raise ValueError("give a food_id, or a label with kcal, protein_g, carbs_g and fat_g")
        if self.food_id is not None and any(v is not None for v in custom[1:]):
            raise ValueError("macros come from the food; don't send them with a food_id")
        return self


class MealIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    slot: MealSlot
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=60)] | None = None
    eaten_at: AwareDatetime
    items: Annotated[list[ItemIn], Field(min_length=1, max_length=50)]


class Meal(BaseModel):
    id: UUID
    name: str
    eaten_at: datetime
    items: list[str]
    kcal: int
    protein_g: int
    carbs_g: int
    fat_g: int


class WaterIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ml: Annotated[int, Field(ge=1, le=3000)]
    at: AwareDatetime | None = None


class WaterOut(BaseModel):
    id: UUID
    ml: int
    at: datetime


class MacroGoals(BaseModel):
    protein_g: int
    carbs_g: int
    fat_g: int
    fibre_g: int


class NutritionTargetsOut(MacroGoals):
    kcal: int
    water_ml: int
    glass_ml: int
    weekly_target_kg: float


class NutritionTargetsIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    kcal: Annotated[int, Field(ge=800, le=6000)] | None = None
    protein_g: Annotated[int, Field(ge=0, le=500)] | None = None
    carbs_g: Annotated[int, Field(ge=0, le=1000)] | None = None
    fat_g: Annotated[int, Field(ge=0, le=400)] | None = None
    fibre_g: Annotated[int, Field(ge=0, le=150)] | None = None
    water_ml: Annotated[int, Field(ge=500, le=8000)] | None = None
    glass_ml: Annotated[int, Field(ge=100, le=1000)] | None = None
    weekly_target_kg: Annotated[float, Field(ge=-1, le=1)] | None = None


class NutritionDay(BaseModel):
    date: datetime
    kcal: int
    kcal_goal: int
    protein_g: int
    carbs_g: int
    fat_g: int
    fibre_g: int
    goals: MacroGoals
    water_ml: int
    water_goal_ml: int
    glass_ml: int
    # null until height, weight, birth date and sex are known (Mifflin-St Jeor needs all four).
    base_burn_kcal: int | None
    active_burn_kcal: int
    weekly_target_kg: float
    meals: list[Meal]
