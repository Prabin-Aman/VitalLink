"""Nutrition rules: food search, meals with copied macros, water, targets, the day view."""

import uuid
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

from sqlalchemy import delete, func, or_, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiError
from app.modules.account import service as account
from app.modules.account.service import CurrentUser
from app.modules.health import service as health
from app.modules.nutrition.models import (
    Food,
    FoodSource,
    HydrationLog,
    MealEntry,
    MealItem,
    NutritionTargets,
)
from app.modules.nutrition.schemas import (
    FoodIn,
    FoodOut,
    MacroGoals,
    Meal,
    MealIn,
    NutritionDay,
    NutritionTargetsIn,
    NutritionTargetsOut,
    WaterIn,
    WaterOut,
)


def _now() -> datetime:
    return datetime.now(UTC)


def _food_out(f: Food) -> FoodOut:
    return FoodOut(
        id=f.id,
        name=f.name,
        brand=f.brand,
        serving_g=float(f.serving_g),
        kcal=float(f.kcal),
        protein_g=float(f.protein_g),
        carbs_g=float(f.carbs_g),
        fat_g=float(f.fat_g),
        fibre_g=float(f.fibre_g),
    )


async def search_foods(session: AsyncSession, me: CurrentUser, q: str) -> list[FoodOut]:
    """Seeded foods and the caller's own, by name. The catalogue is small, so a plain ILIKE is enough."""
    pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    rows = await session.scalars(
        select(Food)
        .where(
            Food.name.ilike(pattern, escape="\\"),
            or_(Food.source == FoodSource.seeded, Food.owner_user_id == me.id),
        )
        .order_by(func.length(Food.name), Food.name)
        .limit(25)
    )
    return [_food_out(f) for f in rows]


async def create_food(session: AsyncSession, me: CurrentUser, body: FoodIn) -> FoodOut:
    food = Food(source=FoodSource.user, owner_user_id=me.id, **body.model_dump())
    session.add(food)
    await session.flush()
    return _food_out(food)


def _meal_out(m: MealEntry, tz: ZoneInfo) -> Meal:
    return Meal(
        id=m.id,
        name=m.name,
        eaten_at=m.eaten_at.astimezone(tz),
        items=[i.label for i in m.items],
        kcal=round(sum(float(i.kcal) for i in m.items)),
        protein_g=round(sum(float(i.protein_g) for i in m.items)),
        carbs_g=round(sum(float(i.carbs_g) for i in m.items)),
        fat_g=round(sum(float(i.fat_g) for i in m.items)),
    )


async def log_meal(session: AsyncSession, me: CurrentUser, body: MealIn) -> Meal:
    food_ids = {i.food_id for i in body.items if i.food_id}
    foods = {
        f.id: f
        for f in await session.scalars(
            select(Food).where(
                Food.id.in_(food_ids), or_(Food.source == FoodSource.seeded, Food.owner_user_id == me.id)
            )
        )
    }
    missing = food_ids - set(foods)
    if missing:
        raise ApiError(404, "unknown_food", "One of the foods doesn't exist.", [str(m) for m in missing])

    items = []
    for position, i in enumerate(body.items):
        if i.food_id:
            f, share = foods[i.food_id], Decimal(str(i.quantity_g)) / 100
            label = i.label or f.name
            macros = {
                k: round(getattr(f, k) * share, 1)
                for k in ("kcal", "protein_g", "carbs_g", "fat_g", "fibre_g")
            }
        else:
            label = i.label
            macros = {
                "kcal": i.kcal,
                "protein_g": i.protein_g,
                "carbs_g": i.carbs_g,
                "fat_g": i.fat_g,
                "fibre_g": i.fibre_g or 0,
            }
        items.append(
            MealItem(food_id=i.food_id, position=position, label=label, quantity_g=i.quantity_g, **macros)
        )

    meal = MealEntry(
        user_id=me.id,
        slot=body.slot,
        name=body.name or body.slot.value.capitalize(),
        eaten_at=body.eaten_at,
        items=items,
    )
    session.add(meal)
    await session.flush()
    return _meal_out(meal, ZoneInfo(me.user.timezone))


async def delete_meal(session: AsyncSession, me: CurrentUser, meal_id: uuid.UUID) -> None:
    result = await session.execute(
        delete(MealEntry).where(MealEntry.id == meal_id, MealEntry.user_id == me.id)
    )
    if result.rowcount == 0:
        raise ApiError(404, "not_found", "No such meal.")


async def log_water(session: AsyncSession, me: CurrentUser, body: WaterIn) -> WaterOut:
    at = body.at or _now()
    if at > _now() + timedelta(minutes=5):
        raise ApiError(422, "invalid_request", "That's in the future.")
    log = HydrationLog(user_id=me.id, logged_at=at, ml=body.ml)
    session.add(log)
    await session.flush()
    return WaterOut(id=log.id, ml=log.ml, at=log.logged_at.astimezone(ZoneInfo(me.user.timezone)))


async def delete_water(session: AsyncSession, me: CurrentUser, log_id: uuid.UUID) -> None:
    result = await session.execute(
        delete(HydrationLog).where(HydrationLog.id == log_id, HydrationLog.user_id == me.id)
    )
    if result.rowcount == 0:
        raise ApiError(404, "not_found", "No such entry.")


# ---------------------------------------------------------------- targets and the day view


async def _targets(session: AsyncSession, user_id: uuid.UUID) -> NutritionTargets:
    """Insert-if-absent with the defaults, then read."""
    await session.execute(insert(NutritionTargets).values(user_id=user_id).on_conflict_do_nothing())
    targets = await session.get(NutritionTargets, user_id, populate_existing=True)
    assert targets is not None
    return targets


def _targets_out(t: NutritionTargets) -> NutritionTargetsOut:
    return NutritionTargetsOut(
        kcal=t.kcal,
        protein_g=t.protein_g,
        carbs_g=t.carbs_g,
        fat_g=t.fat_g,
        fibre_g=t.fibre_g,
        water_ml=t.water_ml,
        glass_ml=t.glass_ml,
        weekly_target_kg=float(t.weekly_target_kg),
    )


async def get_targets(session: AsyncSession, me: CurrentUser) -> NutritionTargetsOut:
    return _targets_out(await _targets(session, me.id))


async def update_targets(
    session: AsyncSession, me: CurrentUser, body: NutritionTargetsIn
) -> NutritionTargetsOut:
    t = await _targets(session, me.id)
    for field, value in body.model_dump(exclude_unset=True, exclude_none=True).items():
        setattr(t, field, value)
    await session.flush()
    return _targets_out(t)


async def base_burn(session: AsyncSession, user_id: uuid.UUID, on: date) -> int | None:
    """Mifflin-St Jeor resting energy. None unless height, weight, birth date and sex are all known."""
    profile = await account.body_profile(session, user_id)
    weight = await health.latest_value(session, user_id, "weight")
    if profile is None or weight is None or None in (profile.height_cm, profile.date_of_birth, profile.sex):
        return None
    age = (on - profile.date_of_birth).days // 365
    sex_term = {"male": 5, "female": -161}.get(profile.sex, -78)  # other/undisclosed: the midpoint
    return round(10 * weight + 6.25 * float(profile.height_cm) - 5 * age + sex_term)


async def day(session: AsyncSession, user_id: uuid.UUID, tz_name: str, on: date | None) -> NutritionDay:
    tz = ZoneInfo(tz_name)
    on = on or _now().astimezone(tz).date()
    start = datetime.combine(on, time(), tz)
    end = start + timedelta(days=1)

    meals = (
        await session.scalars(
            select(MealEntry)
            .where(MealEntry.user_id == user_id, MealEntry.eaten_at >= start, MealEntry.eaten_at < end)
            .order_by(MealEntry.eaten_at)
        )
    ).all()
    water = await session.scalar(
        select(func.coalesce(func.sum(HydrationLog.ml), 0)).where(
            HydrationLog.user_id == user_id, HydrationLog.logged_at >= start, HydrationLog.logged_at < end
        )
    )
    t = await _targets(session, user_id)
    items = [i for m in meals for i in m.items]

    def total(field: str) -> int:
        return round(sum(float(getattr(i, field)) for i in items))

    return NutritionDay(
        date=start,
        kcal=total("kcal"),
        kcal_goal=t.kcal,
        protein_g=total("protein_g"),
        carbs_g=total("carbs_g"),
        fat_g=total("fat_g"),
        fibre_g=total("fibre_g"),
        goals=MacroGoals(protein_g=t.protein_g, carbs_g=t.carbs_g, fat_g=t.fat_g, fibre_g=t.fibre_g),
        water_ml=water,
        water_goal_ml=t.water_ml,
        glass_ml=t.glass_ml,
        base_burn_kcal=await base_burn(session, user_id, on),
        active_burn_kcal=round(await health.total_between(session, user_id, "active_energy", start, end)),
        weekly_target_kg=float(t.weekly_target_kg),
        meals=[_meal_out(m, tz) for m in meals],
    )
