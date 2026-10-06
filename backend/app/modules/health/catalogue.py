"""The closed metric catalogue (docs/04-database.md §4.4).

Each metric belongs to exactly one sharing scope. Its plausible range is for one 5-minute bucket and
rejects garbage. Its normal range (optional) drives the dashboard's range chip. Ids are permanent: never
renumber, only append. Migration 0003 seeds the same rows, and a test keeps the two in step.
"""

from dataclasses import dataclass
from enum import StrEnum

from app.core.audit import Scope


class Agg(StrEnum):
    sum = "sum"
    avg = "avg"
    max = "max"
    last = "last"


@dataclass(frozen=True)
class Metric:
    id: int
    code: str
    scope: Scope
    unit: str
    aggregation: Agg
    min_value: float
    max_value: float
    manual_allowed: bool = False
    normal_low: float | None = None
    normal_high: float | None = None
    label: str = ""  # device data label ("Heart rate"); groups share one


V, A, S = Scope.vitals, Scope.activity, Scope.sleep
SUM, AVG, MAX, LAST = Agg.sum, Agg.avg, Agg.max, Agg.last

METRICS: tuple[Metric, ...] = (
    # vitals
    Metric(1, "heart_rate", V, "bpm", AVG, 25, 250, True, 50, 100, "Heart rate"),
    Metric(2, "resting_heart_rate", V, "bpm", AVG, 25, 150, False, 40, 100, "Heart rate"),
    Metric(3, "hrv", V, "ms", AVG, 1, 300, False, None, None, "Heart rate"),
    Metric(4, "bp_systolic", V, "mmHg", AVG, 60, 260, True, None, None, "Blood pressure"),
    Metric(5, "bp_diastolic", V, "mmHg", AVG, 30, 160, True, None, None, "Blood pressure"),
    Metric(6, "oxygen_saturation", V, "%", AVG, 50, 100, True, 95, 100, "Blood oxygen"),
    Metric(7, "respiratory_rate", V, "breaths/min", AVG, 4, 60, False, 12, 20, "Respiratory rate"),
    Metric(8, "glucose", V, "mg/dL", AVG, 20, 600, True, 70, 140, "Blood glucose"),
    Metric(9, "body_temperature", V, "°C", AVG, 30, 45, True, 36.1, 37.5, "Temperature"),
    Metric(10, "vo2_max", V, "mL/kg/min", LAST, 10, 90, False, None, None, "Cardio fitness"),
    Metric(11, "hr_high_event", V, "count", SUM, 0, 10, False, None, None, "Heart alerts"),
    Metric(12, "hr_low_event", V, "count", SUM, 0, 10, False, None, None, "Heart alerts"),
    Metric(13, "irregular_rhythm_event", V, "count", SUM, 0, 10, False, None, None, "Heart alerts"),
    # body (vitals scope)
    Metric(20, "weight", V, "kg", AVG, 20, 400, True, None, None, "Weight"),
    Metric(21, "body_fat", V, "%", AVG, 2, 75, True, None, None, "Body composition"),
    Metric(22, "lean_mass", V, "kg", AVG, 10, 200, True, None, None, "Body composition"),
    Metric(23, "waist", V, "cm", AVG, 10, 250, True, None, None, "Measurements"),
    Metric(24, "hip", V, "cm", AVG, 10, 250, True, None, None, "Measurements"),
    Metric(25, "chest", V, "cm", AVG, 10, 250, True, None, None, "Measurements"),
    Metric(26, "arm", V, "cm", AVG, 10, 250, True, None, None, "Measurements"),
    Metric(27, "thigh", V, "cm", AVG, 10, 250, True, None, None, "Measurements"),
    # activity
    Metric(40, "steps", A, "count", SUM, 0, 5000, False, None, None, "Activity"),
    Metric(41, "distance", A, "m", SUM, 0, 6000, False, None, None, "Activity"),
    Metric(42, "active_energy", A, "kcal", SUM, 0, 250, False, None, None, "Activity"),
    Metric(43, "basal_energy", A, "kcal", SUM, 0, 50, False, None, None, "Activity"),
    Metric(44, "exercise_minutes", A, "min", SUM, 0, 5, False, None, None, "Activity"),
    Metric(45, "stand_hours", A, "count", SUM, 0, 1, False, None, None, "Activity"),
    Metric(46, "flights_climbed", A, "count", SUM, 0, 100, False, None, None, "Activity"),
    Metric(47, "mindful_minutes", A, "min", SUM, 0, 5, True, None, None, "Mindfulness"),
    Metric(48, "mood", A, "score", AVG, 1, 5, True, None, None, "Mood"),
    Metric(49, "stress", A, "score", AVG, 1, 5, True, None, None, "Mood"),
    Metric(50, "energy", A, "score", AVG, 1, 5, True, None, None, "Mood"),
    Metric(51, "screen_time", A, "min", SUM, 0, 5, False, None, None, "Screen time"),
    Metric(52, "screen_time_social", A, "min", SUM, 0, 5, False, None, None, "Screen time"),
    Metric(53, "screen_time_entertainment", A, "min", SUM, 0, 5, False, None, None, "Screen time"),
    Metric(54, "screen_time_productivity", A, "min", SUM, 0, 5, False, None, None, "Screen time"),
    Metric(55, "phone_pickups", A, "count", SUM, 0, 200, False, None, None, "Screen time"),
    Metric(56, "notifications_received", A, "count", SUM, 0, 1000, False, None, None, "Screen time"),
    # sleep (minutes in each stage per 5-minute bucket)
    Metric(60, "sleep_asleep", S, "min", SUM, 0, 5, False, None, None, "Sleep"),
    Metric(61, "sleep_deep", S, "min", SUM, 0, 5, False, None, None, "Sleep"),
    Metric(62, "sleep_core", S, "min", SUM, 0, 5, False, None, None, "Sleep"),
    Metric(63, "sleep_rem", S, "min", SUM, 0, 5, False, None, None, "Sleep"),
    Metric(64, "sleep_awake", S, "min", SUM, 0, 5, False, None, None, "Sleep"),
)

BY_CODE: dict[str, Metric] = {m.code: m for m in METRICS}
BY_ID: dict[int, Metric] = {m.id: m for m in METRICS}

# Pseudo-metric the API accepts in series/latest: systolic + diastolic at the same instants.
BLOOD_PRESSURE = "blood_pressure"
