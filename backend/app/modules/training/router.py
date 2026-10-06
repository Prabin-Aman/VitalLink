from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_session
from app.modules.account.service import CurrentUser, current_user
from app.modules.training import service
from app.modules.training.schemas import (
    ExerciseOut,
    PlanIn,
    PlanOut,
    Training,
    WorkoutDetail,
    WorkoutIn,
    WorkoutOut,
)

router = APIRouter(tags=["training"])

Session = Annotated[AsyncSession, Depends(get_session)]
Caller = Annotated[CurrentUser, Depends(current_user)]


@router.post("/me/workouts", response_model=WorkoutDetail, status_code=201)
async def save_workout(body: WorkoutIn, response: Response, session: Session, me: Caller) -> WorkoutDetail:
    """Log a workout with its sets. A synced workout with a known platform id is updated (200)."""
    workout, created = await service.save_workout(session, me, body)
    if not created:
        response.status_code = 200
    return workout


@router.get("/me/workouts", response_model=list[WorkoutOut])
async def list_workouts(
    session: Session, me: Caller, days: Annotated[int, Query(ge=1, le=400)] = 7
) -> list[WorkoutOut]:
    return await service.list_workouts(session, me.id, me.user.timezone, days)


@router.get("/me/workouts/{workout_id}", response_model=WorkoutDetail)
async def get_workout(workout_id: UUID, session: Session, me: Caller) -> WorkoutDetail:
    return await service.get_workout(session, me, workout_id)


@router.delete("/me/workouts/{workout_id}", status_code=204)
async def delete_workout(workout_id: UUID, session: Session, me: Caller) -> Response:
    await service.delete_workout(session, me, workout_id)
    return Response(status_code=204)


@router.get("/me/training", response_model=Training)
async def training(session: Session, me: Caller) -> Training:
    """This week's load, recent sessions, muscle balance, personal records and the next planned session."""
    return await service.training(session, me.id, me.user.timezone)


@router.post("/me/training/plans", response_model=PlanOut, status_code=201)
async def create_plan(body: PlanIn, session: Session, me: Caller) -> PlanOut:
    return await service.create_plan(session, me, body)


@router.get("/me/training/plans", response_model=list[PlanOut])
async def list_plans(session: Session, me: Caller) -> list[PlanOut]:
    return await service.list_plans(session, me)


@router.get("/exercises", response_model=list[ExerciseOut])
async def exercises(me: Caller) -> list[ExerciseOut]:
    return service.exercises()
