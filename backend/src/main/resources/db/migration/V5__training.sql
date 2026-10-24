-- Training: exercise catalogue, workouts, sets, plans (docs/04-database.md §4.5).

CREATE TYPE vitallink.workout_type AS ENUM ('walk', 'run', 'cycle', 'strength', 'swim', 'hiit', 'yoga', 'other');

CREATE TYPE vitallink.session_kind AS ENUM ('cardio', 'strength', 'mobility');

CREATE TYPE vitallink.muscle_group AS ENUM ('chest', 'back', 'shoulders', 'arms', 'core', 'legs', 'glutes', 'full_body');

CREATE TYPE vitallink.plan_status AS ENUM ('active', 'archived');

CREATE TABLE vitallink.exercises (
  id             smallint CONSTRAINT exercises_pkey PRIMARY KEY,
  name           text NOT NULL CONSTRAINT exercises_name_key UNIQUE,
  primary_muscle vitallink.muscle_group NOT NULL
);

INSERT INTO vitallink.exercises (id, name, primary_muscle) VALUES 
        (1, 'Back Squat', 'legs'),
        (2, 'Front Squat', 'legs'),
        (3, 'Leg Press', 'legs'),
        (4, 'Lunge', 'legs'),
        (5, 'Romanian Deadlift', 'legs'),
        (6, 'Calf Raise', 'legs'),
        (7, 'Deadlift', 'back'),
        (8, 'Barbell Row', 'back'),
        (9, 'Pull-up', 'back'),
        (10, 'Lat Pulldown', 'back'),
        (11, 'Seated Cable Row', 'back'),
        (12, 'Bench Press', 'chest'),
        (13, 'Incline Bench Press', 'chest'),
        (14, 'Dumbbell Fly', 'chest'),
        (15, 'Push-up', 'chest'),
        (16, 'Dip', 'chest'),
        (17, 'Overhead Press', 'shoulders'),
        (18, 'Lateral Raise', 'shoulders'),
        (19, 'Face Pull', 'shoulders'),
        (20, 'Bicep Curl', 'arms'),
        (21, 'Hammer Curl', 'arms'),
        (22, 'Tricep Extension', 'arms'),
        (23, 'Skull Crusher', 'arms'),
        (24, 'Plank', 'core'),
        (25, 'Hanging Leg Raise', 'core'),
        (26, 'Russian Twist', 'core'),
        (27, 'Hip Thrust', 'glutes'),
        (28, 'Glute Bridge', 'glutes'),
        (29, 'Kettlebell Swing', 'full_body'),
        (30, 'Burpee', 'full_body');

CREATE TABLE vitallink.training_plans (
  id                     uuid CONSTRAINT training_plans_pkey PRIMARY KEY,
  user_id                uuid NOT NULL CONSTRAINT training_plans_user_id_fkey
                         REFERENCES vitallink.users (id) ON DELETE CASCADE,
  name                   text NOT NULL,
  status                 vitallink.plan_status NOT NULL,
  weekly_target_sessions smallint NOT NULL,
  created_at             timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT training_plans_target_range CHECK (weekly_target_sessions BETWEEN 1 AND 14),
  CONSTRAINT training_plans_name_length CHECK (char_length(name) BETWEEN 1 AND 80)
);

CREATE INDEX training_plans_user_id_idx ON vitallink.training_plans (user_id);

CREATE UNIQUE INDEX training_plans_one_active ON vitallink.training_plans (user_id) WHERE status = 'active';

CREATE TABLE vitallink.plan_sessions (
  id            uuid CONSTRAINT plan_sessions_pkey PRIMARY KEY,
  plan_id       uuid NOT NULL CONSTRAINT plan_sessions_plan_id_fkey
                REFERENCES vitallink.training_plans (id) ON DELETE CASCADE,
  title         text NOT NULL,
  kind          vitallink.session_kind NOT NULL,
  scheduled_for timestamptz NOT NULL,
  exercises     smallint NOT NULL,
  minutes       smallint NOT NULL,
  CONSTRAINT plan_sessions_exercises_nonnegative CHECK (exercises >= 0),
  CONSTRAINT plan_sessions_minutes_range CHECK (minutes BETWEEN 1 AND 600),
  CONSTRAINT plan_sessions_title_length CHECK (char_length(title) BETWEEN 1 AND 80)
);

CREATE INDEX plan_sessions_plan_scheduled_idx ON vitallink.plan_sessions (plan_id, scheduled_for);

CREATE TABLE vitallink.workouts (
  id              uuid CONSTRAINT workouts_pkey PRIMARY KEY,
  user_id         uuid NOT NULL CONSTRAINT workouts_user_id_fkey
                  REFERENCES vitallink.users (id) ON DELETE CASCADE,
  type            vitallink.workout_type NOT NULL,
  kind            vitallink.session_kind NOT NULL,
  title           text NOT NULL,
  started_at      timestamptz NOT NULL,
  duration_s      integer NOT NULL,
  distance_m      numeric(9, 1),
  kcal            integer,
  avg_hr          smallint,
  rpe             smallint,
  zone_seconds    integer[],
  focus           text,
  source          vitallink.reading_source NOT NULL,
  external_id     text,
  plan_session_id uuid CONSTRAINT workouts_plan_session_id_fkey
                  REFERENCES vitallink.plan_sessions (id) ON DELETE SET NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT workouts_duration_range CHECK (duration_s BETWEEN 1 AND 86400),
  CONSTRAINT workouts_distance_nonnegative CHECK (distance_m >= 0),
  CONSTRAINT workouts_kcal_range CHECK (kcal BETWEEN 0 AND 20000),
  CONSTRAINT workouts_avg_hr_range CHECK (avg_hr BETWEEN 25 AND 250),
  CONSTRAINT workouts_rpe_range CHECK (rpe BETWEEN 1 AND 10),
  CONSTRAINT workouts_zones_shape
    CHECK (zone_seconds IS NULL OR (cardinality(zone_seconds) = 5 AND 0 <= ALL (zone_seconds))),
  CONSTRAINT workouts_title_length CHECK (char_length(title) BETWEEN 1 AND 80),
  -- Synced workouts carry the platform's id (a re-sync updates, never duplicates); manual ones don't.
  CONSTRAINT workouts_external_id_matches_source CHECK ((source = 'manual') = (external_id IS NULL))
);

CREATE INDEX workouts_user_started_idx ON vitallink.workouts (user_id, started_at DESC);

CREATE UNIQUE INDEX workouts_user_source_external_key ON vitallink.workouts (user_id, source, external_id) WHERE external_id IS NOT NULL;

CREATE INDEX workouts_plan_session_id_idx ON vitallink.workouts (plan_session_id);

CREATE TABLE vitallink.exercise_sets (
  id          bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT exercise_sets_pkey PRIMARY KEY,
  workout_id  uuid NOT NULL CONSTRAINT exercise_sets_workout_id_fkey
              REFERENCES vitallink.workouts (id) ON DELETE CASCADE,
  exercise_id smallint NOT NULL CONSTRAINT exercise_sets_exercise_id_fkey
              REFERENCES vitallink.exercises (id) ON DELETE RESTRICT,
  set_no      smallint NOT NULL,
  reps        smallint,
  weight_kg   numeric(6, 2),
  duration_s  integer,
  CONSTRAINT exercise_sets_workout_exercise_set_key UNIQUE (workout_id, exercise_id, set_no),
  CONSTRAINT exercise_sets_set_no_positive CHECK (set_no > 0),
  CONSTRAINT exercise_sets_reps_range CHECK (reps BETWEEN 1 AND 1000),
  CONSTRAINT exercise_sets_weight_range CHECK (weight_kg BETWEEN 0 AND 1000),
  CONSTRAINT exercise_sets_duration_positive CHECK (duration_s > 0),
  CONSTRAINT exercise_sets_reps_or_duration CHECK (reps IS NOT NULL OR duration_s IS NOT NULL)
);

CREATE INDEX exercise_sets_exercise_id_idx ON vitallink.exercise_sets (exercise_id);

ALTER TABLE vitallink.exercises ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.training_plans ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.plan_sessions ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.workouts ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.exercise_sets ENABLE ROW LEVEL SECURITY;
