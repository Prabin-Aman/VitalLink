"""Account API: first-request creation, profile and targets (docs/05-api.md, Account)."""

import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest


def test_first_call_creates_the_account(client, make_token):
    headers = make_token(first_name="Alex", last_name="Carter")
    me = client.get("/v1/me", headers=headers).json()
    assert me["display_name"] == "Alex Carter"
    assert me["timezone"] == "America/Chicago"
    assert uuid.UUID(me["id"]).version == 7
    # Same token again → same account.
    assert client.get("/v1/me", headers=headers).json()["id"] == me["id"]


def test_email_is_stored_lowercase(client, make_token):
    tag = uuid.uuid4().hex[:8]
    me = client.get("/v1/me", headers=make_token(email=f"Mixed.Case.{tag}@Example.com")).json()
    assert me["email"] == f"mixed.case.{tag}@example.com"


def test_parallel_first_requests_create_one_account(client, make_token):
    headers = make_token()
    with ThreadPoolExecutor(max_workers=6) as pool:
        responses = list(pool.map(lambda _: client.get("/v1/me", headers=headers), range(6)))
    assert {r.status_code for r in responses} == {200}
    assert len({r.json()["id"] for r in responses}) == 1


def test_same_email_new_identity_is_409(client, make_token):
    email = f"dup-{uuid.uuid4().hex[:8]}@example.com"
    assert client.get("/v1/me", headers=make_token(email=email)).status_code == 200
    res = client.get("/v1/me", headers=make_token(email=email))
    assert res.status_code == 409
    assert res.json()["error"]["code"] == "email_in_use"


def test_new_account_defaults(client, make_token):
    account = client.get("/v1/me/account", headers=make_token()).json()
    assert account["first_name"] is None
    assert account["units"] == "imperial"
    assert account["theme"] == "system"
    assert account["language"] == "en-US"
    assert account["two_factor"] is False


def test_patch_account(client, make_token):
    headers = make_token()
    body = {
        "first_name": " Alex ",
        "last_name": "Carter",
        "phone": "+12145550148",
        "zip": "75201",
        "city": "Dallas, TX",
        "height_cm": 178,
        "date_of_birth": "1994-04-17",
        "units": "metric",
        "theme": "light",
        "timezone": "America/New_York",
    }
    res = client.patch("/v1/me/account", headers=headers, json=body)
    assert res.status_code == 200, res.text
    a = res.json()
    assert (a["first_name"], a["phone"], a["height_cm"], a["units"]) == (
        "Alex",
        "+12145550148",
        178.0,
        "metric",
    )
    assert client.get("/v1/me", headers=headers).json()["timezone"] == "America/New_York"
    # null clears an optional field
    assert client.patch("/v1/me/account", headers=headers, json={"phone": None}).json()["phone"] is None


@pytest.mark.parametrize(
    "body",
    [
        {"email": "new@example.com"},  # email changes go through Supabase
        {"id": str(uuid.uuid4())},
        {"member_since": "2020-01-01T00:00:00Z"},
        {"zip": "7520"},
        {"phone": "214-555-0148"},
        {"height_cm": 300},
        {"date_of_birth": "2999-01-01"},
        {"timezone": "Mars/Olympus"},
        {"language": "fr-FR"},
        {"units": None},
    ],
)
def test_patch_account_rejects(client, make_token, body):
    res = client.patch("/v1/me/account", headers=make_token(), json=body)
    assert res.status_code == 422
    assert res.json()["error"]["code"] == "invalid_request"


def test_targets(client, make_token):
    headers = make_token()
    assert client.get("/v1/me/targets", headers=headers).json()["daily_steps_goal"] == 10000
    res = client.patch(
        "/v1/me/targets", headers=headers, json={"daily_steps_goal": 8000, "stand_hours_goal": 10}
    )
    assert res.status_code == 200
    assert res.json()["daily_steps_goal"] == 8000
    assert client.patch("/v1/me/targets", headers=headers, json={"sleep_goal_min": 60}).status_code == 422


def test_users_cannot_see_each_other(client, make_token):
    a, b = make_token(), make_token()
    client.patch("/v1/me/account", headers=a, json={"first_name": "Ada"})
    assert client.get("/v1/me/account", headers=b).json()["first_name"] is None


@pytest.mark.anyio
async def test_deleting_a_user_cascades_to_profile(client, make_token, pg):
    me = client.get("/v1/me", headers=make_token()).json()
    await pg.execute("DELETE FROM vitallink.users WHERE id = $1", uuid.UUID(me["id"]))
    assert (
        await pg.fetchval("SELECT count(*) FROM vitallink.profiles WHERE user_id = $1", uuid.UUID(me["id"]))
        == 0
    )
