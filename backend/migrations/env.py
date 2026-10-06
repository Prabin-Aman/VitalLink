"""Alembic runs migrations over the app's async engine. Everything lives in the private schema,
including Alembic's own version table, so nothing is created in `public` (docs/06 §6.2)."""

import asyncio
from logging.config import fileConfig

from alembic import context
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

from app.core.db import SCHEMA
from app.core.settings import get_settings

if context.config.config_file_name:
    fileConfig(context.config.config_file_name)

# Models register here once they exist (autogenerate + drift check).
target_metadata = None


def _run(connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata, version_table_schema=SCHEMA)
    with context.begin_transaction():
        context.run_migrations()


async def _main() -> None:
    url = get_settings().database_url
    if not url:
        raise SystemExit("DATABASE_URL is not set (see backend/.env.example)")
    engine = create_async_engine(url, poolclass=NullPool)
    async with engine.connect() as conn:
        # The version table lives in the schema, so the schema must exist before Alembic starts.
        await conn.execute(text(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}"))
        await conn.commit()
        await conn.run_sync(_run)
    await engine.dispose()


if context.is_offline_mode():
    raise SystemExit("Offline (--sql) mode isn't supported; run against a database.")
asyncio.run(_main())
