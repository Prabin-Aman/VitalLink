"""Demo account with 90 days of realistic health data (docs/08 §8.7, "Seed data").

    .venv/Scripts/python -m seeds.demo                      # demo@example.com
    .venv/Scripts/python -m app.devtoken demo@example.com   # token for that account

The last week reproduces the Figma sample values (steps 9,240 … 8,412, BP 118/76, weight 72.4 kg),
ending today. Re-running replaces the demo account's readings and devices. Refused in production.

Planted pattern for the insight engine: more evening screen time → less deep sleep that night.
"""

import argparse
import asyncio
import math
import random
import uuid
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import asyncpg
from sqlalchemy.engine import make_url

from app.core.ids import uuid7
from app.core.settings import get_settings
from app.modules.health.catalogue import BY_CODE
from app.modules.training.catalogue import BY_NAME as EXERCISE_IDS

TZ = ZoneInfo("America/Chicago")
DAYS = 90
FIGMA_STEPS = [9240, 6810, 11430, 7520, 12890, 4610, 8412]  # last 7 days, today last
FIGMA_BP = [(121, 77), (118, 75), (125, 80), (116, 74), (120, 78), (117, 76), (118, 76)]

Row = tuple[int, datetime, float, int]  # metric id, bucket start (UTC), value, samples


def at(day: date, hour: int, minute: int = 0) -> datetime:
    return datetime.combine(day, time(hour, minute), TZ)


def slots(start: datetime, end: datetime):
    """5-minute bucket starts in [start, end)."""
    t = datetime.fromtimestamp(int(start.timestamp()) // 300 * 300, UTC)
    while t < end:
        yield t
        t += timedelta(minutes=5)


class Builder:
    def __init__(self, now: datetime, rng: random.Random) -> None:
        self.now, self.rng, self.rows = now, rng, {}

    def add(self, code: str, when: datetime, value: float, samples: int = 1) -> None:
        if when > self.now:
            return
        m = BY_CODE[code]
        value = min(max(value, m.min_value), m.max_value)
        t = datetime.fromtimestamp(int(when.timestamp()) // 300 * 300, UTC)
        self.rows[(m.id, t)] = (m.id, t, round(value, 2), samples)

    def day(self, day: date, steps_total: int, bp: tuple[int, int], weight: float) -> None:
        rng, add = self.rng, self.add
        # Evening screen time drives tonight's deep sleep (the planted pattern).
        late_screen = rng.uniform(20, 140)

        # Activity 07:00–21:00, weighted towards a morning walk and the evening.
        active = list(slots(at(day, 7), at(day, 21)))
        weights = [1 + 3 * math.exp(-((t.astimezone(TZ).hour - 7.5) ** 2)) + rng.random() for t in active]
        total_w = sum(weights)
        for t, w in zip(active, weights, strict=True):
            steps = steps_total * w / total_w
            add("steps", t, steps, 3)
            add("distance", t, steps * 0.76, 3)
            add("active_energy", t, steps * 0.045 + rng.uniform(0, 0.4), 3)
            if rng.random() < 0.03:
                add("flights_climbed", t, rng.randint(1, 3))
        # One 30–45 minute workout, around the morning walk.
        for t in slots(at(day, 7, 10), at(day, 7, 10) + timedelta(minutes=rng.choice([30, 35, 40, 45]))):
            add("exercise_minutes", t, 5)
        for hour in range(8, 21):
            if rng.random() < 0.8:
                add("stand_hours", at(day, hour, 5), 1)

        # Heart rate every 5 minutes; lower overnight.
        for t in slots(at(day, 0), at(day, 0) + timedelta(days=1)):
            hour = t.astimezone(TZ).hour
            base = 56 if hour < 6 or hour >= 23 else 72
            add("heart_rate", t, base + rng.gauss(0, 4), 60)
        add("resting_heart_rate", at(day, 6), 58 + rng.gauss(0, 1.5))
        add("hrv", at(day, 6), 52 + rng.gauss(0, 5))
        add("respiratory_rate", at(day, 5), 14.5 + rng.gauss(0, 0.6))

        # Vitals from home devices.
        add("bp_systolic", at(day, 8, 40), bp[0], 1)
        add("bp_diastolic", at(day, 8, 40), bp[1], 1)
        add("weight", at(day, 7, 30), weight)
        if day.weekday() == 0:
            add("body_fat", at(day, 7, 30), 18.4 + rng.gauss(0, 0.2))
        for hour in range(0, 24, 2):
            add("oxygen_saturation", at(day, hour, 40), rng.choice([96, 97, 98, 98, 99]))
        for t in slots(at(day, 0), at(day, 0) + timedelta(days=1)):  # CGM
            h = t.astimezone(TZ).hour + t.astimezone(TZ).minute / 60
            meals = sum(35 * math.exp(-((h - m) ** 2) / 0.8) for m in (8, 12.5, 19))
            add("glucose", t, 88 + meals + rng.gauss(0, 3))

        # Screen time: daytime plus the evening amount above.
        for t in slots(at(day, 9), at(day, 22)):
            per = (late_screen if t.astimezone(TZ).hour >= 20 else 160) / (
                26 if t.astimezone(TZ).hour >= 20 else 132
            )
            per = min(5, per * rng.uniform(0.5, 1.5))
            add("screen_time", t, per)
            add("screen_time_social", t, per * 0.4)
            add("screen_time_entertainment", t, per * 0.35)
            add("screen_time_productivity", t, per * 0.25)
            if rng.random() < 0.25:
                add("phone_pickups", t, rng.randint(1, 3))
                add("notifications_received", t, rng.randint(1, 6))

        # Tonight's sleep, 23:00 → about 06:50; deep sleep falls as evening screen time rises.
        deep_share = max(0.08, 0.24 - late_screen / 1000)
        start = at(day, 23, rng.choice([0, 5, 10, 15]))
        for i, t in enumerate(slots(start, start + timedelta(minutes=rng.randint(440, 480)))):
            cycle = (i % 18) / 18  # ~90-minute cycles
            if rng.random() < 0.04:
                add("sleep_awake", t, 5)
                continue
            add("sleep_asleep", t, 5)
            stage = (
                "sleep_deep"
                if cycle < deep_share * (1.6 if i < 54 else 0.4)
                else ("sleep_rem" if cycle > 0.78 else "sleep_core")
            )
            add(stage, t, 5)
        if rng.random() < 0.5:
            add("mood", at(day, 21), rng.choice([3, 4, 4, 5]))
            add("mindful_minutes", at(day, 7, 15), 5)


# ---------------------------------------------------------------- workouts and a plan

# The Figma week's pattern, Monday first: (title, type, kind, minutes, hour, sets template or None).
UPPER = [
    ("Bench Press", 4, 8, 70.0),
    ("Barbell Row", 4, 8, 65.0),
    ("Overhead Press", 4, 8, 42.5),
    ("Lat Pulldown", 4, 10, 55.0),
    ("Bicep Curl", 4, 12, 14.0),
    ("Tricep Extension", 4, 12, 20.0),
]
LOWER = [
    ("Back Squat", 5, 5, None),
    ("Romanian Deadlift", 4, 8, 80.0),
    ("Lunge", 4, 10, 24.0),
    ("Calf Raise", 4, 15, 40.0),
    ("Plank", 3, None, None),
]
WEEK = {
    0: [("Lower Body Strength", "strength", "strength", 48, 19, LOWER), ("Mobility & Stretch", "yoga", "mobility", 10, 21, None)],
    1: [("Outdoor Run", "run", "cardio", 28, 6, None), ("Mobility & Stretch", "yoga", "mobility", 12, 21, None)],
    2: [("Upper Body Strength", "strength", "strength", 52, 19, UPPER), ("Mobility & Stretch", "yoga", "mobility", 8, 21, None)],
    4: [("Indoor Cycle", "cycle", "cardio", 35, 18, None)],
    5: [("Outdoor Run", "run", "cardio", 42, 7, None), ("Mobility & Stretch", "yoga", "mobility", 15, 20, None)],
    6: [("Mobility & Stretch", "yoga", "mobility", 20, 9, None)],
}  # fmt: skip


def workouts(today: date, now: datetime, rng: random.Random) -> list[dict]:
    """Workouts over the seeded days; the squat climbs 0.5 kg a week to 92.5 kg (a record last Monday)."""
    out = []
    for offset in range(DAYS - 1, -1, -1):
        day = today - timedelta(days=offset)
        weeks_ago = offset // 7
        for title, wtype, kind, minutes, hour, template in WEEK.get(day.weekday(), []):
            start = at(day, hour, rng.choice([0, 5, 10]))
            if start > now:
                continue
            sets = []
            for exercise, n, reps, weight in template or []:
                if exercise == "Back Squat":
                    weight = 92.5 - 0.5 * weeks_ago
                for _ in range(n):
                    sets.append((exercise, reps, weight, 45 if reps is None else None))
            run = wtype == "run"
            distance = (
                (minutes * 60 / rng.uniform(330, 350)) * 1000
                if run
                else (minutes * 430 if wtype == "cycle" else None)
            )
            out.append(
                {
                    "title": title,
                    "type": wtype,
                    "kind": kind,
                    "start": start,
                    "duration_s": minutes * 60,
                    "distance_m": round(distance, 1) if distance else None,
                    "kcal": round(minutes * (8.5 if kind == "cardio" else 5.5))
                    if kind != "mobility"
                    else None,
                    "avg_hr": rng.randint(150, 160)
                    if run
                    else (rng.randint(132, 142) if wtype == "cycle" else None),
                    "rpe": rng.choice([7, 8]) if kind == "strength" else None,
                    "focus": "Hips, thoracic, hamstrings" if kind == "mobility" else None,
                    "sets": sets,
                }
            )
    return out


DEVICES = [
    ("demo-watch", "Apple Watch S9", "wearable", ["heart_rate", "resting_heart_rate", "hrv", "steps", "distance",
     "active_energy", "exercise_minutes", "stand_hours", "flights_climbed", "sleep_asleep", "sleep_deep",
     "sleep_core", "sleep_rem", "sleep_awake", "oxygen_saturation", "respiratory_rate"], timedelta(minutes=9)),
    ("demo-cuff", "Omron BP Monitor", "home_monitor", ["bp_systolic", "bp_diastolic", "heart_rate"],
     timedelta(minutes=3)),
    ("demo-cgm", "Dexcom G7 CGM", "continuous", ["glucose"], timedelta(minutes=3)),
    ("demo-scale", "Withings Scale", "home_monitor", ["weight", "body_fat"], timedelta(hours=25)),
    ("demo-oximeter", "Pulse Oximeter", "home_monitor", ["oxygen_saturation", "heart_rate"], timedelta(days=6)),
]  # fmt: skip


async def seed(email: str) -> None:
    settings = get_settings()
    if settings.env == "production" or not settings.database_url:
        raise SystemExit("The demo seed runs only against a local or test database.")
    url = make_url(settings.database_url).set(drivername="postgresql").render_as_string(hide_password=False)
    subject = uuid.uuid5(uuid.NAMESPACE_URL, f"vitallink-dev:{email.lower()}")  # same as app.devtoken

    now = datetime.now(UTC)
    rng = random.Random(42)  # noqa: S311 - demo data, not security
    builder = Builder(now, rng)
    today = now.astimezone(TZ).date()
    for offset in range(DAYS - 1, -1, -1):
        day = today - timedelta(days=offset)
        last_week = offset < 7
        steps = FIGMA_STEPS[6 - offset] if last_week else rng.randint(5500, 12500)
        bp = FIGMA_BP[6 - offset] if last_week else (rng.randint(114, 126), rng.randint(72, 80))
        weight = 72.4 if offset <= 1 else 72.4 + offset * 0.012 + rng.gauss(0, 0.15)
        builder.day(day, steps, bp, weight)
    rows = list(builder.rows.values())

    conn = await asyncpg.connect(url)
    try:
        async with conn.transaction():
            user_id = await conn.fetchval(
                """
                INSERT INTO vitallink.users (id, auth_subject, email, email_confirmed_at, created_at)
                VALUES ($1, $2, $3, now(), now() - interval '8 months')
                ON CONFLICT (auth_subject) DO UPDATE SET email = EXCLUDED.email
                RETURNING id
                """,
                uuid7(),
                subject,
                email.lower(),
            )
            await conn.execute(
                """
                INSERT INTO vitallink.profiles (user_id, first_name, last_name, date_of_birth, sex, height_cm,
                                                phone, zip, city, units)
                VALUES ($1, 'Alex', 'Carter', '1994-04-17', 'undisclosed', 178, '+12145550148', '75201',
                        'Dallas, TX', 'metric')
                ON CONFLICT (user_id) DO NOTHING
                """,
                user_id,
            )
            await conn.execute("DELETE FROM vitallink.health_readings WHERE user_id = $1", user_id)
            await conn.execute("DELETE FROM vitallink.devices WHERE user_id = $1", user_id)
            for i in range(0, len(rows), 5000):
                chunk = rows[i : i + 5000]
                await conn.execute(
                    """
                    INSERT INTO vitallink.health_readings (user_id, metric_id, recorded_at, source, value, samples)
                    SELECT $1, m, t, 'healthkit', v, s
                    FROM unnest($2::smallint[], $3::timestamptz[], $4::float8[], $5::smallint[]) AS u(m, t, v, s)
                    """,
                    user_id,
                    [r[0] for r in chunk],
                    [r[1] for r in chunk],
                    [r[2] for r in chunk],
                    [r[3] for r in chunk],
                )
            for external_id, name, kind, codes, ago in DEVICES:
                await conn.execute(
                    """
                    INSERT INTO vitallink.devices (id, user_id, platform, external_id, name, kind, metric_ids,
                                                   first_seen_at, last_sync_at)
                    VALUES ($1, $2, 'healthkit', $3, $4, $5, $6, now() - interval '8 months', $7)
                    """,
                    uuid7(),
                    user_id,
                    external_id,
                    name,
                    kind,
                    sorted({BY_CODE[c].id for c in codes}),
                    now - ago,
                )
            await conn.execute("DELETE FROM vitallink.workouts WHERE user_id = $1", user_id)
            await conn.execute("DELETE FROM vitallink.training_plans WHERE user_id = $1", user_id)
            sessions = workouts(today, now, rng)
            for w in sessions:
                workout_id = uuid7()
                await conn.execute(
                    """
                    INSERT INTO vitallink.workouts (id, user_id, type, kind, title, started_at, duration_s, distance_m,
                                                    kcal, avg_hr, rpe, focus, source, external_id)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, 'healthkit', $13)
                    """,
                    workout_id, user_id, w["type"], w["kind"], w["title"], w["start"], w["duration_s"],
                    w["distance_m"], w["kcal"], w["avg_hr"], w["rpe"], w["focus"], f"demo-{workout_id}",
                )  # fmt: skip
                numbering: dict[int, int] = {}
                for exercise, reps, weight, seconds in w["sets"]:
                    exercise_id = EXERCISE_IDS[exercise.lower()][0]
                    numbering[exercise_id] = numbering.get(exercise_id, 0) + 1
                    await conn.execute(
                        "INSERT INTO vitallink.exercise_sets (workout_id, exercise_id, set_no, reps, weight_kg, duration_s) "
                        "VALUES ($1, $2, $3, $4, $5, $6)",
                        workout_id, exercise_id, numbering[exercise_id], reps, weight, seconds,
                    )  # fmt: skip
            plan_id = uuid7()
            await conn.execute(
                "INSERT INTO vitallink.training_plans (id, user_id, name, status, weekly_target_sessions) "
                "VALUES ($1, $2, 'Push Pull Legs', 'active', 5)",
                plan_id, user_id,
            )  # fmt: skip
            for i, (title, minutes, exercises) in enumerate(
                [("Push Day", 50, 6), ("Pull Day", 50, 6), ("Leg Day", 55, 5)]
            ):
                await conn.execute(
                    "INSERT INTO vitallink.plan_sessions (id, plan_id, title, kind, scheduled_for, exercises, minutes) "
                    "VALUES ($1, $2, $3, 'strength', $4, $5, $6)",
                    uuid7(), plan_id, title, at(today + timedelta(days=1 + 2 * i), 18, 30), exercises, minutes,
                )  # fmt: skip
    finally:
        await conn.close()
    print(
        f"Seeded {email}: {len(rows):,} readings, {len(sessions)} workouts over {DAYS} days, {len(DEVICES)} devices."
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed the local demo account.")
    parser.add_argument("--email", default="demo@example.com")
    asyncio.run(seed(parser.parse_args().email))


if __name__ == "__main__":
    main()
