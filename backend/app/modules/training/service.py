"""Training rules: log workouts with sets, plans, and the weekly training view (docs/05 Training)."""

import uuid
from collections import defaultdict
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy import delete, func, select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.errors import ApiError
from app.modules.account import service as account
from app.modules.account.service import CurrentUser
from app.modules.health import service as health
from app.modules.health.models import ReadingSource
from app.modules.training.catalogue import BY_NAME, EXERCISES
from app.modules.training.models import (
    Exercise,
    ExerciseSet,
    MuscleGroup,
    PlanSession,
    PlanStatus,
    SessionKind,
    TrainingPlan,
    Workout,
    WorkoutType,
)
from app.modules.training.schemas import (
    PR,
    ExerciseOut,
    MuscleGroupSets,
    Muscles,
    PersonalRecord,
    PlanIn,
    PlannedSession,
    PlanOut,
    SetOut,
    Training,
    TrainingSession,
    TrainingWeek,
    WorkoutDetail,
    WorkoutIn,
    WorkoutOut,
)

KIND_OF = {
    WorkoutType.strength: SessionKind.strength,
    WorkoutType.yoga: SessionKind.mobility,
}
DEFAULT_WEEKLY_TARGET = 5
MUSCLE_DAYS = 14
MUSCLE_LABEL = {m: m.value.replace("_", " ").capitalize() for m in MuscleGroup}
PACED = {WorkoutType.run, WorkoutType.walk}


def _now() -> datetime:
    return datetime.now(UTC)


def _volume(w: Workout) -> float | None:
    weighted = [s for s in w.sets if s.reps and s.weight_kg is not None]
    return round(sum(float(s.weight_kg) * s.reps for s in weighted), 1) if weighted else None


def _zone(w: Workout, max_hr: int) -> int:
    """Dominant heart-rate zone 1–5: from zone times, else average HR as a share of max, else by kind."""
    if w.zone_seconds and any(w.zone_seconds):
        return max(range(5), key=lambda i: w.zone_seconds[i]) + 1
    if w.avg_hr:
        share = w.avg_hr / max_hr
        return 1 if share < 0.6 else 2 if share < 0.7 else 3 if share < 0.8 else 4 if share < 0.9 else 5
    return 1 if w.kind is SessionKind.mobility else 3


def _workout_out(w: Workout, tz: ZoneInfo) -> WorkoutOut:
    return WorkoutOut(
        id=w.id,
        type=w.type,
        title=w.title,
        started_at=w.started_at.astimezone(tz),
        duration_s=w.duration_s,
        distance_m=float(w.distance_m) if w.distance_m is not None else None,
        kcal=w.kcal,
        avg_hr=w.avg_hr,
    )


def _detail(w: Workout, tz: ZoneInfo) -> WorkoutDetail:
    sets = [
        SetOut(
            exercise=s.exercise.name,
            set_no=s.set_no,
            reps=s.reps,
            weight_kg=float(s.weight_kg) if s.weight_kg is not None else None,
            duration_s=s.duration_s,
        )
        for s in w.sets
    ]
    return WorkoutDetail(
        **_workout_out(w, tz).model_dump(),
        kind=w.kind,
        rpe=w.rpe,
        zone_seconds=w.zone_seconds,
        focus=w.focus,
        source=w.source.value,
        sets=sets,
    )


# ---------------------------------------------------------------- workouts


async def save_workout(session: AsyncSession, me: CurrentUser, body: WorkoutIn) -> tuple[WorkoutDetail, bool]:
    """Create, or update a synced workout with the same platform id. Returns (workout, created)."""
    unknown = sorted({s.exercise for s in body.sets if s.exercise.lower() not in BY_NAME})
    if unknown:
        raise ApiError(422, "unknown_exercise", f"Unknown exercise: {unknown[0]}.", unknown)
    if body.plan_session_id is not None:
        owns = await session.scalar(
            select(PlanSession.id)
            .join(TrainingPlan)
            .where(PlanSession.id == body.plan_session_id, TrainingPlan.user_id == me.id)
        )
        if owns is None:
            raise ApiError(404, "not_found", "No such planned session.")

    workout = None
    if body.external_id is not None:
        workout = await session.scalar(
            select(Workout)
            .options(selectinload(Workout.sets))
            .where(
                Workout.user_id == me.id,
                Workout.source == ReadingSource(body.source),
                Workout.external_id == body.external_id,
            )
        )
    created = workout is None
    if created:
        workout = Workout(
            user_id=me.id, source=ReadingSource(body.source), external_id=body.external_id, sets=[]
        )
        session.add(workout)
    workout.type = body.type
    workout.kind = body.kind or KIND_OF.get(body.type, SessionKind.cardio)
    workout.title = body.title
    workout.started_at = body.started_at
    workout.duration_s = body.duration_s
    workout.distance_m = body.distance_m
    workout.kcal = body.kcal
    workout.avg_hr = body.avg_hr
    workout.rpe = body.rpe
    workout.zone_seconds = body.zone_seconds
    workout.focus = body.focus
    workout.plan_session_id = body.plan_session_id

    numbering: dict[str, int] = defaultdict(int)
    new_sets = []
    for s in body.sets:
        exercise_id, _, _ = BY_NAME[s.exercise.lower()]
        numbering[exercise_id] += 1
        new_sets.append(
            ExerciseSet(
                exercise_id=exercise_id,
                set_no=numbering[exercise_id],
                reps=s.reps,
                weight_kg=s.weight_kg,
                duration_s=s.duration_s,
            )
        )
    workout.sets.clear()
    await session.flush()
    workout.sets.extend(new_sets)
    await session.flush()
    return await get_workout(session, me, workout.id), created


async def get_workout(session: AsyncSession, me: CurrentUser, workout_id: uuid.UUID) -> WorkoutDetail:
    w = await session.scalar(
        select(Workout)
        .options(selectinload(Workout.sets).joinedload(ExerciseSet.exercise))
        .where(Workout.id == workout_id, Workout.user_id == me.id)
        .execution_options(populate_existing=True)
    )
    if w is None:
        raise ApiError(404, "not_found", "No such workout.")
    return _detail(w, ZoneInfo(me.user.timezone))


async def delete_workout(session: AsyncSession, me: CurrentUser, workout_id: uuid.UUID) -> None:
    result = await session.execute(delete(Workout).where(Workout.id == workout_id, Workout.user_id == me.id))
    if result.rowcount == 0:
        raise ApiError(404, "not_found", "No such workout.")


def _local_midnight(day: date, tz: ZoneInfo) -> datetime:
    return datetime.combine(day, time(), tz)


async def list_workouts(
    session: AsyncSession, user_id: uuid.UUID, tz_name: str, days: int
) -> list[WorkoutOut]:
    """Workouts started in the last `days` local days, newest first."""
    tz = ZoneInfo(tz_name)
    since = _local_midnight(_now().astimezone(tz).date() - timedelta(days=days - 1), tz)
    rows = await session.scalars(
        select(Workout)
        .where(Workout.user_id == user_id, Workout.started_at >= since)
        .order_by(Workout.started_at.desc())
    )
    return [_workout_out(w, tz) for w in rows]


# ---------------------------------------------------------------- the training view


def _streaks(days_with_workouts: set[date], today: date) -> tuple[int, int]:
    """(current, longest). The current streak may end yesterday: today isn't over yet."""
    current, day = 0, today if today in days_with_workouts else today - timedelta(days=1)
    while day in days_with_workouts:
        current, day = current + 1, day - timedelta(days=1)
    longest = run = 0
    previous = None
    for d in sorted(days_with_workouts):
        run = run + 1 if previous is not None and d - previous == timedelta(days=1) else 1
        longest, previous = max(longest, run), d
    return current, longest


def _muscle_note(groups: list[MuscleGroupSets]) -> str:
    if not groups:
        return "Log strength sets to see how your training is spread across muscle groups."
    top = groups[0].sets
    # Under 40% of the most-trained group counts as trailing.
    trailing = [g.group.lower() for g in groups if g.sets < top * 0.4]
    if not trailing:
        return "Your training is well balanced across muscle groups."
    names = (
        trailing[0].capitalize()
        if len(trailing) == 1
        else (", ".join(trailing[:-1]).capitalize() + " and " + trailing[-1])
    )
    verb = "is" if len(trailing) == 1 else "are"
    return f"{names} {verb} trailing. Two accessory sets would even this out."


async def training(session: AsyncSession, user_id: uuid.UUID, tz_name: str) -> Training:
    tz = ZoneInfo(tz_name)
    now = _now()
    today = now.astimezone(tz).date()
    week_start = _local_midnight(today - timedelta(days=today.weekday()), tz)
    week_end = week_start + timedelta(days=7)

    history = (
        await session.scalars(
            select(Workout)
            .options(selectinload(Workout.sets).joinedload(ExerciseSet.exercise))
            .where(Workout.user_id == user_id, Workout.started_at >= now - timedelta(days=365))
            .order_by(Workout.started_at)
        )
    ).all()
    total = await session.scalar(select(func.count()).select_from(Workout).where(Workout.user_id == user_id))

    dob = await account.birth_date(session, user_id)
    max_hr = 220 - ((today - dob).days // 365) if dob else 190

    # Personal records: walk history in time order, keeping the best weight per exercise.
    best: dict[int, tuple[float, int, datetime, str]] = {}
    session_pr: dict[uuid.UUID, PR] = {}
    for w in history:
        top_this: dict[int, tuple[float, int, str]] = {}
        for s in w.sets:
            if s.weight_kg is None or not s.reps:
                continue
            weight = float(s.weight_kg)
            if s.exercise_id not in top_this or weight > top_this[s.exercise_id][0]:
                top_this[s.exercise_id] = (weight, s.reps, s.exercise.name)
        for exercise_id, (weight, reps, name) in top_this.items():
            previous = best.get(exercise_id)
            if previous is None or weight > previous[0]:
                if previous is not None and (w.id not in session_pr or weight > session_pr[w.id].weight_kg):
                    session_pr[w.id] = PR(exercise=name, weight_kg=weight)
                best[exercise_id] = (weight, reps, w.started_at, name)

    records = [
        PersonalRecord(exercise=name, achieved_at=at.astimezone(tz), weight_kg=weight, reps=reps, time_s=None)
        for weight, reps, at, name in sorted(best.values(), key=lambda b: b[2], reverse=True)[:3]
    ]
    runs = [w for w in history if w.type is WorkoutType.run and w.distance_m and w.distance_m >= 5000]
    if runs:
        fastest = min(runs, key=lambda w: w.duration_s / float(w.distance_m))
        records.append(
            PersonalRecord(
                exercise="5 km run",
                achieved_at=fastest.started_at.astimezone(tz),
                weight_kg=None,
                reps=None,
                time_s=round(fastest.duration_s * 5000 / float(fastest.distance_m)),
            )
        )

    recent = [w for w in history if w.started_at >= now - timedelta(days=MUSCLE_DAYS)]
    sessions = []
    for w in reversed(recent):
        volume = _volume(w)
        distance = float(w.distance_m) if w.distance_m is not None else None
        sessions.append(
            TrainingSession(
                id=w.id,
                type=w.type,
                kind=w.kind,
                title=w.title,
                started_at=w.started_at.astimezone(tz),
                duration_s=w.duration_s,
                exercises=len({s.exercise_id for s in w.sets}) or None,
                sets=len(w.sets) or None,
                volume_kg=volume,
                distance_m=distance,
                pace_s_per_km=round(w.duration_s / (distance / 1000))
                if w.type in PACED and distance
                else None,
                kcal=w.kcal,
                avg_hr=w.avg_hr,
                rpe=w.rpe,
                zone=_zone(w, max_hr),
                focus=w.focus,
                pr=session_pr.get(w.id),
            )
        )

    per_group: dict[MuscleGroup, int] = defaultdict(int)
    for w in recent:
        for s in w.sets:
            per_group[s.exercise.primary_muscle] += 1
    groups = sorted(
        (MuscleGroupSets(group=MUSCLE_LABEL[g], sets=n) for g, n in per_group.items()), key=lambda g: -g.sets
    )

    this_week = [w for w in history if week_start <= w.started_at < week_end]
    load: list[dict[SessionKind, int]] = [{} for _ in range(7)]
    for w in this_week:
        day = load[w.started_at.astimezone(tz).weekday()]
        day[w.kind] = day.get(w.kind, 0) + round(w.duration_s / 60)
    current, longest = _streaks({w.started_at.astimezone(tz).date() for w in history}, today)

    plan = await session.scalar(
        select(TrainingPlan).where(TrainingPlan.user_id == user_id, TrainingPlan.status == PlanStatus.active)
    )
    upcoming = None
    if plan is not None:
        upcoming = await session.scalar(
            select(PlanSession)
            .where(PlanSession.plan_id == plan.id, PlanSession.scheduled_for > now)
            .order_by(PlanSession.scheduled_for)
            .limit(1)
        )

    return Training(
        total_sessions=total or 0,
        week=TrainingWeek(
            sessions=len(this_week),
            target_sessions=plan.weekly_target_sessions if plan else DEFAULT_WEEKLY_TARGET,
            minutes=round(sum(w.duration_s for w in this_week) / 60),
            volume_kg=round(sum(_volume(w) or 0 for w in this_week), 1),
            active_kcal=round(
                await health.total_between(session, user_id, "active_energy", week_start, week_end)
            ),
            streak_days=current,
            longest_streak_days=longest,
            source=await health.wearable_name(session, user_id) or "Manual entries",
            load=load,
        ),
        sessions=sessions,
        muscles=Muscles(days=MUSCLE_DAYS, groups=groups, note=_muscle_note(groups)),
        records=records,
        next=_planned(upcoming, tz) if upcoming else None,
    )


# ---------------------------------------------------------------- plans and catalogue


def _planned(p: PlanSession, tz: ZoneInfo) -> PlannedSession:
    return PlannedSession(
        id=p.id,
        title=p.title,
        kind=p.kind.value.capitalize(),
        starts_at=p.scheduled_for.astimezone(tz),
        exercises=p.exercises,
        minutes=p.minutes,
    )


def _plan_out(plan: TrainingPlan, tz: ZoneInfo) -> PlanOut:
    return PlanOut(
        id=plan.id,
        name=plan.name,
        status=plan.status.value,
        weekly_target_sessions=plan.weekly_target_sessions,
        sessions=[_planned(s, tz) for s in plan.sessions],
    )


async def create_plan(session: AsyncSession, me: CurrentUser, body: PlanIn) -> PlanOut:
    """The new plan becomes active; the previous active plan is archived."""
    await session.execute(
        update(TrainingPlan)
        .where(TrainingPlan.user_id == me.id, TrainingPlan.status == PlanStatus.active)
        .values(status=PlanStatus.archived)
    )
    plan = TrainingPlan(
        user_id=me.id,
        name=body.name,
        status=PlanStatus.active,
        weekly_target_sessions=body.weekly_target_sessions,
        sessions=[
            PlanSession(
                title=s.title,
                kind=s.kind,
                scheduled_for=s.scheduled_for,
                exercises=s.exercises,
                minutes=s.minutes,
            )  # fmt: skip
            for s in sorted(body.sessions, key=lambda s: s.scheduled_for)
        ],
    )
    session.add(plan)
    await session.flush()
    return _plan_out(plan, ZoneInfo(me.user.timezone))


async def list_plans(session: AsyncSession, me: CurrentUser) -> list[PlanOut]:
    plans = await session.scalars(
        select(TrainingPlan)
        .options(selectinload(TrainingPlan.sessions))
        .where(TrainingPlan.user_id == me.id)
        .order_by(TrainingPlan.created_at.desc())
    )
    tz = ZoneInfo(me.user.timezone)
    return [_plan_out(p, tz) for p in plans]


def exercises() -> list[ExerciseOut]:
    return [ExerciseOut(id=i, name=n, primary_muscle=m.value) for i, n, m in EXERCISES]


async def catalogue_matches_database(session: AsyncSession) -> bool:
    rows = {(e.id, e.name, e.primary_muscle) for e in await session.scalars(select(Exercise))}
    return rows == set(EXERCISES)
