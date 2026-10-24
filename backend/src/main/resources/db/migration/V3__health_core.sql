-- Health core: metric catalogue, readings, ingest batches, devices (docs/04-database.md §4.4).

CREATE TYPE vitallink.reading_source AS ENUM ('healthkit', 'health_connect', 'manual');

CREATE TYPE vitallink.aggregation AS ENUM ('sum', 'avg', 'max', 'last');

CREATE TYPE vitallink.device_kind AS ENUM ('phone', 'wearable', 'home_monitor', 'continuous');

CREATE TABLE vitallink.metrics (
  id             smallint PRIMARY KEY,
  code           text NOT NULL CONSTRAINT metrics_code_key UNIQUE,
  scope          vitallink.share_scope NOT NULL,
  unit           text NOT NULL,
  aggregation    vitallink.aggregation NOT NULL,
  min_value      double precision NOT NULL,
  max_value      double precision NOT NULL,
  manual_allowed boolean NOT NULL,
  normal_low     double precision,
  normal_high    double precision,
  CONSTRAINT metrics_range_order CHECK (min_value < max_value),
  CONSTRAINT metrics_normal_order CHECK (normal_low IS NULL OR normal_high IS NULL OR normal_low < normal_high)
);

INSERT INTO vitallink.metrics (id, code, scope, unit, aggregation, min_value, max_value, manual_allowed, normal_low, normal_high) VALUES 
        (1, 'heart_rate', 'vitals', 'bpm', 'avg', 25, 250, true, 50, 100),
        (2, 'resting_heart_rate', 'vitals', 'bpm', 'avg', 25, 150, false, 40, 100),
        (3, 'hrv', 'vitals', 'ms', 'avg', 1, 300, false, NULL, NULL),
        (4, 'bp_systolic', 'vitals', 'mmHg', 'avg', 60, 260, true, NULL, NULL),
        (5, 'bp_diastolic', 'vitals', 'mmHg', 'avg', 30, 160, true, NULL, NULL),
        (6, 'oxygen_saturation', 'vitals', '%', 'avg', 50, 100, true, 95, 100),
        (7, 'respiratory_rate', 'vitals', 'breaths/min', 'avg', 4, 60, false, 12, 20),
        (8, 'glucose', 'vitals', 'mg/dL', 'avg', 20, 600, true, 70, 140),
        (9, 'body_temperature', 'vitals', '°C', 'avg', 30, 45, true, 36.1, 37.5),
        (10, 'vo2_max', 'vitals', 'mL/kg/min', 'last', 10, 90, false, NULL, NULL),
        (11, 'hr_high_event', 'vitals', 'count', 'sum', 0, 10, false, NULL, NULL),
        (12, 'hr_low_event', 'vitals', 'count', 'sum', 0, 10, false, NULL, NULL),
        (13, 'irregular_rhythm_event', 'vitals', 'count', 'sum', 0, 10, false, NULL, NULL),
        (20, 'weight', 'vitals', 'kg', 'avg', 20, 400, true, NULL, NULL),
        (21, 'body_fat', 'vitals', '%', 'avg', 2, 75, true, NULL, NULL),
        (22, 'lean_mass', 'vitals', 'kg', 'avg', 10, 200, true, NULL, NULL),
        (23, 'waist', 'vitals', 'cm', 'avg', 10, 250, true, NULL, NULL),
        (24, 'hip', 'vitals', 'cm', 'avg', 10, 250, true, NULL, NULL),
        (25, 'chest', 'vitals', 'cm', 'avg', 10, 250, true, NULL, NULL),
        (26, 'arm', 'vitals', 'cm', 'avg', 10, 250, true, NULL, NULL),
        (27, 'thigh', 'vitals', 'cm', 'avg', 10, 250, true, NULL, NULL),
        (40, 'steps', 'activity', 'count', 'sum', 0, 5000, false, NULL, NULL),
        (41, 'distance', 'activity', 'm', 'sum', 0, 6000, false, NULL, NULL),
        (42, 'active_energy', 'activity', 'kcal', 'sum', 0, 250, false, NULL, NULL),
        (43, 'basal_energy', 'activity', 'kcal', 'sum', 0, 50, false, NULL, NULL),
        (44, 'exercise_minutes', 'activity', 'min', 'sum', 0, 5, false, NULL, NULL),
        (45, 'stand_hours', 'activity', 'count', 'sum', 0, 1, false, NULL, NULL),
        (46, 'flights_climbed', 'activity', 'count', 'sum', 0, 100, false, NULL, NULL),
        (47, 'mindful_minutes', 'activity', 'min', 'sum', 0, 5, true, NULL, NULL),
        (48, 'mood', 'activity', 'score', 'avg', 1, 5, true, NULL, NULL),
        (49, 'stress', 'activity', 'score', 'avg', 1, 5, true, NULL, NULL),
        (50, 'energy', 'activity', 'score', 'avg', 1, 5, true, NULL, NULL),
        (51, 'screen_time', 'activity', 'min', 'sum', 0, 5, false, NULL, NULL),
        (52, 'screen_time_social', 'activity', 'min', 'sum', 0, 5, false, NULL, NULL),
        (53, 'screen_time_entertainment', 'activity', 'min', 'sum', 0, 5, false, NULL, NULL),
        (54, 'screen_time_productivity', 'activity', 'min', 'sum', 0, 5, false, NULL, NULL),
        (55, 'phone_pickups', 'activity', 'count', 'sum', 0, 200, false, NULL, NULL),
        (56, 'notifications_received', 'activity', 'count', 'sum', 0, 1000, false, NULL, NULL),
        (60, 'sleep_asleep', 'sleep', 'min', 'sum', 0, 5, false, NULL, NULL),
        (61, 'sleep_deep', 'sleep', 'min', 'sum', 0, 5, false, NULL, NULL),
        (62, 'sleep_core', 'sleep', 'min', 'sum', 0, 5, false, NULL, NULL),
        (63, 'sleep_rem', 'sleep', 'min', 'sum', 0, 5, false, NULL, NULL),
        (64, 'sleep_awake', 'sleep', 'min', 'sum', 0, 5, false, NULL, NULL);

CREATE TABLE vitallink.health_readings (
  user_id     uuid NOT NULL CONSTRAINT health_readings_user_id_fkey
              REFERENCES vitallink.users (id) ON DELETE CASCADE,
  metric_id   smallint NOT NULL CONSTRAINT health_readings_metric_id_fkey
              REFERENCES vitallink.metrics (id) ON DELETE RESTRICT,
  recorded_at timestamptz NOT NULL,
  source      vitallink.reading_source NOT NULL,
  value       double precision NOT NULL,
  samples     smallint NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT health_readings_pkey PRIMARY KEY (user_id, metric_id, recorded_at, source),
  CONSTRAINT health_readings_bucket_aligned CHECK (extract(epoch FROM recorded_at)::bigint % 300 = 0),
  CONSTRAINT health_readings_samples_positive CHECK (samples > 0)
);

CREATE TABLE vitallink.ingest_batches (
  id              bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT ingest_batches_pkey PRIMARY KEY,
  user_id         uuid NOT NULL CONSTRAINT ingest_batches_user_id_fkey
                  REFERENCES vitallink.users (id) ON DELETE CASCADE,
  idempotency_key uuid NOT NULL,
  payload_sha256  bytea NOT NULL,
  platform        vitallink.reading_source NOT NULL,
  received_at     timestamptz NOT NULL DEFAULT now(),
  submitted       integer NOT NULL,
  inserted        integer NOT NULL,
  updated         integer NOT NULL,
  unchanged       integer NOT NULL,
  CONSTRAINT ingest_batches_user_key_key UNIQUE (user_id, idempotency_key),
  CONSTRAINT ingest_batches_hash_length CHECK (octet_length(payload_sha256) = 32),
  CONSTRAINT ingest_batches_submitted_range CHECK (submitted BETWEEN 1 AND 5000),
  CONSTRAINT ingest_batches_counts_add_up CHECK (inserted + updated + unchanged = submitted)
);

CREATE INDEX ingest_batches_received_at_idx ON vitallink.ingest_batches (received_at);

CREATE TABLE vitallink.devices (
  id            uuid CONSTRAINT devices_pkey PRIMARY KEY,
  user_id       uuid NOT NULL CONSTRAINT devices_user_id_fkey
                REFERENCES vitallink.users (id) ON DELETE CASCADE,
  platform      vitallink.reading_source NOT NULL,
  external_id   text NOT NULL,
  name          text NOT NULL,
  kind          vitallink.device_kind NOT NULL,
  metric_ids    smallint[] NOT NULL,
  first_seen_at timestamptz NOT NULL DEFAULT now(),
  last_sync_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT devices_user_platform_external_key UNIQUE (user_id, platform, external_id)
);

ALTER TABLE vitallink.metrics ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.health_readings ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.ingest_batches ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.devices ENABLE ROW LEVEL SECURITY;
