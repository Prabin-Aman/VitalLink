-- Baseline: private schema lockdown, share scopes, and the append-only audit log.
--
-- docs/04-database.md §4.1 and §4.3, docs/06-security-and-consent.md §6.1–6.2.

REVOKE ALL ON SCHEMA vitallink FROM PUBLIC;

DO $$
DECLARE r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('REVOKE ALL ON SCHEMA vitallink FROM %I', r);
      EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA vitallink FROM %I', r);
      EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA vitallink REVOKE ALL ON TABLES FROM %I', r);
      EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA vitallink REVOKE ALL ON SEQUENCES FROM %I', r);
      EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA vitallink REVOKE ALL ON FUNCTIONS FROM %I', r);
    END IF;
  END LOOP;
END $$;

CREATE TYPE vitallink.share_scope AS ENUM ('vitals', 'activity', 'sleep', 'nutrition', 'records');

CREATE TYPE vitallink.audit_action AS ENUM ('read', 'share_granted', 'share_revoked', 'share_bound');

CREATE TYPE vitallink.access_outcome AS ENUM ('authorised', 'limited', 'blocked');

CREATE TYPE vitallink.refusal_reason AS ENUM ('no_grant', 'scope_not_granted', 'revoked', 'not_yet_valid', 'expired', 'email_unconfirmed');

CREATE TABLE vitallink.audit_log (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  at              timestamptz NOT NULL DEFAULT now(),
  actor_user_id   uuid,                 -- null = the system (nightly job)
  subject_user_id uuid NOT NULL,
  action          vitallink.audit_action NOT NULL,
  scope           vitallink.share_scope,
  outcome         vitallink.access_outcome NOT NULL,
  reason          vitallink.refusal_reason,
  consent_id      uuid,
  request_id      text,                 -- null for job entries
  CONSTRAINT audit_reason_only_when_blocked CHECK ((outcome = 'blocked') = (reason IS NOT NULL)),
  CONSTRAINT audit_read_has_scope CHECK (action <> 'read' OR scope IS NOT NULL),
  CONSTRAINT audit_read_has_actor CHECK (action <> 'read' OR actor_user_id IS NOT NULL)
);

CREATE INDEX audit_log_subject_at ON vitallink.audit_log (subject_user_id, at DESC);

CREATE INDEX audit_log_actor_at ON vitallink.audit_log (actor_user_id, at DESC);

CREATE FUNCTION vitallink.audit_log_is_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is append-only (% blocked)', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE TRIGGER audit_log_no_update_delete BEFORE UPDATE OR DELETE ON vitallink.audit_log FOR EACH ROW EXECUTE FUNCTION vitallink.audit_log_is_append_only();

CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON vitallink.audit_log FOR EACH STATEMENT EXECUTE FUNCTION vitallink.audit_log_is_append_only();

ALTER TABLE vitallink.audit_log ENABLE ROW LEVEL SECURITY;
