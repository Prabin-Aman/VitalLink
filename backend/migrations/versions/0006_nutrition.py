"""Nutrition: foods, meals with copied macros, hydration, targets (docs/04-database.md §4.5).

Revision ID: 0006
Revises: 0005
"""

from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None

# Copied from app/modules/nutrition/catalogue.py (per 100 g); tests/test_nutrition.py keeps them in step.
FOODS = """
        ('fcf2a7de-6e06-5f78-86ed-b5e6a1e60af3', 'Greek yoghurt', 'seeded', 170, 73, 9.9, 3.9, 2.0, 0),
        ('55912f64-edca-5b59-9f0d-e1c44790a357', 'Rolled oats', 'seeded', 40, 379, 13.2, 67.7, 6.5, 10.1),
        ('76ef517a-0c6c-5d01-804c-2f209cc9e098', 'Blueberries', 'seeded', 75, 57, 0.7, 14.5, 0.3, 2.4),
        ('b38b3084-b6a6-561d-a447-96db93ac3acf', 'Honey', 'seeded', 21, 304, 0.3, 82.4, 0, 0.2),
        ('1b9a0f61-2fe0-551f-a27f-bedabd2eb7fb', 'Chicken breast, cooked', 'seeded', 120, 165, 31, 0, 3.6, 0),
        ('985ced3b-e0e0-51c7-b8f5-e4cfdb480914', 'White rice, cooked', 'seeded', 160, 130, 2.7, 28.2, 0.3, 0.4),
        ('ed53f8a1-080b-5423-bd16-2cfd047015e2', 'Brown rice, cooked', 'seeded', 160, 123, 2.7, 25.6, 1.0, 1.6),
        ('0f5a36c1-01d7-5740-bbf0-3c30701aa3ea', 'Avocado', 'seeded', 70, 160, 2.0, 8.5, 14.7, 6.7),
        ('b64a8ab9-6fb5-5fc2-9ae4-15a0a28997a2', 'Mixed greens', 'seeded', 60, 20, 1.8, 3.3, 0.3, 1.9),
        ('1f21b841-920f-533a-a4bf-221cddee2100', 'Whey protein powder', 'seeded', 30, 400, 80, 8, 6, 0),
        ('e38aa0b3-1427-5abd-b576-ceb7057e3c3f', 'Banana', 'seeded', 118, 89, 1.1, 22.8, 0.3, 2.6),
        ('82e50920-ad0d-5ad0-bf09-2a8d04951da5', 'Salmon, cooked', 'seeded', 140, 206, 22.1, 0, 12.4, 0),
        ('24a2b185-0a6a-50d5-8973-bf88ee01f153', 'Sweet potato, baked', 'seeded', 150, 90, 2.0, 20.7, 0.2, 3.3),
        ('5ed8fa6f-b3a2-5171-a2f5-fe300e796238', 'Broccoli, cooked', 'seeded', 90, 35, 2.4, 7.2, 0.4, 3.3),
        ('46e6c294-3676-596a-8f4a-a63e9cc5f074', 'Egg', 'seeded', 50, 143, 12.6, 0.7, 9.5, 0),
        ('afce7466-3be7-5c9d-9033-4bb1fdbbcee9', 'Whole wheat bread', 'seeded', 32, 247, 13, 41, 3.4, 7),
        ('7e065a53-b288-560f-a3b3-07158795d615', 'Peanut butter', 'seeded', 32, 588, 25, 20, 50, 6),
        ('5554a102-949b-5afd-b6d3-e4743a2d3659', 'Apple', 'seeded', 180, 52, 0.3, 13.8, 0.2, 2.4),
        ('7e1d52a4-b761-5dd6-b9a5-459bd670a328', 'Almonds', 'seeded', 28, 579, 21.2, 21.6, 49.9, 12.5),
        ('bb496b68-bf7c-5ca3-bb1f-ede1abd9a582', 'Milk, 2%', 'seeded', 240, 50, 3.3, 4.8, 2.0, 0),
        ('cb3ef9f2-b707-5f4b-a517-7384ebabe078', 'Cheddar cheese', 'seeded', 28, 403, 24.9, 1.3, 33.1, 0),
        ('d3c5b4cf-a958-5c41-8905-d25b7bb3f1ec', 'Black beans, cooked', 'seeded', 130, 132, 8.9, 23.7, 0.5, 8.7),
        ('2b55fa2c-e93a-543c-99a5-8f903a49ddd4', 'Pasta, cooked', 'seeded', 140, 158, 5.8, 30.9, 0.9, 1.8),
        ('136b14dc-82a4-53c3-8854-332dd5ca1c0c', 'Ground beef, 90% lean, cooked', 'seeded', 100, 217, 26.1, 0, 11.7, 0),
        ('d242a871-c713-5aaa-92a8-55343103a132', 'Tofu, firm', 'seeded', 100, 144, 15.8, 2.8, 8.7, 2.3),
        ('ed5de2a3-c1f5-5458-9fde-57945e5a1077', 'Olive oil', 'seeded', 14, 884, 0, 0, 100, 0),
        ('30075b4e-318e-5fc5-9974-cf07adb15c9a', 'Orange', 'seeded', 140, 47, 0.9, 11.8, 0.1, 2.4),
        ('70390cbb-1245-57aa-94e6-d170de7f4106', 'Spinach', 'seeded', 30, 23, 2.9, 3.6, 0.4, 2.2),
        ('096465a2-bdc7-5db5-b3ae-62e6810fe2bd', 'Flour tortilla', 'seeded', 45, 304, 8, 50, 8, 3),
        ('b9a75ad8-227b-542e-9f32-bbf36589b8da', 'Hummus', 'seeded', 30, 166, 7.9, 14.3, 9.6, 6)
"""

MACROS_NONNEGATIVE = "kcal >= 0 AND protein_g >= 0 AND carbs_g >= 0 AND fat_g >= 0 AND fibre_g >= 0"


def upgrade() -> None:
    op.execute("CREATE TYPE vitallink.food_source AS ENUM ('seeded', 'user')")
    op.execute("CREATE TYPE vitallink.meal_slot AS ENUM ('breakfast', 'lunch', 'dinner', 'snack')")

    op.execute(
        f"""
        CREATE TABLE vitallink.foods (
          id            uuid CONSTRAINT foods_pkey PRIMARY KEY,
          name          text NOT NULL,
          brand         text,
          source        vitallink.food_source NOT NULL,
          owner_user_id uuid CONSTRAINT foods_owner_user_id_fkey
                        REFERENCES vitallink.users (id) ON DELETE CASCADE,
          serving_g     numeric(6, 1) NOT NULL,
          kcal          numeric(6, 1) NOT NULL,
          protein_g     numeric(6, 1) NOT NULL,
          carbs_g       numeric(6, 1) NOT NULL,
          fat_g         numeric(6, 1) NOT NULL,
          fibre_g       numeric(6, 1) NOT NULL,
          CONSTRAINT foods_owner_matches_source CHECK ((source = 'user') = (owner_user_id IS NOT NULL)),
          CONSTRAINT foods_macros_nonnegative CHECK ({MACROS_NONNEGATIVE}),
          CONSTRAINT foods_serving_positive CHECK (serving_g > 0),
          CONSTRAINT foods_name_length CHECK (char_length(name) BETWEEN 1 AND 80)
        )
        """
    )
    op.execute("CREATE INDEX foods_owner_user_id_idx ON vitallink.foods (owner_user_id)")
    op.execute(
        "CREATE UNIQUE INDEX foods_seeded_name_key ON vitallink.foods (lower(name)) WHERE source = 'seeded'"
    )
    op.execute(
        "INSERT INTO vitallink.foods (id, name, source, serving_g, kcal, protein_g, carbs_g, fat_g, fibre_g) "
        "VALUES " + FOODS
    )

    op.execute(
        """
        CREATE TABLE vitallink.meal_entries (
          id         uuid CONSTRAINT meal_entries_pkey PRIMARY KEY,
          user_id    uuid NOT NULL CONSTRAINT meal_entries_user_id_fkey
                     REFERENCES vitallink.users (id) ON DELETE CASCADE,
          slot       vitallink.meal_slot NOT NULL,
          name       text NOT NULL,
          eaten_at   timestamptz NOT NULL,
          created_at timestamptz NOT NULL DEFAULT now(),
          CONSTRAINT meal_entries_name_length CHECK (char_length(name) BETWEEN 1 AND 60)
        )
        """
    )
    op.execute("CREATE INDEX meal_entries_user_eaten_idx ON vitallink.meal_entries (user_id, eaten_at)")

    op.execute(
        f"""
        CREATE TABLE vitallink.meal_items (
          id         uuid CONSTRAINT meal_items_pkey PRIMARY KEY,
          meal_id    uuid NOT NULL CONSTRAINT meal_items_meal_id_fkey
                     REFERENCES vitallink.meal_entries (id) ON DELETE CASCADE,
          food_id    uuid CONSTRAINT meal_items_food_id_fkey
                     REFERENCES vitallink.foods (id) ON DELETE SET NULL,
          position   smallint NOT NULL,
          label      text NOT NULL,
          quantity_g numeric(7, 1) NOT NULL,
          kcal       numeric(7, 1) NOT NULL,
          protein_g  numeric(6, 1) NOT NULL,
          carbs_g    numeric(6, 1) NOT NULL,
          fat_g      numeric(6, 1) NOT NULL,
          fibre_g    numeric(6, 1) NOT NULL,
          CONSTRAINT meal_items_quantity_positive CHECK (quantity_g > 0),
          CONSTRAINT meal_items_macros_nonnegative CHECK ({MACROS_NONNEGATIVE}),
          CONSTRAINT meal_items_label_length CHECK (char_length(label) BETWEEN 1 AND 80)
        )
        """
    )
    op.execute("CREATE INDEX meal_items_meal_id_idx ON vitallink.meal_items (meal_id)")
    op.execute("CREATE INDEX meal_items_food_id_idx ON vitallink.meal_items (food_id)")

    op.execute(
        """
        CREATE TABLE vitallink.hydration_logs (
          id        uuid CONSTRAINT hydration_logs_pkey PRIMARY KEY,
          user_id   uuid NOT NULL CONSTRAINT hydration_logs_user_id_fkey
                    REFERENCES vitallink.users (id) ON DELETE CASCADE,
          logged_at timestamptz NOT NULL,
          ml        smallint NOT NULL,
          CONSTRAINT hydration_logs_ml_range CHECK (ml BETWEEN 1 AND 3000)
        )
        """
    )
    op.execute("CREATE INDEX hydration_logs_user_logged_idx ON vitallink.hydration_logs (user_id, logged_at)")

    op.execute(
        """
        CREATE TABLE vitallink.nutrition_targets (
          user_id          uuid CONSTRAINT nutrition_targets_pkey PRIMARY KEY
                           CONSTRAINT nutrition_targets_user_id_fkey
                           REFERENCES vitallink.users (id) ON DELETE CASCADE,
          kcal             smallint NOT NULL DEFAULT 2200,
          protein_g        smallint NOT NULL DEFAULT 130,
          carbs_g          smallint NOT NULL DEFAULT 250,
          fat_g            smallint NOT NULL DEFAULT 70,
          fibre_g          smallint NOT NULL DEFAULT 30,
          water_ml         smallint NOT NULL DEFAULT 2500,
          glass_ml         smallint NOT NULL DEFAULT 250,
          weekly_target_kg numeric(3, 2) NOT NULL DEFAULT 0,
          updated_at       timestamptz NOT NULL DEFAULT now(),
          CONSTRAINT nutrition_targets_kcal_range CHECK (kcal BETWEEN 800 AND 6000),
          CONSTRAINT nutrition_targets_macro_ranges CHECK (protein_g BETWEEN 0 AND 500 AND carbs_g BETWEEN 0 AND 1000
            AND fat_g BETWEEN 0 AND 400 AND fibre_g BETWEEN 0 AND 150),
          CONSTRAINT nutrition_targets_water_range CHECK (water_ml BETWEEN 500 AND 8000),
          CONSTRAINT nutrition_targets_glass_range CHECK (glass_ml BETWEEN 100 AND 1000),
          CONSTRAINT nutrition_targets_weekly_target_range CHECK (weekly_target_kg BETWEEN -1 AND 1)
        )
        """
    )

    for table in ("foods", "meal_entries", "meal_items", "hydration_logs", "nutrition_targets"):
        op.execute(f"ALTER TABLE vitallink.{table} ENABLE ROW LEVEL SECURITY")


def downgrade() -> None:
    for table in ("nutrition_targets", "hydration_logs", "meal_items", "meal_entries", "foods"):
        op.execute(f"DROP TABLE vitallink.{table}")
    op.execute("DROP TYPE vitallink.meal_slot")
    op.execute("DROP TYPE vitallink.food_source")
