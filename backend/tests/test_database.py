import uuid

import asyncpg
import pytest
from fastapi.testclient import TestClient

from app.main import app


def test_ready_when_database_is_up(database):
    res = TestClient(app).get("/ready")
    assert res.status_code == 200
    assert res.json() == {"status": "ready"}


@pytest.mark.anyio
async def test_nothing_is_created_in_public(pg):
    tables = await pg.fetch("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")
    assert tables == []


@pytest.mark.anyio
async def test_every_table_has_row_level_security(pg):
    rows = await pg.fetch(
        "SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
        "WHERE n.nspname = 'vitallink' AND c.relkind = 'r' AND NOT c.relrowsecurity "
        "AND relname <> 'alembic_version'"
    )
    assert [r["relname"] for r in rows] == []


async def _insert_read(pg, outcome="authorised", reason=None) -> int:
    return await pg.fetchval(
        "INSERT INTO vitallink.audit_log (actor_user_id, subject_user_id, action, scope, outcome, reason) "
        "VALUES ($1, $2, 'read', 'sleep', $3, $4) RETURNING id",
        uuid.uuid4(),
        uuid.uuid4(),
        outcome,
        reason,
    )


@pytest.mark.anyio
async def test_audit_log_accepts_inserts(pg):
    assert await _insert_read(pg) > 0
    assert await _insert_read(pg, "blocked", "expired") > 0


@pytest.mark.anyio
@pytest.mark.parametrize(
    "sql",
    [
        "UPDATE vitallink.audit_log SET outcome = 'authorised' WHERE id = $1",
        "DELETE FROM vitallink.audit_log WHERE id = $1",
    ],
)
async def test_audit_log_is_append_only(pg, sql):
    entry = await _insert_read(pg, "blocked", "revoked")
    with pytest.raises(asyncpg.InsufficientPrivilegeError, match="append-only"):
        await pg.execute(sql, entry)


@pytest.mark.anyio
async def test_audit_log_cannot_be_truncated(pg):
    with pytest.raises(asyncpg.InsufficientPrivilegeError, match="append-only"):
        await pg.execute("TRUNCATE vitallink.audit_log")


@pytest.mark.anyio
@pytest.mark.parametrize(("outcome", "reason"), [("blocked", None), ("authorised", "expired")])
async def test_refusal_reason_only_on_blocked(pg, outcome, reason):
    with pytest.raises(asyncpg.CheckViolationError):
        await _insert_read(pg, outcome, reason)
