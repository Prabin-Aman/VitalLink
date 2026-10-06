"""Training: workouts with sets, synced upserts, the weekly view, personal records, plans."""

import uuid
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from app.modules.training.catalogue import EXERCISES

CHICAGO = ZoneInfo("America/Chicago")


def _at(days_ago: int, hour: int = 18) -> str:
    day = datetime.now(CHICAGO).date() - timedelta(days=days_ago)
    return datetime.combine(day, time(hour), CHICAGO).isoformat()


def strength(days_ago=0, squat=80.0, **kw):
    return {
        "type": "strength",
        "title": "Lower Body Strength",
        "started_at": _at(days_ago, kw.pop("hour", 7)),
        "duration_s": 3000,
        "sets": [
            {"exercise": "Back Squat", "reps": 5, "weight_kg": squat},
            {"exercise": "Back Squat", "reps": 5, "weight_kg": squat},
            {"exercise": "Back Squat", "reps": 5, "weight_kg": squat},
            {"exercise": "Plank", "duration_s": 60},
        ],
    } | kw


@pytest.fixture
def user(client, make_token):
    headers = make_token()
    client.get("/v1/me", headers=headers)
    return headers


def _post(client, headers, body):
    return client.post("/v1/me/workouts", headers=headers, json=body)


@pytest.mark.anyio
async def test_exercise_catalogue_matches_database(pg):
    rows = {
        (r["id"], r["name"], r["primary_muscle"]) for r in await pg.fetch("SELECT * FROM vitallink.exercises")
    }
    assert rows == {(i, n, m.value) for i, n, m in EXERCISES}


def test_log_strength_workout(client, user):
    res = _post(client, user, strength())
    assert res.status_code == 201, res.text
    w = res.json()
    assert w["kind"] == "strength"
    assert [(s["exercise"], s["set_no"]) for s in w["sets"]] == [
        ("Back Squat", 1),
        ("Back Squat", 2),
        ("Back Squat", 3),
        ("Plank", 1),
    ]
    assert client.get(f"/v1/me/workouts/{w['id']}", headers=user).json() == w


@pytest.mark.parametrize(
    "change",
    [
        {"sets": [{"exercise": "Blood Donation", "reps": 1}]},
        {"sets": [{"exercise": "Back Squat"}]},  # neither reps nor time
        {"source": "healthkit"},  # synced without a platform id
        {"external_id": "abc"},  # manual with a platform id
        {"duration_s": 0},
        {"avg_hr": 400},
        {"zone_seconds": [1, 2, 3]},
        {"user_id": str(uuid.uuid4())},
    ],
)
def test_rejects(client, user, change):
    assert _post(client, user, strength() | change).status_code == 422


def test_synced_workout_is_updated_not_duplicated(client, user):
    run = {
        "type": "run",
        "title": "Outdoor Run",
        "started_at": _at(1, 6),
        "duration_s": 1800,
        "distance_m": 5000,
        "source": "healthkit",
        "external_id": "hk-123",
    }
    first = _post(client, user, run)
    again = _post(client, user, run | {"distance_m": 5100, "kcal": 340})
    assert (first.status_code, again.status_code) == (201, 200)
    assert again.json()["id"] == first.json()["id"]
    listed = client.get("/v1/me/workouts?days=7", headers=user).json()
    assert [(w["distance_m"], w["kcal"]) for w in listed] == [(5100.0, 340)]


def test_training_view(client, user):
    _post(client, user, strength(days_ago=3, squat=80))
    _post(client, user, strength(days_ago=1, squat=85))  # heavier squat → PR
    _post(client, user, {"type": "run", "title": "Run", "started_at": _at(0, 6), "duration_s": 1500,
                         "distance_m": 5000, "avg_hr": 160})  # fmt: skip
    t = client.get("/v1/me/training", headers=user).json()

    assert t["total_sessions"] == 3
    sessions = {s["started_at"][:10]: s for s in t["sessions"]}
    squat_day = sessions[_at(1)[:10]]
    assert squat_day["pr"] == {"exercise": "Back Squat", "weight_kg": 85.0}
    assert squat_day["volume_kg"] == 1275.0
    assert (squat_day["exercises"], squat_day["sets"]) == (2, 4)
    assert sessions[_at(3)[:10]]["pr"] is None  # the first time isn't a record
    run = sessions[_at(0)[:10]]
    assert run["pace_s_per_km"] == 300
    assert run["zone"] in (4, 5)

    assert t["records"][0]["exercise"] == "Back Squat" and t["records"][0]["weight_kg"] == 85.0
    assert t["records"][-1] == t["records"][-1] | {"exercise": "5 km run", "time_s": 1500}
    assert t["week"]["streak_days"] == 2  # today and yesterday; three days ago breaks it
    assert t["week"]["target_sessions"] == 5
    assert [g["group"] for g in t["muscles"]["groups"]] == ["Legs", "Core"]
    assert t["muscles"]["note"] == "Core is trailing. Two accessory sets would even this out."
    assert len(t["week"]["load"]) == 7
    assert t["next"] is None


def test_plans(client, user):
    tomorrow = _at(-1, 18)
    first = client.post(
        "/v1/me/training/plans",
        headers=user,
        json={
            "name": "Push Pull Legs",
            "weekly_target_sessions": 4,
            "sessions": [
                {
                    "title": "Push Day",
                    "kind": "strength",
                    "scheduled_for": tomorrow,
                    "exercises": 6,
                    "minutes": 50,
                }
            ],
        },  # fmt: skip
    )
    assert first.status_code == 201, first.text
    t = client.get("/v1/me/training", headers=user).json()
    assert t["week"]["target_sessions"] == 4
    assert (t["next"]["title"], t["next"]["kind"], t["next"]["minutes"]) == ("Push Day", "Strength", 50)

    client.post("/v1/me/training/plans", headers=user, json={"name": "Base", "weekly_target_sessions": 3})
    plans = client.get("/v1/me/training/plans", headers=user).json()
    assert [(p["name"], p["status"]) for p in plans] == [("Base", "active"), ("Push Pull Legs", "archived")]


def test_delete_and_isolation(client, user, make_token):
    w = _post(client, user, strength()).json()
    other = make_token()
    assert client.get(f"/v1/me/workouts/{w['id']}", headers=other).status_code == 404
    assert client.delete(f"/v1/me/workouts/{w['id']}", headers=other).status_code == 404
    assert client.delete(f"/v1/me/workouts/{w['id']}", headers=user).status_code == 204
    assert client.get("/v1/me/workouts", headers=user).json() == []


def test_cannot_link_another_users_plan_session(client, user, make_token):
    other = make_token()
    client.get("/v1/me", headers=other)
    plan = client.post(
        "/v1/me/training/plans",
        headers=other,
        json={
            "name": "Theirs",
            "weekly_target_sessions": 3,
            "sessions": [{"title": "Leg Day", "kind": "strength", "scheduled_for": _at(-1), "minutes": 40}],
        },
    ).json()
    theirs = plan["sessions"][0]["id"]
    res = _post(client, user, strength() | {"plan_session_id": theirs})
    assert res.status_code == 404
    mine = _post(client, other, strength() | {"plan_session_id": theirs})
    assert mine.status_code == 201


def test_exercises_listed(client, user):
    names = [e["name"] for e in client.get("/v1/exercises", headers=user).json()]
    assert "Back Squat" in names and len(names) == len(EXERCISES)
