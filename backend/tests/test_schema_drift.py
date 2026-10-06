"""The models and the migrations describe the same schema (docs/08 §8.3, drift check)."""

import asyncio

from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

from app.core.db import SCHEMA
from app.core.models import Base
from app.models import load_all


def _diff(sync_conn):
    ctx = MigrationContext.configure(
        sync_conn,
        opts={
            "include_schemas": True,
            "include_name": lambda name, type_, parent: (
                name == SCHEMA if type_ == "schema" else name != "alembic_version"
            ),
            "compare_type": True,
        },
    )
    return compare_metadata(ctx, Base.metadata)


async def _run(url: str):
    # Pin the default schema to public, as on Supabase. Locally the role is named `vitallink`, and
    # Postgres's "$user" search path would make the private schema the default and confuse the diff.
    engine = create_async_engine(
        url, poolclass=NullPool, connect_args={"server_settings": {"search_path": "public"}}
    )
    async with engine.connect() as conn:
        diff = await conn.run_sync(_diff)
    await engine.dispose()
    return diff


def test_models_match_migrations(database):
    load_all()
    assert asyncio.run(_run(database)) == []
