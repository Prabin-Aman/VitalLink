-- Phase 10 review (docs/13-review.md): constraints the API already enforced but the database didn't,
-- a same-owner rule for lab panels, file replacement that keeps the old file until the new one is
-- confirmed, and a queue of stored files still to delete.

-- ---------------------------------------------------------------- limits the API applies, now in the schema

ALTER TABLE vitallink.users
  ADD CONSTRAINT users_email_shape CHECK (char_length(email) <= 254 AND position('@' IN email) > 1),
  ADD CONSTRAINT users_timezone_length CHECK (char_length(timezone) BETWEEN 1 AND 64);

ALTER TABLE vitallink.profiles
  ADD CONSTRAINT profiles_first_name_length CHECK (char_length(first_name) BETWEEN 1 AND 60),
  ADD CONSTRAINT profiles_last_name_length CHECK (char_length(last_name) BETWEEN 1 AND 60),
  ADD CONSTRAINT profiles_city_length CHECK (char_length(city) BETWEEN 1 AND 80),
  ADD CONSTRAINT profiles_language_known CHECK (language IN ('en-US', 'es-US'));

ALTER TABLE vitallink.consents
  ADD CONSTRAINT consents_email_shape CHECK (char_length(grantee_email) <= 254 AND position('@' IN grantee_email) > 1);

ALTER TABLE vitallink.devices
  ADD CONSTRAINT devices_name_length CHECK (char_length(name) BETWEEN 1 AND 120),
  ADD CONSTRAINT devices_external_id_length CHECK (char_length(external_id) BETWEEN 1 AND 200),
  ADD CONSTRAINT devices_metric_count CHECK (cardinality(metric_ids) <= 60);

ALTER TABLE vitallink.workouts
  ADD CONSTRAINT workouts_focus_length CHECK (char_length(focus) <= 200),
  ADD CONSTRAINT workouts_external_id_length CHECK (char_length(external_id) BETWEEN 1 AND 200);

-- counts_add_up alone allows a negative count balanced by another.
ALTER TABLE vitallink.ingest_batches
  ADD CONSTRAINT ingest_batches_counts_nonnegative CHECK (inserted >= 0 AND updated >= 0 AND unchanged >= 0);

ALTER TABLE vitallink.meal_items
  ADD CONSTRAINT meal_items_position_nonnegative CHECK (position >= 0);

ALTER TABLE vitallink.conditions
  ADD CONSTRAINT conditions_noted_on_range CHECK (noted_on >= '1900-01-01');

ALTER TABLE vitallink.audit_log
  ADD CONSTRAINT audit_request_id_length CHECK (char_length(request_id) <= 64);

-- ---------------------------------------------------------------- a lab panel's record belongs to the same user

-- The API checks this; the composite key makes the database refuse it too. SET NULL (record_id) keeps the
-- panel's user_id when its record is deleted (Postgres 15+).
ALTER TABLE vitallink.health_records ADD CONSTRAINT health_records_id_user_key UNIQUE (id, user_id);

ALTER TABLE vitallink.lab_panels DROP CONSTRAINT lab_panels_record_id_fkey;

ALTER TABLE vitallink.lab_panels
  ADD CONSTRAINT lab_panels_record_same_user_fkey FOREIGN KEY (record_id, user_id)
  REFERENCES vitallink.health_records (id, user_id) ON DELETE SET NULL (record_id);

-- ---------------------------------------------------------------- replacing a record's file

-- An attached file stays attached while its replacement uploads; confirming swaps them, and the nightly job
-- drops a replacement that never arrived. upload_requested_at dates the replacement.
ALTER TABLE vitallink.health_records
  ADD COLUMN replacement_key text,
  ADD COLUMN replacement_name text,
  ADD CONSTRAINT health_records_replacement_complete CHECK ((replacement_key IS NULL) = (replacement_name IS NULL)),
  ADD CONSTRAINT health_records_replacement_only_attached
    CHECK (replacement_key IS NULL OR (file_status = 'attached' AND upload_requested_at IS NOT NULL)),
  ADD CONSTRAINT health_records_replacement_name_length CHECK (char_length(replacement_name) BETWEEN 1 AND 200);

CREATE INDEX health_records_replacement_idx ON vitallink.health_records (upload_requested_at)
  WHERE replacement_key IS NOT NULL;

-- ---------------------------------------------------------------- stored files still to delete

-- Written in the same transaction as the delete that frees the file; the nightly job deletes the objects
-- and the rows. No foreign key: the queue outlives the record and the account.
CREATE TABLE vitallink.file_deletions (
  key          text CONSTRAINT file_deletions_pkey PRIMARY KEY,
  requested_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT file_deletions_key_length CHECK (char_length(key) BETWEEN 1 AND 300)
);

ALTER TABLE vitallink.file_deletions ENABLE ROW LEVEL SECURITY;
