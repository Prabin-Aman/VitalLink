-- Records and labs (docs/04-database.md §4.6). A record is metadata plus, optionally, one stored file
-- (PDF or image) in the private bucket; the table keeps the storage key, never a URL. Lab panels are typed
-- in by the user (decision D6) and usually point at the record holding the lab's PDF.

CREATE TYPE vitallink.record_type AS ENUM
  ('lab', 'prescription', 'imaging', 'visit', 'vaccination', 'allergy', 'procedure', 'upload');

CREATE TYPE vitallink.file_status AS ENUM ('none', 'pending', 'attached');

CREATE TYPE vitallink.interpretation AS ENUM ('normal', 'borderline', 'abnormal');

CREATE TABLE vitallink.health_records (
  id                  uuid CONSTRAINT health_records_pkey PRIMARY KEY,
  user_id             uuid NOT NULL CONSTRAINT health_records_user_id_fkey
                      REFERENCES vitallink.users (id) ON DELETE CASCADE,
  type                vitallink.record_type NOT NULL,
  title               text NOT NULL,
  source              text NOT NULL DEFAULT '',
  note                text,
  record_date         date NOT NULL,
  file_status         vitallink.file_status NOT NULL DEFAULT 'none',
  file_key            text,
  file_name           text,
  file_size           integer,
  file_type           text,
  upload_requested_at timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT health_records_title_length CHECK (char_length(title) BETWEEN 1 AND 120),
  CONSTRAINT health_records_source_length CHECK (char_length(source) <= 120),
  CONSTRAINT health_records_note_length CHECK (char_length(note) <= 200),
  CONSTRAINT health_records_file_name_length CHECK (char_length(file_name) BETWEEN 1 AND 200),
  CONSTRAINT health_records_file_size_range CHECK (file_size BETWEEN 1 AND 26214400),
  CONSTRAINT health_records_file_type_allowed
    CHECK (file_type IN ('application/pdf', 'image/jpeg', 'image/png', 'image/heic')),
  -- The file columns follow the status: none → no file; pending → a key and a name, waiting for the upload;
  -- attached → the stored object's size and type were checked by the server.
  CONSTRAINT health_records_file_matches_status CHECK (
    (file_status = 'none' AND file_key IS NULL AND file_name IS NULL AND file_size IS NULL
       AND file_type IS NULL AND upload_requested_at IS NULL)
    OR (file_status = 'pending' AND file_key IS NOT NULL AND file_name IS NOT NULL
       AND upload_requested_at IS NOT NULL AND file_size IS NULL)
    OR (file_status = 'attached' AND file_key IS NOT NULL AND file_name IS NOT NULL
       AND file_size IS NOT NULL AND file_type IS NOT NULL)
  )
);

CREATE INDEX health_records_user_date_idx ON vitallink.health_records (user_id, record_date DESC);

CREATE INDEX health_records_user_type_idx ON vitallink.health_records (user_id, type);

-- The nightly job finds abandoned uploads with this.
CREATE INDEX health_records_pending_idx ON vitallink.health_records (upload_requested_at) WHERE file_status = 'pending';

CREATE TABLE vitallink.lab_panels (
  id           uuid CONSTRAINT lab_panels_pkey PRIMARY KEY,
  user_id      uuid NOT NULL CONSTRAINT lab_panels_user_id_fkey
               REFERENCES vitallink.users (id) ON DELETE CASCADE,
  record_id    uuid CONSTRAINT lab_panels_record_id_fkey
               REFERENCES vitallink.health_records (id) ON DELETE SET NULL,
  name         text NOT NULL,
  short_name   text NOT NULL,
  facility     text NOT NULL DEFAULT '',
  laboratory   text NOT NULL DEFAULT '',
  ordered_by   text NOT NULL DEFAULT '',
  collected_at timestamptz NOT NULL,
  reported_at  timestamptz NOT NULL,
  note         text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT lab_panels_record_id_key UNIQUE (record_id),
  CONSTRAINT lab_panels_name_length CHECK (char_length(name) BETWEEN 1 AND 120),
  CONSTRAINT lab_panels_short_name_length CHECK (char_length(short_name) BETWEEN 1 AND 20),
  CONSTRAINT lab_panels_facility_length CHECK (char_length(facility) <= 120),
  CONSTRAINT lab_panels_laboratory_length CHECK (char_length(laboratory) <= 120),
  CONSTRAINT lab_panels_ordered_by_length CHECK (char_length(ordered_by) <= 80),
  CONSTRAINT lab_panels_note_length CHECK (char_length(note) <= 500),
  CONSTRAINT lab_panels_reported_after_collected CHECK (reported_at >= collected_at)
);

CREATE INDEX lab_panels_user_collected_idx ON vitallink.lab_panels (user_id, collected_at DESC);

CREATE TABLE vitallink.lab_results (
  id                      uuid CONSTRAINT lab_results_pkey PRIMARY KEY,
  panel_id                uuid NOT NULL CONSTRAINT lab_results_panel_id_fkey
                          REFERENCES vitallink.lab_panels (id) ON DELETE CASCADE,
  analyte                 text NOT NULL,
  value                   numeric(12, 4) NOT NULL,
  unit                    text NOT NULL DEFAULT '',
  ref_low                 numeric(12, 4) NOT NULL,
  ref_high                numeric(12, 4) NOT NULL,
  decimals                smallint NOT NULL DEFAULT 1,
  reported_interpretation vitallink.interpretation,
  on_report               boolean NOT NULL DEFAULT true,
  position                smallint NOT NULL,
  CONSTRAINT lab_results_analyte_length CHECK (char_length(analyte) BETWEEN 1 AND 80),
  CONSTRAINT lab_results_unit_length CHECK (char_length(unit) <= 30),
  CONSTRAINT lab_results_range_order CHECK (ref_low < ref_high),
  CONSTRAINT lab_results_decimals_range CHECK (decimals BETWEEN 0 AND 4),
  CONSTRAINT lab_results_position_nonnegative CHECK (position >= 0)
);

CREATE UNIQUE INDEX lab_results_panel_analyte_key ON vitallink.lab_results (panel_id, lower(analyte));

ALTER TABLE vitallink.health_records ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.lab_panels ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.lab_results ENABLE ROW LEVEL SECURITY;
