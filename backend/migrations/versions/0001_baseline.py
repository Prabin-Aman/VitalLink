"""Baseline: private schema lockdown, share scopes, and the append-only audit log.

docs/04-database.md §4.1 and §4.3, docs/06-security-and-consent.md §6.1–6.2.

Revision ID: 0001
Revises:
"""

from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    # --- Lockdown. Only the API's own role may use the schema. `anon` and `authenticated` exist on
    # Supabase (their key ships inside the app); locally they don't, so revoke only if present.
    op.execute("REVOKE ALL ON SCHEMA vitallink FROM PUBLIC")
    op.execute(
        """
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
        """
    )

    # --- Closed sets used by sharing and the audit log (docs/04-database.md §4.9).
    op.execute(
        "CREATE TYPE vitallink.share_scope AS ENUM ('vitals', 'activity', 'sleep', 'nutrition', 'records')"
    )
    op.execute(
        "CREATE TYPE vitallink.audit_action AS ENUM ('read', 'share_granted', 'share_revoked', 'share_bound')"
    )
    op.execute("CREATE TYPE vitallink.access_outcome AS ENUM ('authorised', 'limited', 'blocked')")
    op.execute(
        "CREATE TYPE vitallink.refusal_reason AS ENUM "
        "('no_grant', 'scope_not_granted', 'revoked', 'not_yet_valid', 'expired', 'email_unconfirmed')"
    )

    # --- Audit log. No foreign keys on purpose: entries outlive accounts and no cascade can touch them.
    # No free text: reasons are an enum, so nothing personal is left after an account is deleted.
    op.execute(
        """
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
        )
        """
    )
    op.execute("CREATE INDEX audit_log_subject_at ON vitallink.audit_log (subject_user_id, at DESC)")
    op.execute("CREATE INDEX audit_log_actor_at ON vitallink.audit_log (actor_user_id, at DESC)")

    # Append-only: neither a bug nor a compromised API role can rewrite history.
    op.execute(
        """
        CREATE FUNCTION vitallink.audit_log_is_append_only() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'audit_log is append-only (% blocked)', TG_OP USING ERRCODE = 'insufficient_privilege';
        END $$
        """
    )
    op.execute(
        "CREATE TRIGGER audit_log_no_update_delete BEFORE UPDATE OR DELETE ON vitallink.audit_log "
        "FOR EACH ROW EXECUTE FUNCTION vitallink.audit_log_is_append_only()"
    )
    op.execute(
        "CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON vitallink.audit_log "
        "FOR EACH STATEMENT EXECUTE FUNCTION vitallink.audit_log_is_append_only()"
    )

    # Deny-all row-level security: a second lock if the schema were ever exposed by mistake.
    op.execute("ALTER TABLE vitallink.audit_log ENABLE ROW LEVEL SECURITY")


def downgrade() -> None:
    op.execute("DROP TABLE vitallink.audit_log")
    op.execute("DROP FUNCTION vitallink.audit_log_is_append_only()")
    op.execute("DROP TYPE vitallink.refusal_reason")
    op.execute("DROP TYPE vitallink.access_outcome")
    op.execute("DROP TYPE vitallink.audit_action")
    op.execute("DROP TYPE vitallink.share_scope")
