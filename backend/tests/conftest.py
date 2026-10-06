"""Tests run against a real Postgres (docs/08-operations-and-testing.md §8.7).

The test database is `<your db>_test` next to the one in DATABASE_URL. It's created if missing and
migrated to head once per run. Tests that need it are skipped when Postgres isn't reachable.
"""

import asyncio
import os

import asyncpg
import pytest
from sqlalchemy.engine import make_url

from app.core.settings import Settings

_base = Settings().database_url  # read before ENV/DATABASE_URL are overridden below
TEST_URL = None
if _base:
    url = make_url(_base)
    TEST_URL = url.set(database=f"{url.database}_test").render_as_string(hide_password=False)
    os.environ["DATABASE_URL"] = TEST_URL
os.environ["ENV"] = "test"
os.environ.setdefault("DEV_JWT_SECRET", "test-only-secret-" + "x" * 32)


def _plain(sqlalchemy_url: str, database: str | None = None) -> str:
    """asyncpg wants postgresql://, not postgresql+asyncpg://."""
    url = make_url(sqlalchemy_url).set(drivername="postgresql")
    if database:
        url = url.set(database=database)
    return url.render_as_string(hide_password=False)


async def _ensure_database(test_url: str) -> None:
    name = make_url(test_url).database
    admin = await asyncpg.connect(_plain(test_url, "postgres"))
    try:
        if not await admin.fetchval("SELECT 1 FROM pg_database WHERE datname = $1", name):
            await admin.execute(f'CREATE DATABASE "{name}"')
    finally:
        await admin.close()


@pytest.fixture(scope="session")
def database() -> str:
    """A migrated test database; skips the test if Postgres isn't running (fails instead in CI)."""
    unavailable = pytest.fail if os.environ.get("REQUIRE_DB") else pytest.skip
    if not TEST_URL:
        unavailable("DATABASE_URL isn't set (backend/.env)")
    try:
        asyncio.run(_ensure_database(TEST_URL))
    except (OSError, asyncpg.PostgresError) as exc:
        unavailable(f"Postgres isn't reachable ({exc.__class__.__name__}); run `docker compose up`")

    from alembic import command
    from alembic.config import Config

    command.upgrade(Config("alembic.ini"), "head")
    return TEST_URL


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
async def pg(database: str):
    """A raw asyncpg connection to the test database."""
    conn = await asyncpg.connect(_plain(database))
    try:
        yield conn
    finally:
        await conn.close()


@pytest.fixture
def client(database):
    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


@pytest.fixture
def make_token():
    """make_token(email=..., **claims) -> Authorization header for a fresh dev user."""
    import uuid as _uuid

    from app.core.security import mint_dev_token
    from app.core.settings import get_settings

    def _make(email: str | None = None, subject: _uuid.UUID | None = None, **kwargs) -> dict[str, str]:
        subject = subject or _uuid.uuid4()
        email = email or f"user-{subject.hex[:12]}@example.com"
        token = mint_dev_token(get_settings(), subject=subject, email=email, **kwargs)
        return {"Authorization": f"Bearer {token}"}

    return _make
