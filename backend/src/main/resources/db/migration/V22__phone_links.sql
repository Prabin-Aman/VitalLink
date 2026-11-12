-- The Android helper app (Health Connect). The web portal shows a one-time code; the app exchanges it for a
-- token that can only upload readings (POST /v1/phone/ingest). Only SHA-256 hashes of the code and token are kept.
CREATE TABLE vitallink.phone_links (
  id              uuid CONSTRAINT phone_links_pkey PRIMARY KEY,
  user_id         uuid NOT NULL CONSTRAINT phone_links_user_id_fkey
                  REFERENCES vitallink.users (id) ON DELETE CASCADE,
  code_hash       bytea CONSTRAINT phone_links_code_hash_key UNIQUE,
  code_expires_at timestamptz NOT NULL,
  token_hash      bytea CONSTRAINT phone_links_token_hash_key UNIQUE,
  name            text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  linked_at       timestamptz,
  last_upload_at  timestamptz,
  CONSTRAINT phone_links_hash_lengths CHECK (
    (code_hash IS NULL OR octet_length(code_hash) = 32) AND (token_hash IS NULL OR octet_length(token_hash) = 32)),
  -- Waiting for the app (a code, no token) or linked (a token, a name, no code).
  CONSTRAINT phone_links_state CHECK (
    (token_hash IS NULL AND code_hash IS NOT NULL AND linked_at IS NULL)
    OR (token_hash IS NOT NULL AND code_hash IS NULL AND linked_at IS NOT NULL AND name IS NOT NULL)),
  CONSTRAINT phone_links_name_length CHECK (char_length(name) BETWEEN 1 AND 80)
);

CREATE INDEX phone_links_user_id_idx ON vitallink.phone_links (user_id);

ALTER TABLE vitallink.phone_links ENABLE ROW LEVEL SECURITY;
