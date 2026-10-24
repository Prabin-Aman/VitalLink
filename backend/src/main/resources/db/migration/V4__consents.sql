-- Sharing: consents (docs/04-database.md §4.3, docs/06-security-and-consent.md §6.1).

CREATE TYPE vitallink.grantee_kind AS ENUM ('doctor', 'family', 'other');

CREATE TYPE vitallink.revoke_reason AS ENUM ('owner', 'expired_cleanup', 'grantee_deleted');

CREATE TABLE vitallink.consents (
  id              uuid CONSTRAINT consents_pkey PRIMARY KEY,
  grantor_id      uuid NOT NULL CONSTRAINT consents_grantor_id_fkey
                  REFERENCES vitallink.users (id) ON DELETE CASCADE,
  grantee_email   text NOT NULL,
  -- RESTRICT: deleting a viewer's account must revoke and unbind their grants first, so a later
  -- sign-up with the same email can never inherit them.
  grantee_user_id uuid CONSTRAINT consents_grantee_user_id_fkey
                  REFERENCES vitallink.users (id) ON DELETE RESTRICT,
  grantee_label   text NOT NULL,
  grantee_kind    vitallink.grantee_kind NOT NULL,
  scopes          vitallink.share_scope[] NOT NULL,
  purpose         text NOT NULL DEFAULT '',
  starts_at       timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  revoked_at      timestamptz,
  revoke_reason   vitallink.revoke_reason,
  bound_at        timestamptz,
  CONSTRAINT consents_email_lowercase CHECK (grantee_email = lower(grantee_email)),
  CONSTRAINT consents_not_self CHECK (grantee_user_id IS NULL OR grantee_user_id <> grantor_id),
  CONSTRAINT consents_scopes_present CHECK (cardinality(scopes) > 0),
  CONSTRAINT consents_label_length CHECK (char_length(grantee_label) BETWEEN 1 AND 80),
  CONSTRAINT consents_purpose_length CHECK (char_length(purpose) <= 200),
  CONSTRAINT consents_expiry_after_start CHECK (expires_at IS NULL OR expires_at > starts_at),
  CONSTRAINT consents_revocation_has_reason CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
  CONSTRAINT consents_binding_has_time CHECK (grantee_user_id IS NULL OR bound_at IS NOT NULL),
  CONSTRAINT consents_binding_kept
    CHECK (bound_at IS NULL OR grantee_user_id IS NOT NULL OR revoked_at IS NOT NULL)
);

CREATE INDEX consents_grantor_id_idx ON vitallink.consents (grantor_id);

CREATE INDEX consents_grantee_user_id_idx ON vitallink.consents (grantee_user_id);

CREATE INDEX consents_unbound_email_idx ON vitallink.consents (grantee_email) WHERE grantee_user_id IS NULL;

CREATE UNIQUE INDEX consents_one_live_per_grantee ON vitallink.consents (grantor_id, grantee_email) WHERE revoked_at IS NULL;

ALTER TABLE vitallink.consents ENABLE ROW LEVEL SECURITY;
