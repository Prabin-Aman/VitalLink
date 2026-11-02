-- Forgot password: one row per reset link. Only the SHA-256 of the token is stored; a link works once,
-- for 30 minutes.
CREATE TABLE vitallink.password_resets (
  token_hash bytea PRIMARY KEY,
  user_id    uuid NOT NULL CONSTRAINT password_resets_user_id_fkey
             REFERENCES vitallink.users (id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at    timestamptz,
  CONSTRAINT password_resets_hash_length CHECK (octet_length(token_hash) = 32),
  CONSTRAINT password_resets_expiry_after_creation CHECK (expires_at > created_at)
);

CREATE INDEX password_resets_user_id_idx ON vitallink.password_resets (user_id, created_at DESC);

ALTER TABLE vitallink.password_resets ENABLE ROW LEVEL SECURITY;
