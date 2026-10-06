from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_session
from app.modules.account import service
from app.modules.account.schemas import Account, AccountPatch, Me, Targets, TargetsPatch
from app.modules.account.service import CurrentUser, current_user

router = APIRouter(prefix="/me", tags=["account"])

Session = Annotated[AsyncSession, Depends(get_session)]
Caller = Annotated[CurrentUser, Depends(current_user)]


@router.get("", response_model=Me)
async def get_me(session: Session, me: Caller) -> Me:
    """The signed-in account. The first call creates it."""
    return await service.get_me(session, me)


@router.get("/account", response_model=Account)
async def get_account(session: Session, me: Caller) -> Account:
    return await service.get_account(session, me)


@router.patch("/account", response_model=Account)
async def update_account(body: AccountPatch, session: Session, me: Caller) -> Account:
    return await service.update_account(session, me, body)


@router.get("/targets", response_model=Targets)
async def get_targets(session: Session, me: Caller) -> Targets:
    return await service.get_targets(session, me)


@router.patch("/targets", response_model=Targets)
async def update_targets(body: TargetsPatch, session: Session, me: Caller) -> Targets:
    return await service.update_targets(session, me, body)
