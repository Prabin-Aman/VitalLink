"""users and profiles (docs/04-database.md §4.3).

Revision ID: 0002
Revises: 0001
"""

from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("CREATE TYPE vitallink.sex AS ENUM ('female', 'male', 'other', 'undisclosed')")
    op.execute("CREATE TYPE vitallink.units AS ENUM ('metric', 'imperial')")
    op.execute("CREATE TYPE vitallink.theme AS ENUM ('light', 'dark', 'system')")

    op.execute(
        """
        CREATE TABLE vitallink.users (
          id                 uuid PRIMARY KEY,
          auth_subject       uuid NOT NULL,
          email              text NOT NULL,
          email_confirmed_at timestamptz,
          timezone           text NOT NULL DEFAULT 'America/Chicago',
          created_at         timestamptz NOT NULL DEFAULT now(),
          CONSTRAINT users_auth_subject_key UNIQUE (auth_subject),
          CONSTRAINT users_email_key UNIQUE (email),
          CONSTRAINT users_email_lowercase CHECK (email = lower(email))
        )
        """
    )

    op.execute(
        """
        CREATE TABLE vitallink.profiles (
          user_id           uuid PRIMARY KEY
                            CONSTRAINT profiles_user_id_fkey REFERENCES vitallink.users (id) ON DELETE CASCADE,
          first_name        text,
          last_name         text,
          date_of_birth     date,
          sex               vitallink.sex,
          height_cm         numeric(4, 1),
          phone             text,
          zip               text,
          city              text,
          language          text NOT NULL DEFAULT 'en-US',
          units             vitallink.units NOT NULL DEFAULT 'imperial',
          theme             vitallink.theme NOT NULL DEFAULT 'system',
          daily_steps_goal  integer NOT NULL DEFAULT 10000,
          move_kcal_goal    integer NOT NULL DEFAULT 500,
          exercise_min_goal smallint NOT NULL DEFAULT 30,
          stand_hours_goal  smallint NOT NULL DEFAULT 12,
          sleep_goal_min    smallint NOT NULL DEFAULT 480,
          updated_at        timestamptz NOT NULL DEFAULT now(),
          CONSTRAINT profiles_height_range CHECK (height_cm BETWEEN 50 AND 272),
          CONSTRAINT profiles_zip_format CHECK (zip ~ '^[0-9]{5}$'),
          CONSTRAINT profiles_phone_e164 CHECK (phone ~ '^\\+[1-9][0-9]{6,14}$'),
          CONSTRAINT profiles_dob_range CHECK (date_of_birth >= '1900-01-01'),
          CONSTRAINT profiles_steps_goal_range CHECK (daily_steps_goal BETWEEN 1000 AND 100000),
          CONSTRAINT profiles_move_goal_range CHECK (move_kcal_goal BETWEEN 50 AND 5000),
          CONSTRAINT profiles_exercise_goal_range CHECK (exercise_min_goal BETWEEN 5 AND 600),
          CONSTRAINT profiles_stand_goal_range CHECK (stand_hours_goal BETWEEN 1 AND 24),
          CONSTRAINT profiles_sleep_goal_range CHECK (sleep_goal_min BETWEEN 180 AND 900)
        )
        """
    )

    for table in ("users", "profiles"):
        op.execute(f"ALTER TABLE vitallink.{table} ENABLE ROW LEVEL SECURITY")


def downgrade() -> None:
    op.execute("DROP TABLE vitallink.profiles")
    op.execute("DROP TABLE vitallink.users")
    op.execute("DROP TYPE vitallink.theme")
    op.execute("DROP TYPE vitallink.units")
    op.execute("DROP TYPE vitallink.sex")
