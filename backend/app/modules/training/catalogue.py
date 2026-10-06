"""Seeded exercise catalogue. Ids are permanent; migration 0005 inserts the same rows."""

from app.modules.training.models import MuscleGroup as M

EXERCISES: tuple[tuple[int, str, M], ...] = (
    (1, "Back Squat", M.legs),
    (2, "Front Squat", M.legs),
    (3, "Leg Press", M.legs),
    (4, "Lunge", M.legs),
    (5, "Romanian Deadlift", M.legs),
    (6, "Calf Raise", M.legs),
    (7, "Deadlift", M.back),
    (8, "Barbell Row", M.back),
    (9, "Pull-up", M.back),
    (10, "Lat Pulldown", M.back),
    (11, "Seated Cable Row", M.back),
    (12, "Bench Press", M.chest),
    (13, "Incline Bench Press", M.chest),
    (14, "Dumbbell Fly", M.chest),
    (15, "Push-up", M.chest),
    (16, "Dip", M.chest),
    (17, "Overhead Press", M.shoulders),
    (18, "Lateral Raise", M.shoulders),
    (19, "Face Pull", M.shoulders),
    (20, "Bicep Curl", M.arms),
    (21, "Hammer Curl", M.arms),
    (22, "Tricep Extension", M.arms),
    (23, "Skull Crusher", M.arms),
    (24, "Plank", M.core),
    (25, "Hanging Leg Raise", M.core),
    (26, "Russian Twist", M.core),
    (27, "Hip Thrust", M.glutes),
    (28, "Glute Bridge", M.glutes),
    (29, "Kettlebell Swing", M.full_body),
    (30, "Burpee", M.full_body),
)

BY_NAME = {name.lower(): (id_, name, muscle) for id_, name, muscle in EXERCISES}
