-- Device clouds connected with OAuth (Google Health: Fitbit and Pixel Watch). The API pulls readings from the
-- service and stores them as ordinary 5-minute readings with their own source.
ALTER TYPE vitallink.reading_source ADD VALUE IF NOT EXISTS 'google_health';

CREATE TABLE vitallink.device_connections (
  user_id        uuid NOT NULL CONSTRAINT device_connections_user_id_fkey
                 REFERENCES vitallink.users (id) ON DELETE CASCADE,
  service        text NOT NULL,
  -- The service's refresh token, sealed with AES-256-GCM (CONNECTION_KEY); never exported or returned.
  refresh_token  bytea NOT NULL,
  scopes         text NOT NULL,
  connected_at   timestamptz NOT NULL DEFAULT now(),
  last_sync_at   timestamptz,
  -- Readings are complete up to here; the next sync starts a day earlier to pick up late uploads.
  synced_through timestamptz,
  last_error     text,
  CONSTRAINT device_connections_pkey PRIMARY KEY (user_id, service),
  CONSTRAINT device_connections_service_check CHECK (service IN ('google_health')),
  CONSTRAINT device_connections_last_error_length CHECK (char_length(last_error) <= 300)
);

ALTER TABLE vitallink.device_connections ENABLE ROW LEVEL SECURITY;
