"""Sharing end to end: grant → read → revoke → refused, all in the owner's access log (docs/07 §7.3)."""

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app.main import app


def _email() -> str:
    return f"viewer-{uuid.uuid4().hex[:10]}@example.com"


def _account(client, make_token, email=None, **kw):
    """(headers, user id, email) for a new account."""
    email = email or _email()
    subject = kw.pop("subject", None) or uuid.uuid4()
    headers = make_token(email=email, subject=subject, **kw)
    me = client.get("/v1/me", headers=headers).json()
    return headers, me["id"], email, subject


def _steps(client, headers, value=1234):
    now = datetime.now(UTC)
    start = datetime.fromtimestamp(int(now.timestamp()) // 300 * 300 - 600, UTC).isoformat()
    body = {
        "idempotency_key": str(uuid.uuid4()),
        "platform": "healthkit",
        "buckets": [
            {"metric": "steps", "start": start, "value": value},
            {"metric": "heart_rate", "start": start, "value": 70},
        ],
    }
    assert client.post("/v1/me/health/ingest", headers=headers, json=body).status_code == 202


def _share(client, owner, email, **kw):
    body = {"email": email, "label": "Dr. Laura Bennett", "kind": "doctor", "scopes": ["activity"]} | kw
    return client.post("/v1/me/shares", headers=owner, json=body)


@pytest.fixture
def pair(client, make_token):
    owner, owner_id, *_ = _account(client, make_token)
    viewer, viewer_id, viewer_email, viewer_sub = _account(client, make_token)
    _steps(client, owner)
    return owner, owner_id, viewer, viewer_id, viewer_email, viewer_sub


def _read(client, viewer, owner_id, metric="steps"):
    return client.get(f"/v1/users/{owner_id}/health/series/{metric}?days=1", headers=viewer)


# ---------------------------------------------------------------- the full story


def test_grant_read_revoke_refused_and_logged(client, pair):
    owner, owner_id, viewer, viewer_id, viewer_email, _ = pair

    # Before any share: 404, so the account's existence stays hidden.
    assert _read(client, viewer, owner_id).status_code == 404

    share = _share(client, owner, viewer_email, purpose="Treatment").json()
    assert share["active"] and share["scopes"] == ["activity"]

    res = _read(client, viewer, owner_id)
    assert res.status_code == 200
    assert sum(p["value"] for p in res.json()["points"]) == 1234

    # Activity was shared; vitals were not.
    denied = _read(client, viewer, owner_id, "heart_rate")
    assert (denied.status_code, denied.json()["error"]["code"]) == (403, "scope_not_granted")

    assert client.delete(f"/v1/me/shares/{share['id']}", headers=owner).status_code == 204
    after = _read(client, viewer, owner_id)
    assert (after.status_code, after.json()["error"]["code"]) == (403, "revoked")

    log = client.get("/v1/me/access-log", headers=owner).json()
    assert [(e["status"], e["accessed"], e["purpose"]) for e in log[:4]] == [
        ("blocked", "None — blocked", "Share revoked"),
        ("blocked", "None — blocked", "Not part of the share"),
        ("authorised", "Activity", "Treatment"),
        ("blocked", "None — blocked", "No share"),
    ]
    assert log[2]["actor"] == "Dr. Laura Bennett"
    assert log[1]["actor"] == "Dr. Laura Bennett"  # a refused scope still names the share
    assert log[2]["actor_detail"] == f"Doctor · {viewer_email}"


def test_shared_with_me(client, pair):
    owner, owner_id, viewer, _, viewer_email, _ = pair
    client.patch("/v1/me/account", headers=owner, json={"first_name": "Alex", "last_name": "Carter"})
    share = _share(client, owner, viewer_email, scopes=["activity", "sleep"]).json()
    received = client.get("/v1/me/shared-with-me", headers=viewer).json()
    assert [(r["owner_id"], r["owner_name"], r["scopes"]) for r in received] == [
        (owner_id, "Alex Carter", ["activity", "sleep"])
    ]
    client.delete(f"/v1/me/shares/{share['id']}", headers=owner)
    assert client.get("/v1/me/shared-with-me", headers=viewer).json() == []


def test_shared_dashboard_has_no_readiness_score(client, pair):
    owner, owner_id, viewer, _, viewer_email, _ = pair
    _share(client, owner, viewer_email, scopes=["vitals"])
    dash = client.get(f"/v1/users/{owner_id}/health/dashboard", headers=viewer).json()
    assert dash["health_score"] is None
    assert dash["metrics"][0]["latest"] == 70


# ---------------------------------------------------------------- refusals


def test_unconfirmed_email_gets_404(client, make_token, pair):
    owner, owner_id, *_ = pair
    email = _email()
    _share(client, owner, email)
    unconfirmed, *_ = _account(client, make_token, email=email, email_verified=False)
    assert _read(client, unconfirmed, owner_id).status_code == 404


def test_stranger_and_unknown_user_look_the_same(client, make_token, pair):
    _, owner_id, *_ = pair
    stranger, *_ = _account(client, make_token)
    a = _read(client, stranger, owner_id)
    b = _read(client, stranger, str(uuid.uuid4()))
    assert a.status_code == b.status_code == 404
    assert a.json()["error"]["code"] == b.json()["error"]["code"] == "not_found"


def test_not_yet_valid(client, pair):
    owner, owner_id, viewer, _, viewer_email, _ = pair
    _share(client, owner, viewer_email, starts_at=(datetime.now(UTC) + timedelta(days=1)).isoformat())
    res = _read(client, viewer, owner_id)
    assert (res.status_code, res.json()["error"]["code"]) == (403, "not_yet_valid")


@pytest.mark.anyio
async def test_expired(client, pair, pg):
    owner, owner_id, viewer, _, viewer_email, _ = pair
    share = _share(client, owner, viewer_email).json()
    await pg.execute(
        "UPDATE vitallink.consents SET starts_at = now() - interval '2 days', "
        "expires_at = now() - interval '1 second' WHERE id = $1",
        uuid.UUID(share["id"]),
    )
    res = _read(client, viewer, owner_id)
    assert (res.status_code, res.json()["error"]["code"]) == (403, "expired")
    assert client.get("/v1/me/shares", headers=owner).json()[0]["active"] is False
    # An expired share doesn't block sharing again with the same person.
    assert _share(client, owner, viewer_email).status_code == 201
    assert _read(client, viewer, owner_id).status_code == 200


# ---------------------------------------------------------------- binding


@pytest.mark.anyio
async def test_grant_binds_to_the_account_on_first_read(client, make_token, pair, pg):
    owner, owner_id, viewer, viewer_id, viewer_email, viewer_sub = pair
    share = _share(client, owner, viewer_email).json()
    assert _read(client, viewer, owner_id).status_code == 200
    bound = await pg.fetchval(
        "SELECT grantee_user_id FROM vitallink.consents WHERE id = $1", uuid.UUID(share["id"])
    )
    assert str(bound) == viewer_id

    # The viewer changes their email in Supabase: the share follows the account.
    new_email = _email()
    moved = make_token(email=new_email, subject=viewer_sub)
    assert _read(client, moved, owner_id).status_code == 200

    # Someone who later signs up with the old address gets nothing.
    newcomer, *_ = _account(client, make_token, email=viewer_email)
    assert _read(client, newcomer, owner_id).status_code == 404


@pytest.mark.anyio
async def test_refused_reads_are_audited_despite_the_rollback(client, pair, pg):
    _, owner_id, viewer, viewer_id, *_ = pair
    assert _read(client, viewer, owner_id).status_code == 404
    count = await pg.fetchval(
        "SELECT count(*) FROM vitallink.audit_log WHERE actor_user_id = $1 AND subject_user_id = $2 "
        "AND outcome = 'blocked' AND reason = 'no_grant'",
        uuid.UUID(viewer_id),
        uuid.UUID(owner_id),
    )
    assert count == 1


# ---------------------------------------------------------------- creating shares


def test_cannot_share_twice_or_with_self(client, pair):
    owner, _, _, _, viewer_email, _ = pair
    assert _share(client, owner, viewer_email).status_code == 201
    assert _share(client, owner, viewer_email.upper()).status_code == 409
    me = client.get("/v1/me", headers=owner).json()
    assert _share(client, owner, me["email"]).json()["error"]["code"] == "cannot_share_with_self"


@pytest.mark.parametrize(
    "body",
    [
        {"kind": "hospital"},  # consumers only
        {"kind": "app"},  # no third parties
        {"scopes": []},
        {"scopes": ["blood_type"]},
        {"email": "not-an-email"},
        {"expires_at": "2020-01-01T00:00:00Z"},
        {"grantee_user_id": str(uuid.uuid4())},  # can't bind from the client
    ],
)
def test_share_validation(client, pair, body):
    owner, _, _, _, viewer_email, _ = pair
    full = {"email": viewer_email, "label": "Dr. Bennett", "kind": "doctor", "scopes": ["activity"]} | body
    assert client.post("/v1/me/shares", headers=owner, json=full).status_code == 422


def test_owner_cannot_revoke_someone_elses_share(client, make_token, pair):
    owner, _, viewer, _, viewer_email, _ = pair
    share = _share(client, owner, viewer_email).json()
    assert client.delete(f"/v1/me/shares/{share['id']}", headers=viewer).status_code == 404


# ---------------------------------------------------------------- guardrails


def _all_routes(routes=None, prefix=""):
    """(full path, route) for every endpoint, through included routers (FastAPI keeps them nested)."""
    for r in app.routes if routes is None else routes:
        context = getattr(r, "include_context", None)
        if context is not None:
            yield from _all_routes(context.included_router.routes, prefix + context.prefix)
        elif hasattr(r, "path"):
            yield prefix + r.path, r


def _shared():
    return [(p, r) for p, r in _all_routes() if p.startswith("/v1/users/")]


def test_every_shared_route_passes_the_consent_gate():
    from app.modules.sharing.service import shared_access

    assert len(_shared()) >= 4, "shared routes not found"
    for path, route in _shared():
        deps = {d.call for d in route.dependant.dependencies}
        assert shared_access in deps, f"{path} skips the consent gate"


def test_shared_routes_are_read_only():
    for path, route in _shared():
        assert route.methods <= {"GET", "HEAD"}, f"{path} allows {route.methods}"


def test_no_provider_or_admin_routes():
    banned = ("hospital", "clinic", "provider", "admin")
    paths = [p for p, _ in _all_routes()]
    assert len(paths) > 20
    assert not [p for p in paths if any(word in p.lower() for word in banned)]
