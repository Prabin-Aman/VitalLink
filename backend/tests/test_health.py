"""Health core: catalogue, ingest, manual readings, series, latest, dashboard, devices."""

import uuid
from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from app.modules.health.catalogue import METRICS
from app.modules.health.service import bp_category

CHICAGO = ZoneInfo("America/Chicago")


def _bucket(dt: datetime) -> str:
    """Floor to a 5-minute boundary, ISO with offset."""
    ts = int(dt.timestamp()) // 300 * 300
    return datetime.fromtimestamp(ts, CHICAGO).isoformat()


def _today_at(hour: int, minute: int = 0, days_ago: int = 0) -> datetime:
    day = datetime.now(CHICAGO).date() - timedelta(days=days_ago)
    return datetime.combine(day, time(hour, minute), CHICAGO)


def _ingest(client, headers, buckets, key=None, devices=(), platform="healthkit"):
    body = {
        "idempotency_key": str(key or uuid.uuid4()),
        "platform": platform,
        "devices": list(devices),
        "buckets": buckets,
    }
    return client.post("/v1/me/health/ingest", headers=headers, json=body)


def b(metric, when, value, samples=1):
    return {"metric": metric, "start": _bucket(when), "value": value, "samples": samples}


@pytest.fixture
def user(client, make_token):
    headers = make_token()
    assert client.get("/v1/me", headers=headers).status_code == 200
    return headers


# ---------------------------------------------------------------- catalogue


@pytest.mark.anyio
async def test_database_catalogue_matches_code(pg):
    rows = {r["id"]: r for r in await pg.fetch("SELECT * FROM vitallink.metrics")}
    assert set(rows) == {m.id for m in METRICS}
    for m in METRICS:
        r = rows[m.id]
        stored = (r["code"], r["scope"], r["unit"], r["aggregation"], r["min_value"], r["max_value"])
        assert stored == (m.code, m.scope.value, m.unit, m.aggregation.value, m.min_value, m.max_value)
        assert (r["manual_allowed"], r["normal_low"], r["normal_high"]) == (
            m.manual_allowed,
            m.normal_low,
            m.normal_high,
        )


def test_catalogue_has_no_blood_features():
    banned = ("blood_type", "blood_group", "donor", "blood_bank")
    assert not [m.code for m in METRICS if any(word in m.code for word in banned)]


# ---------------------------------------------------------------- ingest


def test_ingest_inserts_then_updates_then_is_unchanged(client, user):
    when = _today_at(8, 40)
    first = _ingest(client, user, [b("steps", when, 400), b("heart_rate", when, 71.5, 60)])
    assert first.status_code == 202, first.text
    assert first.json() | {"batch_id": 0} == {
        "batch_id": 0,
        "submitted": 2,
        "inserted": 2,
        "updated": 0,
        "unchanged": 0,
        "replayed": False,
    }
    # Late watch data corrects the bucket: the new value wins.
    second = _ingest(client, user, [b("steps", when, 412), b("heart_rate", when, 71.5, 60)]).json()
    assert (second["inserted"], second["updated"], second["unchanged"]) == (0, 1, 1)
    series = client.get("/v1/me/health/series/steps?days=1", headers=user).json()
    assert sum(p["value"] for p in series["points"]) == 412


def test_same_key_same_payload_replays(client, user):
    key, buckets = uuid.uuid4(), [b("steps", _today_at(9), 100)]
    first = _ingest(client, user, buckets, key).json()
    again = _ingest(client, user, buckets, key)
    assert again.status_code == 202
    assert again.json() == first | {"replayed": True}


def test_same_key_different_payload_is_409(client, user):
    key = uuid.uuid4()
    _ingest(client, user, [b("steps", _today_at(9), 100)], key)
    res = _ingest(client, user, [b("steps", _today_at(9), 101)], key)
    assert res.status_code == 409
    assert res.json()["error"]["code"] == "idempotency_conflict"


def test_keys_are_per_user(client, make_token):
    key = uuid.uuid4()
    a, other = make_token(), make_token()
    assert _ingest(client, a, [b("steps", _today_at(9), 1)], key).status_code == 202
    assert _ingest(client, other, [b("steps", _today_at(9), 2)], key).json()["replayed"] is False


@pytest.mark.parametrize(
    ("bucket", "problem"),
    [
        ({"metric": "blood_type", "value": 1}, "unknown metric"),
        ({"metric": "heart_rate", "value": 400}, "value outside"),
        ({"metric": "steps", "value": -1}, "value outside"),
        ({"metric": "steps", "value": 1, "shift": timedelta(minutes=2)}, "5-minute boundary"),
        ({"metric": "steps", "value": 1, "shift": timedelta(hours=2)}, "future"),
    ],
)
def test_invalid_buckets_reject_the_whole_batch(client, user, bucket, problem):
    start = datetime.fromtimestamp(int(_today_at(0).timestamp()), CHICAGO) + bucket.pop("shift", timedelta())
    if problem == "future":
        start = datetime.fromtimestamp(int(datetime.now(UTC).timestamp()) // 300 * 300, CHICAGO) + timedelta(
            hours=2
        )
    bad = {"metric": bucket["metric"], "start": start.isoformat(), "value": bucket["value"]}
    res = _ingest(client, user, [b("steps", _today_at(1), 5), bad])
    assert res.status_code == 422
    error = res.json()["error"]
    assert error["code"] == "invalid_buckets"
    assert problem in error["detail"][0]["problem"]
    assert error["detail"][0]["index"] == 1
    # nothing was saved, not even the valid bucket
    points = client.get("/v1/me/health/series/steps?days=1", headers=user).json()["points"]
    assert sum(p["value"] for p in points) == 0


def test_duplicate_bucket_in_one_batch_is_rejected(client, user):
    when = _today_at(10)
    res = _ingest(client, user, [b("steps", when, 1), b("steps", when, 2)])
    assert res.status_code == 422


def test_oversized_batch_is_413(client, user):
    start = _today_at(0, days_ago=30)
    buckets = [b("steps", start + timedelta(minutes=5 * i), 1) for i in range(5001)]
    assert _ingest(client, user, buckets).status_code == 413


# ---------------------------------------------------------------- series


def test_day_series_fills_gaps_in_local_time(client, user):
    _ingest(
        client, user, [b("steps", _today_at(9, days_ago=2), 3000), b("steps", _today_at(23, 55, 2), 1000)]
    )
    points = client.get("/v1/me/health/series/steps?days=7", headers=user).json()["points"]
    assert len(points) == 7
    # buckets are local midnights in Chicago
    assert all(datetime.fromisoformat(p["bucket"]).astimezone(CHICAGO).time() == time() for p in points)
    assert [p["value"] for p in points] == [0, 0, 0, 0, 4000, 0, 0]


def test_average_series_skips_empty_buckets(client, user):
    _ingest(client, user, [b("heart_rate", _today_at(8), 60), b("heart_rate", _today_at(9), 80)])
    res = client.get("/v1/me/health/series/heart_rate?days=7", headers=user).json()
    assert res["unit"] == "bpm"
    assert [p["value"] for p in res["points"]] == [70]


def test_week_series(client, user):
    _ingest(client, user, [b("distance", _today_at(7), 1000), b("distance", _today_at(7, days_ago=1), 500)])
    points = client.get("/v1/me/health/series/distance?days=7&interval=week", headers=user).json()["points"]
    assert sum(p["value"] for p in points) == 1500
    assert all(datetime.fromisoformat(p["bucket"]).astimezone(CHICAGO).weekday() == 0 for p in points)


def test_unknown_metric_series_is_404(client, user):
    assert client.get("/v1/me/health/series/blood_type", headers=user).status_code == 404


def test_blood_pressure_pairs(client, user):
    when = _today_at(8, 40)
    _ingest(client, user, [b("bp_systolic", when, 118), b("bp_diastolic", when, 76)])
    bp = client.get("/v1/me/health/series/blood_pressure?days=7", headers=user).json()
    assert [p["value"] for p in bp["systolic"]["points"]] == [118]
    assert [p["value"] for p in bp["diastolic"]["points"]] == [76]
    latest = client.get("/v1/me/health/latest?metrics=blood_pressure,heart_rate", headers=user).json()
    assert latest == [
        {
            "metric": "blood_pressure",
            "value": 118,
            "value2": 76,
            "unit": "mmHg",
            "at": latest[0]["at"],
            "assessment": "Normal",
        }
    ]


@pytest.mark.parametrize(
    ("sys", "dia", "category"),
    [(118, 76, "Normal"), (124, 78, "Elevated"), (132, 78, "High (stage 1)"), (118, 92, "High (stage 2)")],
)
def test_bp_categories(sys, dia, category):
    assert bp_category(sys, dia)[0] == category


# ---------------------------------------------------------------- manual


def test_manual_reading(client, user):
    res = client.post("/v1/me/health/manual", headers=user, json={"metric": "weight", "value": 72.4})
    assert res.status_code == 201
    assert res.json()["unit"] == "kg"
    assert client.get("/v1/me/health/latest?metrics=weight", headers=user).json()[0]["value"] == 72.4


@pytest.mark.parametrize(
    "body",
    [
        {"metric": "steps", "value": 100},
        {"metric": "weight", "value": 1000},
        {"metric": "weight", "value": 70, "x": 1},
    ],
)
def test_manual_rejects(client, user, body):
    assert client.post("/v1/me/health/manual", headers=user, json=body).status_code == 422


# ---------------------------------------------------------------- dashboard


def test_dashboard_without_data(client, user):
    dash = client.get("/v1/me/health/dashboard", headers=user).json()
    assert dash["health_score"] is None
    assert [m["metric"] for m in dash["metrics"]] == [
        "heart_rate",
        "blood_pressure",
        "oxygen_saturation",
        "weight",
        "glucose",
    ]
    assert {m["range"] for m in dash["metrics"]} == {"no-data"}


def test_dashboard_with_data(client, user):
    now = datetime.now(CHICAGO) - timedelta(minutes=10)
    buckets = [b("heart_rate", now, 72), b("oxygen_saturation", now, 93), b("glucose", now, 92)]
    buckets += [b("bp_systolic", now, 132), b("bp_diastolic", now, 78)]
    # readiness: 30-day baselines plus last night's sleep
    for d in range(1, 15):
        buckets.append(b("resting_heart_rate", _today_at(6, days_ago=d), 60))
        buckets.append(b("hrv", _today_at(6, days_ago=d), 50))
    buckets.append(b("resting_heart_rate", _today_at(0, 5), 60))
    buckets.append(b("hrv", _today_at(0, 5), 50))
    night = _today_at(23, days_ago=1)
    buckets += [b("sleep_asleep", night + timedelta(minutes=5 * i), 5) for i in range(96)]  # 8 h
    assert _ingest(client, user, buckets).status_code == 202

    dash = client.get("/v1/me/health/dashboard", headers=user).json()
    cards = {m["metric"]: m for m in dash["metrics"]}
    assert (cards["heart_rate"]["latest"], cards["heart_rate"]["range"]) == (72, "normal")
    assert cards["oxygen_saturation"]["range"] == "low"
    assert cards["blood_pressure"]["range"] == "high"
    assert cards["weight"]["range"] == "no-data"
    assert dash["health_score"] == 100


# ---------------------------------------------------------------- devices


def test_devices_registry(client, user):
    watch = {
        "external_id": "watch-1",
        "name": "Apple Watch S9",
        "kind": "wearable",
        "metrics": ["heart_rate", "steps", "sleep_deep", "oxygen_saturation"],
    }
    cuff = {"external_id": "cuff-1", "name": "Omron BP Monitor", "kind": "home_monitor",
            "metrics": ["bp_systolic", "bp_diastolic", "heart_rate"]}  # fmt: skip
    _ingest(client, user, [b("steps", _today_at(1), 1)], devices=[watch, cuff])
    devices = {d["name"]: d for d in client.get("/v1/me/devices", headers=user).json()}
    assert devices["Apple Watch S9"]["data"] == ["Heart rate", "Blood oxygen", "Activity", "Sleep"]
    assert devices["Apple Watch S9"]["short_data"] == ["Heart rate", "Blood oxygen", "Activity"]
    assert (devices["Omron BP Monitor"]["icon"], devices["Omron BP Monitor"]["data"]) == (
        "monitor",
        ["Pulse", "Blood pressure"],
    )
    assert {d["status"] for d in devices.values()} == {"connected"}

    gone = devices["Omron BP Monitor"]["id"]
    assert client.delete(f"/v1/me/devices/{gone}", headers=user).status_code == 204
    assert client.delete(f"/v1/me/devices/{gone}", headers=user).status_code == 404


# ---------------------------------------------------------------- isolation


def test_users_never_see_each_others_readings(client, make_token):
    a, other = make_token(), make_token()
    _ingest(client, a, [b("steps", _today_at(1), 999)])
    points = client.get("/v1/me/health/series/steps?days=1", headers=other).json()["points"]
    assert sum(p["value"] for p in points) == 0
    assert client.get("/v1/me/devices", headers=other).json() == []
