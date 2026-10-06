"""One async engine for the process; one transaction per request (docs/02-architecture.md)."""

from collections.abc import AsyncIterator
from functools import lru_cache

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.core.errors import ApiError
from app.core.settings import get_settings

SCHEMA = "vitallink"


@lru_cache
def get_engine() -> AsyncEngine:
    settings = get_settings()
    if not settings.database_url:
        raise ApiError(503, "db_unavailable", "The database isn't configured.")
    if settings.env == "test":
        # Each test client runs its own event loop; pooled connections can't cross loops.
        return create_async_engine(settings.database_url, poolclass=NullPool)
    # Supabase free tier: session pooler, small pool (docs/08-operations-and-testing.md).
    return create_async_engine(settings.database_url, pool_size=5, max_overflow=5, pool_pre_ping=True)


@lru_cache
def _sessions() -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(get_engine(), expire_on_commit=False)


async def get_session() -> AsyncIterator[AsyncSession]:
    """FastAPI dependency: commits on success, rolls back on any error."""
    async with _sessions()() as session, session.begin():
        yield session


async def ping() -> bool:
    try:
        async with get_engine().connect() as conn:
            await conn.execute(text("SELECT 1"))
        return True
    except Exception:
        return False
