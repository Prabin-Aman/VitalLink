"""Sharing bodies. Responses match packages/api/src/types.ts (Share, SharedWithMe, AccessLogEntry)."""

from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, EmailStr, Field, StringConstraints

from app.core.audit import Scope
from app.modules.sharing.models import GranteeKind


class ShareIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    email: EmailStr
    label: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)]
    kind: GranteeKind
    scopes: Annotated[list[Scope], Field(min_length=1, max_length=5)]
    purpose: Annotated[str, StringConstraints(strip_whitespace=True, max_length=200)] = ""
    starts_at: AwareDatetime | None = None  # default: now
    expires_at: AwareDatetime | None = None  # null: until revoked


class Share(BaseModel):
    id: UUID
    grantee_label: str
    grantee_email: str
    grantee_kind: GranteeKind
    scopes: list[Scope]
    purpose: str
    starts_at: datetime
    expires_at: datetime | None
    active: bool


class SharedWithMe(BaseModel):
    id: UUID
    owner_id: UUID
    owner_name: str
    scopes: list[Scope]
    expires_at: datetime | None


class AccessLogEntry(BaseModel):
    at: datetime
    actor: str
    actor_detail: str
    accessed: str
    purpose: str
    status: Literal["authorised", "limited", "blocked"]
