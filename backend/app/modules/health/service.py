"""Health rules: ingest, manual readings, series, latest readings, dashboard, devices.

Aggregation happens in Postgres (never by pulling raw rows into Python), grouped by the user's local
day, hour, week or month (docs/04 §4.1, docs/08 performance).
"""

import hashlib
import uuid
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy import delete, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiError
from app.core.ids import uuid7
from app.modules.account.service import CurrentUser, get_targets
from app.modules.health.catalogue import BLOOD_PRESSURE, BY_CODE, BY_ID, Agg, Metric
from app.modules.health.models import Device, DeviceKind, IngestBatch, ReadingSource
from app.modules.health.schemas import (
    BloodPressureSeries,
    Dashboard,
    DeviceOut,
    IngestIn,
    IngestOut,
    Interval,
    ManualIn,
    MetricSummary,
    RangeStatus,
    ReadingOut,
    Series,
    SeriesPoint,
)

BUCKET = timedelta(minutes=5)
MAX_BUCKETS = 5000
FUTURE_SLACK = timedelta(minutes=5)
EARLIEST = datetime(2000, 1, 1, tzinfo=UTC)
DASHBOARD_CARDS = ("heart_rate", BLOOD_PRESSURE, "oxygen_saturation", "weight", "glucose")
SYS, DIA = BY_CODE["bp_systolic"], BY_CODE["bp_diastolic"]


def _now() -> datetime:
    return datetime.now(UTC)


def _metric(code: str) -> Metric:
    metric = BY_CODE.get(code)
    if metric is None:
        raise ApiError(404, "unknown_metric", f"There's no metric called {code!r}.")
    return metric


def _decimals(metric: Metric) -> int:
    return 0 if metric.unit in ("bpm", "%", "mg/dL", "mmHg", "count", "kcal", "min") else 1


def _round(metric: Metric, value: float | None) -> float | None:
    return None if value is None else round(value, _decimals(metric))


# ---------------------------------------------------------------- ingest

_UPSERT = text(
    """
    INSERT INTO vitallink.health_readings (user_id, metric_id, recorded_at, source, value, samples)
    SELECT :user_id, u.metric_id, u.recorded_at, CAST(:source AS vitallink.reading_source), u.value, u.samples
    FROM unnest(CAST(:metric_ids AS smallint[]), CAST(:times AS timestamptz[]),
                CAST(:values AS double precision[]), CAST(:samples AS smallint[]))
         AS u(metric_id, recorded_at, value, samples)
    ON CONFLICT (user_id, metric_id, recorded_at, source) DO UPDATE
      SET value = EXCLUDED.value, samples = EXCLUDED.samples, updated_at = now()
      WHERE (health_readings.value, health_readings.samples)
            IS DISTINCT FROM (EXCLUDED.value, EXCLUDED.samples)
    RETURNING (xmax = 0) AS inserted
    """
)


def _check_bucket(index: int, code: str, start: datetime, value: float, now: datetime) -> dict | None:
    metric = BY_CODE.get(code)
    problem = None
    if metric is None:
        problem = "unknown metric"
    elif start.timestamp() % 300:
        problem = "start is not on a 5-minute boundary"
    elif start > now + FUTURE_SLACK:
        problem = "start is in the future"
    elif start < EARLIEST:
        problem = "start is before 2000"
    elif not metric.min_value <= value <= metric.max_value:
        problem = f"value outside {metric.min_value:g}–{metric.max_value:g} {metric.unit}"
    return None if problem is None else {"index": index, "metric": code, "problem": problem}


async def _upsert(
    session: AsyncSession, user_id: uuid.UUID, source: str, rows: list[tuple[int, datetime, float, int]]
) -> tuple[int, int]:
    """Returns (inserted, updated). Rows whose stored value didn't change aren't returned."""
    result = await session.execute(
        _UPSERT,
        {
            "user_id": user_id,
            "source": source,
            "metric_ids": [r[0] for r in rows],
            "times": [r[1] for r in rows],
            "values": [r[2] for r in rows],
            "samples": [r[3] for r in rows],
        },
    )
    flags = [row.inserted for row in result]
    inserted = sum(flags)
    return inserted, len(flags) - inserted


async def ingest(session: AsyncSession, me: CurrentUser, body: IngestIn) -> IngestOut:
    """Idempotent batch upsert (docs/07 §7.2). Same key + same payload replays; a different payload → 409."""
    if len(body.buckets) > MAX_BUCKETS:
        raise ApiError(413, "batch_too_large", f"Send at most {MAX_BUCKETS} buckets per batch.")
    digest = hashlib.sha256(body.model_dump_json().encode()).digest()

    # Serialise requests that reuse a key, so a retry racing the original can't double-apply.
    await session.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))"),
        {"k": f"ingest:{me.id}:{body.idempotency_key}"},
    )
    previous = await session.scalar(
        select(IngestBatch).where(
            IngestBatch.user_id == me.id, IngestBatch.idempotency_key == body.idempotency_key
        )
    )
    if previous is not None:
        if previous.payload_sha256 != digest:
            raise ApiError(
                409, "idempotency_conflict", "This idempotency key was already used for different data."
            )
        return IngestOut(
            batch_id=previous.id,
            submitted=previous.submitted,
            inserted=previous.inserted,
            updated=previous.updated,
            unchanged=previous.unchanged,
            replayed=True,
        )

    now = _now()
    problems, seen, rows = [], set(), []
    for i, b in enumerate(body.buckets):
        problem = _check_bucket(i, b.metric, b.start, b.value, now)
        key = (b.metric, b.start)
        if problem is None and key in seen:
            problem = {"index": i, "metric": b.metric, "problem": "duplicate bucket in this batch"}
        if problem:
            problems.append(problem)
            continue
        seen.add(key)
        rows.append((BY_CODE[b.metric].id, b.start.astimezone(UTC), b.value, b.samples))
    for d in body.devices:
        for code in d.metrics:
            if code not in BY_CODE:
                problems.append({"device": d.external_id, "metric": code, "problem": "unknown metric"})
    if problems:
        raise ApiError(
            422, "invalid_buckets", "Some buckets were rejected; nothing was saved.", problems[:20]
        )

    inserted, updated = await _upsert(session, me.id, body.platform, rows)
    for d in body.devices:
        await session.execute(
            text(
                """
                INSERT INTO vitallink.devices (id, user_id, platform, external_id, name, kind, metric_ids)
                VALUES (:id, :user_id, CAST(:platform AS vitallink.reading_source), :external_id, :name,
                        CAST(:kind AS vitallink.device_kind), CAST(:metric_ids AS smallint[]))
                ON CONFLICT (user_id, platform, external_id) DO UPDATE
                  SET name = EXCLUDED.name, kind = EXCLUDED.kind,
                      metric_ids = EXCLUDED.metric_ids, last_sync_at = now()
                """
            ),
            {
                "id": uuid7(),
                "user_id": me.id,
                "platform": body.platform,
                "external_id": d.external_id,
                "name": d.name,
                "kind": d.kind.value,
                "metric_ids": sorted({BY_CODE[c].id for c in d.metrics}),
            },
        )
    batch = IngestBatch(
        user_id=me.id,
        idempotency_key=body.idempotency_key,
        payload_sha256=digest,
        platform=ReadingSource(body.platform),
        submitted=len(rows),
        inserted=inserted,
        updated=updated,
        unchanged=len(rows) - inserted - updated,
    )
    session.add(batch)
    await session.flush()
    return IngestOut(
        batch_id=batch.id,
        submitted=batch.submitted,
        inserted=inserted,
        updated=updated,
        unchanged=batch.unchanged,
        replayed=False,
    )


async def add_manual(session: AsyncSession, me: CurrentUser, body: ManualIn) -> ReadingOut:
    metric = _metric(body.metric)
    if not metric.manual_allowed:
        raise ApiError(422, "not_manual", f"{metric.code} comes from devices and can't be typed in.")
    now = _now()
    at = (body.at or now).astimezone(UTC)
    if at > now + FUTURE_SLACK:
        raise ApiError(422, "invalid_request", "The reading is in the future.")
    if not metric.min_value <= body.value <= metric.max_value:
        raise ApiError(
            422,
            "invalid_request",
            f"{metric.code} must be {metric.min_value:g}–{metric.max_value:g} {metric.unit}.",
        )
    bucket = datetime.fromtimestamp(at.timestamp() // 300 * 300, UTC)
    await _upsert(session, me.id, ReadingSource.manual.value, [(metric.id, bucket, body.value, 1)])
    return ReadingOut(
        metric=metric.code,
        value=body.value,
        unit=metric.unit,
        at=bucket,
        assessment=_assess(metric, body.value),
    )


# ---------------------------------------------------------------- series


def _window(days: int, tz: ZoneInfo, now: datetime) -> tuple[datetime, datetime]:
    """Local midnight `days - 1` days ago → now, as naive local times."""
    today = now.astimezone(tz).date()
    start = datetime.combine(today - timedelta(days=days - 1), time())
    return start, now.astimezone(tz).replace(tzinfo=None)


def _agg_sql(agg: Agg) -> str:
    return {
        Agg.sum: "sum(value)",
        Agg.avg: "avg(value)",
        Agg.max: "max(value)",
        Agg.last: "(array_agg(value ORDER BY recorded_at DESC))[1]",
    }[agg]


async def series(
    session: AsyncSession, user_id: uuid.UUID, tz_name: str, code: str, days: int, interval: Interval
) -> Series:
    metric = _metric(code)
    tz = ZoneInfo(tz_name)
    start_local, end_local = _window(days, tz, _now())
    sql = text(
        f"""
        WITH buckets AS (
          SELECT generate_series(date_trunc(:interval, CAST(:start AS timestamp)),
                                 date_trunc(:interval, CAST(:end AS timestamp)),
                                 CAST(CAST(:step AS text) AS interval)) AS local_bucket
        ), readings AS (
          SELECT date_trunc(:interval, recorded_at AT TIME ZONE :tz) AS local_bucket,
                 {_agg_sql(metric.aggregation)} AS value, sum(samples) AS samples
          FROM vitallink.health_readings
          WHERE user_id = :user_id AND metric_id = :metric_id
            AND recorded_at >= :start_utc AND recorded_at < :end_utc
          GROUP BY 1
        )
        SELECT b.local_bucket, r.value, coalesce(r.samples, 0) AS samples
        FROM buckets b LEFT JOIN readings r USING (local_bucket)
        ORDER BY b.local_bucket
        """  # noqa: S608 - the only interpolation is a fixed aggregate expression from _agg_sql
    )
    rows = await session.execute(
        sql,
        {
            "interval": interval,
            "start": start_local,
            "end": end_local,
            "step": f"1 {interval}",
            "tz": tz_name,
            "user_id": user_id,
            "metric_id": metric.id,
            "start_utc": start_local.replace(tzinfo=tz),
            "end_utc": _now() + FUTURE_SLACK,
        },
    )
    points = []
    for local_bucket, value, samples in rows:
        if value is None:
            if metric.aggregation is not Agg.sum:
                continue  # no reading: averages have no value to show, totals are zero
            value = 0.0
        points.append(
            SeriesPoint(
                bucket=local_bucket.replace(tzinfo=tz),
                value=_round(metric, value),
                samples=samples,
            )
        )
    return Series(metric=metric.code, unit=metric.unit, interval=interval, points=points)


async def blood_pressure_series(
    session: AsyncSession, user_id: uuid.UUID, tz: str, days: int, interval: Interval
) -> BloodPressureSeries:
    return BloodPressureSeries(
        systolic=await series(session, user_id, tz, SYS.code, days, interval),
        diastolic=await series(session, user_id, tz, DIA.code, days, interval),
    )


# ---------------------------------------------------------------- assessments


def bp_category(sys: float, dia: float) -> tuple[str, RangeStatus]:
    """American Heart Association categories."""
    if sys >= 140 or dia >= 90:
        return "High (stage 2)", "high"
    if sys >= 130 or dia >= 80:
        return "High (stage 1)", "high"
    if sys >= 120:
        return "Elevated", "borderline"
    if sys < 90 or dia < 60:
        return "Low", "low"
    return "Normal", "normal"


def _range(metric: Metric, value: float | None) -> RangeStatus:
    if value is None:
        return "no-data"
    if metric.normal_low is not None and value < metric.normal_low:
        return "low"
    if metric.normal_high is not None and value > metric.normal_high:
        return "high"
    return "normal"


def _assess(metric: Metric, value: float) -> str:
    if metric.normal_low is None and metric.normal_high is None:
        return ""
    return {"low": "Low", "high": "High"}.get(_range(metric, value), "Normal")


# ---------------------------------------------------------------- latest readings

_LATEST = text(
    """
    SELECT value, recorded_at FROM vitallink.health_readings
    WHERE user_id = :user_id AND metric_id = :metric_id
    ORDER BY recorded_at DESC LIMIT 1
    """
)

_LATEST_BP = text(
    """
    SELECT s.value AS sys, d.value AS dia, s.recorded_at
    FROM vitallink.health_readings s
    JOIN vitallink.health_readings d
      ON d.user_id = s.user_id AND d.metric_id = :dia
     AND d.recorded_at = s.recorded_at AND d.source = s.source
    WHERE s.user_id = :user_id AND s.metric_id = :sys
    ORDER BY s.recorded_at DESC LIMIT 1
    """
)


async def _latest_bp(session: AsyncSession, user_id: uuid.UUID):
    return (await session.execute(_LATEST_BP, {"user_id": user_id, "sys": SYS.id, "dia": DIA.id})).first()


async def latest(
    session: AsyncSession, user_id: uuid.UUID, tz_name: str, codes: list[str]
) -> list[ReadingOut]:
    tz = ZoneInfo(tz_name)
    out = []
    for code in codes:
        if code == BLOOD_PRESSURE:
            row = await _latest_bp(session, user_id)
            if row:
                out.append(
                    ReadingOut(
                        metric=BLOOD_PRESSURE,
                        value=round(row.sys),
                        value2=round(row.dia),
                        unit="mmHg",
                        at=row.recorded_at.astimezone(tz),
                        assessment=bp_category(row.sys, row.dia)[0],
                    )
                )
            continue
        metric = _metric(code)
        row = (await session.execute(_LATEST, {"user_id": user_id, "metric_id": metric.id})).first()
        if row:
            out.append(
                ReadingOut(
                    metric=code,
                    value=_round(metric, row.value),
                    unit=metric.unit,
                    at=row.recorded_at.astimezone(tz),
                    assessment=_assess(metric, row.value),
                )
            )
    return out


# ---------------------------------------------------------------- dashboard


async def _avg(session: AsyncSession, user_id: uuid.UUID, metric: Metric, since: datetime) -> float | None:
    return await session.scalar(
        text(
            "SELECT avg(value) FROM vitallink.health_readings "
            "WHERE user_id = :user_id AND metric_id = :metric_id AND recorded_at >= :since"
        ),
        {"user_id": user_id, "metric_id": metric.id, "since": since},
    )


async def _day_total(
    session: AsyncSession, user_id: uuid.UUID, metric: Metric, start: datetime, end: datetime
) -> float | None:
    return await session.scalar(
        text(
            "SELECT sum(value) FROM vitallink.health_readings WHERE user_id = :user_id "
            "AND metric_id = :metric_id AND recorded_at >= :start AND recorded_at < :end"
        ),
        {"user_id": user_id, "metric_id": metric.id, "start": start, "end": end},
    )


async def readiness(
    session: AsyncSession, user_id: uuid.UUID, tz: ZoneInfo, day: date, sleep_goal_min: int
) -> int | None:
    """Readiness 0–100 for a local day: the mean of whichever of three parts have data (at least two).

    - Sleep: last night's minutes asleep (18:00 the evening before → 14:00) against the sleep goal.
    - Resting heart rate: each bpm above the 30-day baseline costs 10 points.
    - HRV: the day's average as a share of the 30-day baseline.
    """
    midnight = datetime.combine(day, time(), tz)
    parts: list[float] = []

    asleep = await _day_total(
        session,
        user_id,
        BY_CODE["sleep_asleep"],
        midnight - timedelta(hours=6),
        midnight + timedelta(hours=14),
    )
    if asleep:
        parts.append(min(100.0, 100 * asleep / sleep_goal_min))

    for code in ("resting_heart_rate", "hrv"):
        metric = BY_CODE[code]
        today = await session.scalar(
            text(
                "SELECT avg(value) FROM vitallink.health_readings WHERE user_id = :u AND metric_id = :m "
                "AND recorded_at >= :start AND recorded_at < :end"
            ),
            {"u": user_id, "m": metric.id, "start": midnight, "end": midnight + timedelta(days=1)},
        )
        base = await session.scalar(
            text(
                "SELECT avg(value) FROM vitallink.health_readings WHERE user_id = :u AND metric_id = :m "
                "AND recorded_at >= :start AND recorded_at < :end"
            ),
            {"u": user_id, "m": metric.id, "start": midnight - timedelta(days=30), "end": midnight},
        )
        if today is None or not base:
            continue
        if code == "resting_heart_rate":
            parts.append(max(0.0, min(100.0, 100 - 10 * max(0.0, today - base))))
        else:
            parts.append(max(0.0, min(100.0, 100 * today / base)))

    return round(sum(parts) / len(parts)) if len(parts) >= 2 else None


async def dashboard(session: AsyncSession, me: CurrentUser) -> Dashboard:
    tz = ZoneInfo(me.user.timezone)
    now = _now()
    week_ago = now - timedelta(days=7)
    cards = []
    for code in DASHBOARD_CARDS:
        if code == BLOOD_PRESSURE:
            row = await _latest_bp(session, me.id)
            cards.append(
                MetricSummary(
                    metric=BLOOD_PRESSURE,
                    unit="mmHg",
                    latest=None,  # two numbers: clients read them from /me/health/latest
                    latest_at=row.recorded_at.astimezone(tz) if row else None,
                    avg_7d=None,
                    range=bp_category(row.sys, row.dia)[1] if row else "no-data",
                )
            )
            continue
        metric = BY_CODE[code]
        row = (await session.execute(_LATEST, {"user_id": me.id, "metric_id": metric.id})).first()
        value = row.value if row else None
        cards.append(
            MetricSummary(
                metric=code,
                unit=metric.unit,
                latest=_round(metric, value),
                latest_at=row.recorded_at.astimezone(tz) if row else None,
                avg_7d=_round(metric, await _avg(session, me.id, metric, week_ago)),
                range=_range(metric, value),
            )
        )

    goal = (await get_targets(session, me)).sleep_goal_min
    today = now.astimezone(tz).date()
    score = await readiness(session, me.id, tz, today, goal)
    before = await readiness(session, me.id, tz, today - timedelta(days=7), goal)
    return Dashboard(
        generated_at=now.astimezone(tz),
        health_score=score,
        health_score_delta_7d=score - before if score is not None and before is not None else None,
        metrics=cards,
    )


# ---------------------------------------------------------------- devices

OVERDUE_AFTER = {
    DeviceKind.phone: timedelta(hours=24),
    DeviceKind.wearable: timedelta(hours=24),
    DeviceKind.continuous: timedelta(hours=24),
    DeviceKind.home_monitor: timedelta(days=3),
}


def _device_out(d: Device, now: datetime, tz: ZoneInfo) -> DeviceOut:
    codes = {BY_ID[i].code for i in d.metric_ids if i in BY_ID}
    labels: list[str] = []
    for m in sorted((BY_ID[i] for i in d.metric_ids if i in BY_ID), key=lambda m: m.id):
        # A cuff or oximeter measures pulse, not continuous heart rate.
        label = "Pulse" if m.code == "heart_rate" and d.kind is DeviceKind.home_monitor else m.label
        if label not in labels:
            labels.append(label)
    if d.kind is DeviceKind.wearable:
        icon = "devices"
    elif d.kind is DeviceKind.continuous:
        icon = "droplet"
    elif "weight" in codes:
        icon = "scale"
    elif "bp_systolic" in codes:
        icon = "monitor"
    else:
        icon = "health"
    return DeviceOut(
        id=d.id,
        name=d.name,
        kind=d.kind,
        icon=icon,
        last_sync_at=d.last_sync_at.astimezone(tz),
        status="overdue" if now - d.last_sync_at > OVERDUE_AFTER[d.kind] else "connected",
        data=labels,
        short_data=labels[:3] if d.kind is DeviceKind.wearable and len(labels) > 3 else None,
    )


async def devices(session: AsyncSession, me: CurrentUser) -> list[DeviceOut]:
    rows = await session.scalars(
        select(Device).where(Device.user_id == me.id).order_by(Device.last_sync_at.desc())
    )
    now, tz = _now(), ZoneInfo(me.user.timezone)
    return [_device_out(d, now, tz) for d in rows]


async def forget_device(session: AsyncSession, me: CurrentUser, device_id: uuid.UUID) -> None:
    """Forget the device. Its readings stay: they're the user's data, not the device's."""
    result = await session.execute(delete(Device).where(Device.id == device_id, Device.user_id == me.id))
    if result.rowcount == 0:
        raise ApiError(404, "not_found", "No such device.")
