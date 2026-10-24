-- The in-app notification feed and the health library (docs/04-database.md §4.7).
--
-- Notifications are written by events (a new lab result, someone viewing shared records) and by the
-- nightly job (missed doses, tomorrow's appointments, goals at risk). The dedupe key makes a repeated event
-- or a re-run job a no-op. They're kept 90 days. There is no remote push (decision D9).
--
-- The library is the same for everyone and records no per-user reads; "trending" is editorial order.
-- Articles live as Markdown in the API's resources and are loaded at startup.

CREATE TYPE vitallink.notification_kind AS ENUM
  ('fitness', 'health', 'medication', 'appointments', 'records', 'security', 'goals');

CREATE TABLE vitallink.notifications (
  id         uuid CONSTRAINT notifications_pkey PRIMARY KEY,
  user_id    uuid NOT NULL CONSTRAINT notifications_user_id_fkey
             REFERENCES vitallink.users (id) ON DELETE CASCADE,
  kind       vitallink.notification_kind NOT NULL,
  title      text NOT NULL,
  body       text NOT NULL,
  short_body text,
  action     text NOT NULL,
  target     text NOT NULL,
  dedupe_key text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  read_at    timestamptz,
  CONSTRAINT notifications_user_dedupe_key UNIQUE (user_id, dedupe_key),
  CONSTRAINT notifications_title_length CHECK (char_length(title) BETWEEN 1 AND 120),
  CONSTRAINT notifications_body_length CHECK (char_length(body) BETWEEN 1 AND 300),
  CONSTRAINT notifications_short_body_length CHECK (char_length(short_body) <= 160),
  CONSTRAINT notifications_action_length CHECK (char_length(action) BETWEEN 1 AND 30),
  CONSTRAINT notifications_target_is_route CHECK (target ~ '^/[a-z0-9/_-]*$')
);

CREATE INDEX notifications_user_created_idx ON vitallink.notifications (user_id, created_at DESC);

CREATE INDEX notifications_user_unread_idx ON vitallink.notifications (user_id) WHERE read_at IS NULL;

CREATE TYPE vitallink.placement AS ENUM ('web', 'mobile');

-- No blood category: the Figma "Cat / Blood" layer is a leftover.
CREATE TABLE vitallink.article_categories (
  code     text CONSTRAINT article_categories_pkey PRIMARY KEY,
  label    text NOT NULL,
  position smallint NOT NULL,
  CONSTRAINT article_categories_code_format CHECK (code ~ '^[a-z][a-z_]*$'),
  CONSTRAINT article_categories_no_blood CHECK (code NOT LIKE '%blood%'),
  CONSTRAINT article_categories_position_key UNIQUE (position)
);

CREATE TABLE vitallink.articles (
  id            uuid CONSTRAINT articles_pkey PRIMARY KEY,
  slug          text NOT NULL CONSTRAINT articles_slug_key UNIQUE,
  category_code text NOT NULL CONSTRAINT articles_category_code_fkey
                REFERENCES vitallink.article_categories (code) ON DELETE RESTRICT,
  title         text NOT NULL,
  minutes       smallint NOT NULL,
  -- Set only for a real reviewer who agreed to be named (decision D14).
  reviewer      text,
  summary       text NOT NULL,
  body_md       text NOT NULL,
  trending_rank smallint,
  published_on  date NOT NULL,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT articles_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  CONSTRAINT articles_title_length CHECK (char_length(title) BETWEEN 1 AND 120),
  CONSTRAINT articles_minutes_range CHECK (minutes BETWEEN 1 AND 60),
  CONSTRAINT articles_summary_length CHECK (char_length(summary) BETWEEN 1 AND 300)
);

CREATE INDEX articles_category_code_idx ON vitallink.articles (category_code);

CREATE UNIQUE INDEX articles_trending_rank_key ON vitallink.articles (trending_rank) WHERE trending_rank IS NOT NULL;

CREATE TABLE vitallink.library_features (
  placement  vitallink.placement CONSTRAINT library_features_pkey PRIMARY KEY,
  article_id uuid NOT NULL CONSTRAINT library_features_article_id_fkey
             REFERENCES vitallink.articles (id) ON DELETE CASCADE
);

CREATE INDEX library_features_article_id_idx ON vitallink.library_features (article_id);

ALTER TABLE vitallink.notifications ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.article_categories ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.articles ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.library_features ENABLE ROW LEVEL SECURITY;
