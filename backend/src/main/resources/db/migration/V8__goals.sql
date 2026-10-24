-- Goals (docs/04-database.md §4.5). Progress and status (on track / at risk) are computed from the
-- user's data when read; the lifecycle (active, paused, achieved, abandoned) is what the user or the nightly
-- job set. Insights arrive with the nightly job (V10).

CREATE TYPE vitallink.goal_area AS ENUM ('activity', 'training', 'nutrition', 'recovery', 'body', 'heart', 'mind');

-- daily_total: a day's total or average against the target (steps, protein…), judged on the last 7 days.
-- weekly_count: how many times this week (workouts).
-- target_value: reach a reading (resting heart rate under 60, body fat to 17%).
CREATE TYPE vitallink.goal_kind AS ENUM ('daily_total', 'weekly_count', 'target_value');

CREATE TYPE vitallink.comparator AS ENUM ('at_least', 'at_most');

CREATE TYPE vitallink.goal_lifecycle AS ENUM ('active', 'paused', 'achieved', 'abandoned');

CREATE TABLE vitallink.goals (
  id          uuid CONSTRAINT goals_pkey PRIMARY KEY,
  user_id     uuid NOT NULL CONSTRAINT goals_user_id_fkey
              REFERENCES vitallink.users (id) ON DELETE CASCADE,
  title       text NOT NULL,
  short_title text NOT NULL,
  area        vitallink.goal_area NOT NULL,
  kind        vitallink.goal_kind NOT NULL,
  -- A code from the goal metric list in the API (health metrics, nutrition totals, workouts).
  metric      text NOT NULL,
  comparator  vitallink.comparator NOT NULL DEFAULT 'at_least',
  target      numeric(10, 2) NOT NULL,
  -- target_value goals: the reading when the goal was set, so progress has a starting point.
  baseline    numeric(10, 2),
  starts_on   date NOT NULL,
  ends_on     date,
  lifecycle   vitallink.goal_lifecycle NOT NULL DEFAULT 'active',
  achieved_at timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT goals_title_length CHECK (char_length(title) BETWEEN 1 AND 80),
  CONSTRAINT goals_short_title_length CHECK (char_length(short_title) BETWEEN 1 AND 40),
  CONSTRAINT goals_metric_length CHECK (char_length(metric) BETWEEN 1 AND 40),
  CONSTRAINT goals_target_positive CHECK (target > 0),
  CONSTRAINT goals_baseline_only_for_targets CHECK (kind = 'target_value' OR baseline IS NULL),
  CONSTRAINT goals_end_after_start CHECK (ends_on IS NULL OR ends_on > starts_on),
  CONSTRAINT goals_achieved_has_time CHECK ((lifecycle = 'achieved') = (achieved_at IS NOT NULL))
);

CREATE INDEX goals_user_lifecycle_idx ON vitallink.goals (user_id, lifecycle);

ALTER TABLE vitallink.goals ENABLE ROW LEVEL SECURITY;
