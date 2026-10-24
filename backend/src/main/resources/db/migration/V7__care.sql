-- Care: medications with schedules and a dose log, user-tracked appointments, conditions and allergies,
-- the emergency medical ID and its contacts (docs/04-database.md §4.6).
--
-- Appointments are a personal tracker: the user confirms them; there is no booking with a provider (D5).
-- The emergency ID is shown on the phone (D2); nothing here is reachable without signing in.

CREATE TYPE vitallink.dose_form AS ENUM ('tablet', 'capsule', 'liquid', 'inhaler', 'injection', 'topical', 'other');

CREATE TYPE vitallink.med_status AS ENUM ('active', 'stopped');

CREATE TYPE vitallink.dose_status AS ENUM ('taken', 'skipped');

CREATE TYPE vitallink.visit_mode AS ENUM ('in_person', 'telehealth');

CREATE TYPE vitallink.appointment_state AS ENUM ('scheduled', 'confirmed', 'cancelled');

CREATE TYPE vitallink.condition_kind AS ENUM ('chronic', 'allergy');

CREATE TYPE vitallink.organ_donor AS ENUM ('yes', 'no', 'undecided');

CREATE TABLE vitallink.medications (
  id            uuid CONSTRAINT medications_pkey PRIMARY KEY,
  user_id       uuid NOT NULL CONSTRAINT medications_user_id_fkey
                REFERENCES vitallink.users (id) ON DELETE CASCADE,
  name          text NOT NULL,
  form          vitallink.dose_form NOT NULL,
  strength      text NOT NULL,
  instructions  text NOT NULL DEFAULT '',
  prescriber    text NOT NULL DEFAULT '',
  refills_left  smallint NOT NULL DEFAULT 0,
  refill_due_on date,
  status        vitallink.med_status NOT NULL DEFAULT 'active',
  started_on    date NOT NULL,
  stopped_on    date,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT medications_name_length CHECK (char_length(name) BETWEEN 1 AND 80),
  CONSTRAINT medications_strength_length CHECK (char_length(strength) BETWEEN 1 AND 40),
  CONSTRAINT medications_instructions_length CHECK (char_length(instructions) <= 200),
  CONSTRAINT medications_prescriber_length CHECK (char_length(prescriber) <= 80),
  CONSTRAINT medications_refills_range CHECK (refills_left BETWEEN 0 AND 99),
  CONSTRAINT medications_stopped_has_date CHECK ((status = 'stopped') = (stopped_on IS NOT NULL)),
  CONSTRAINT medications_stop_after_start CHECK (stopped_on IS NULL OR stopped_on >= started_on)
);

CREATE INDEX medications_user_status_idx ON vitallink.medications (user_id, status);

-- One row per time of day. days_mask: bit 0 = Monday … bit 6 = Sunday; 127 = every day.
CREATE TABLE vitallink.medication_schedules (
  id            uuid CONSTRAINT medication_schedules_pkey PRIMARY KEY,
  medication_id uuid NOT NULL CONSTRAINT medication_schedules_medication_id_fkey
                REFERENCES vitallink.medications (id) ON DELETE CASCADE,
  local_time    time NOT NULL,
  days_mask     smallint NOT NULL DEFAULT 127,
  CONSTRAINT medication_schedules_medication_time_key UNIQUE (medication_id, local_time),
  CONSTRAINT medication_schedules_days_range CHECK (days_mask BETWEEN 1 AND 127),
  CONSTRAINT medication_schedules_whole_minutes CHECK (date_trunc('minute', local_time) = local_time)
);

-- Logging the same dose twice updates it (natural key). "due" and "missed" are computed, never stored.
CREATE TABLE vitallink.dose_logs (
  id            uuid CONSTRAINT dose_logs_pkey PRIMARY KEY,
  medication_id uuid NOT NULL CONSTRAINT dose_logs_medication_id_fkey
                REFERENCES vitallink.medications (id) ON DELETE CASCADE,
  scheduled_for timestamptz NOT NULL,
  status        vitallink.dose_status NOT NULL,
  logged_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dose_logs_medication_scheduled_key UNIQUE (medication_id, scheduled_for)
);

CREATE TABLE vitallink.appointments (
  id             uuid CONSTRAINT appointments_pkey PRIMARY KEY,
  user_id        uuid NOT NULL CONSTRAINT appointments_user_id_fkey
                 REFERENCES vitallink.users (id) ON DELETE CASCADE,
  clinician_name text NOT NULL,
  specialty      text NOT NULL DEFAULT '',
  starts_at      timestamptz NOT NULL,
  duration_min   smallint NOT NULL DEFAULT 30,
  mode           vitallink.visit_mode NOT NULL,
  location       text NOT NULL DEFAULT '',
  state          vitallink.appointment_state NOT NULL DEFAULT 'scheduled',
  notes          text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT appointments_clinician_length CHECK (char_length(clinician_name) BETWEEN 1 AND 80),
  CONSTRAINT appointments_specialty_length CHECK (char_length(specialty) <= 80),
  CONSTRAINT appointments_location_length CHECK (char_length(location) <= 200),
  CONSTRAINT appointments_notes_length CHECK (char_length(notes) <= 1000),
  CONSTRAINT appointments_duration_range CHECK (duration_min BETWEEN 5 AND 600)
);

CREATE INDEX appointments_user_starts_idx ON vitallink.appointments (user_id, starts_at);

CREATE TABLE vitallink.conditions (
  id         uuid CONSTRAINT conditions_pkey PRIMARY KEY,
  user_id    uuid NOT NULL CONSTRAINT conditions_user_id_fkey
             REFERENCES vitallink.users (id) ON DELETE CASCADE,
  label      text NOT NULL,
  kind       vitallink.condition_kind NOT NULL,
  note       text,
  noted_on   date,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT conditions_label_length CHECK (char_length(label) BETWEEN 1 AND 80),
  CONSTRAINT conditions_note_length CHECK (char_length(note) <= 80)
);

CREATE UNIQUE INDEX conditions_user_label_kind_key ON vitallink.conditions (user_id, lower(label), kind);

CREATE TABLE vitallink.emergency_profiles (
  user_id             uuid CONSTRAINT emergency_profiles_pkey PRIMARY KEY
                      CONSTRAINT emergency_profiles_user_id_fkey
                      REFERENCES vitallink.users (id) ON DELETE CASCADE,
  organ_donor         vitallink.organ_donor,
  organ_donor_note    text,
  preferred_hospital  text,
  notes               text NOT NULL DEFAULT '',
  show_on_lock_screen boolean NOT NULL DEFAULT false,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT emergency_profiles_donor_note_length CHECK (char_length(organ_donor_note) <= 80),
  CONSTRAINT emergency_profiles_hospital_length CHECK (char_length(preferred_hospital) <= 120),
  CONSTRAINT emergency_profiles_notes_length CHECK (char_length(notes) <= 500)
);

CREATE TABLE vitallink.emergency_contacts (
  id         uuid CONSTRAINT emergency_contacts_pkey PRIMARY KEY,
  user_id    uuid NOT NULL CONSTRAINT emergency_contacts_user_id_fkey
             REFERENCES vitallink.users (id) ON DELETE CASCADE,
  name       text NOT NULL,
  relation   text NOT NULL,
  phone      text NOT NULL,
  is_primary boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT emergency_contacts_name_length CHECK (char_length(name) BETWEEN 1 AND 80),
  CONSTRAINT emergency_contacts_relation_length CHECK (char_length(relation) BETWEEN 1 AND 40),
  CONSTRAINT emergency_contacts_phone_e164 CHECK (phone ~ '^\+[1-9][0-9]{6,14}$')
);

CREATE INDEX emergency_contacts_user_id_idx ON vitallink.emergency_contacts (user_id);

-- At most one primary contact per user.
CREATE UNIQUE INDEX emergency_contacts_one_primary ON vitallink.emergency_contacts (user_id) WHERE is_primary;

ALTER TABLE vitallink.medications ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.medication_schedules ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.dose_logs ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.appointments ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.conditions ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.emergency_profiles ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.emergency_contacts ENABLE ROW LEVEL SECURITY;
