from datetime import date
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_session
from app.modules.account.service import CurrentUser, current_user
from app.modules.nutrition import service
from app.modules.nutrition.schemas import (
    FoodIn,
    FoodOut,
    Meal,
    MealIn,
    NutritionDay,
    NutritionTargetsIn,
    NutritionTargetsOut,
    WaterIn,
    WaterOut,
)

router = APIRouter(tags=["nutrition"])

Session = Annotated[AsyncSession, Depends(get_session)]
Caller = Annotated[CurrentUser, Depends(current_user)]


@router.get("/foods/search", response_model=list[FoodOut])
async def search_foods(
    session: Session, me: Caller, q: Annotated[str, Query(min_length=2, max_length=60)]
) -> list[FoodOut]:
    return await service.search_foods(session, me, q)


@router.post("/me/foods", response_model=FoodOut, status_code=201)
async def create_food(body: FoodIn, session: Session, me: Caller) -> FoodOut:
    return await service.create_food(session, me, body)


@router.post("/me/meals", response_model=Meal, status_code=201)
async def log_meal(body: MealIn, session: Session, me: Caller) -> Meal:
    """Macros are copied from the food at logging time."""
    return await service.log_meal(session, me, body)


@router.delete("/me/meals/{meal_id}", status_code=204)
async def delete_meal(meal_id: UUID, session: Session, me: Caller) -> Response:
    await service.delete_meal(session, me, meal_id)
    return Response(status_code=204)


@router.post("/me/hydration", response_model=WaterOut, status_code=201)
async def log_water(body: WaterIn, session: Session, me: Caller) -> WaterOut:
    return await service.log_water(session, me, body)


@router.delete("/me/hydration/{log_id}", status_code=204)
async def delete_water(log_id: UUID, session: Session, me: Caller) -> Response:
    await service.delete_water(session, me, log_id)
    return Response(status_code=204)


@router.get("/me/nutrition/day", response_model=NutritionDay)
async def nutrition_day(session: Session, me: Caller, date: date | None = None) -> NutritionDay:
    """Totals, targets, water, energy balance and meals for one local day (default today)."""
    return await service.day(session, me.id, me.user.timezone, date)


@router.get("/me/nutrition/targets", response_model=NutritionTargetsOut)
async def get_targets(session: Session, me: Caller) -> NutritionTargetsOut:
    return await service.get_targets(session, me)


@router.patch("/me/nutrition/targets", response_model=NutritionTargetsOut)
async def update_targets(body: NutritionTargetsIn, session: Session, me: Caller) -> NutritionTargetsOut:
    return await service.update_targets(session, me, body)
