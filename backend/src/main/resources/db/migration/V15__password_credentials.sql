-- Email/password accounts for local and test runs (POST /v1/auth/sign-up, /sign-in) until Supabase Auth.
-- Only a BCrypt hash is stored. Five wrong passwords lock sign-in for 15 minutes.
CREATE TABLE vitallink.credentials (
  user_id         uuid PRIMARY KEY
                  CONSTRAINT credentials_user_id_fkey REFERENCES vitallink.users (id) ON DELETE CASCADE,
  password_hash   text NOT NULL,
  failed_attempts smallint NOT NULL DEFAULT 0,
  locked_until    timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT credentials_hash_shape CHECK (password_hash ~ '^\$2[aby]\$[0-9]{2}\$.{53}$'),
  CONSTRAINT credentials_failed_attempts_range CHECK (failed_attempts BETWEEN 0 AND 1000)
);

ALTER TABLE vitallink.credentials ENABLE ROW LEVEL SECURITY;
