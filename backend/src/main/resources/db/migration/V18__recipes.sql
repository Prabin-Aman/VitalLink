-- Nutrition › Recipes: a dish the user makes often, built from foods (seeded or their own) with grams, cooked
-- in a number of servings. Macros are computed from the foods on read; logging a serving writes an ordinary
-- meal (POST /me/meals), so a later change to the recipe never rewrites past meals.
CREATE TABLE vitallink.recipes (
  id         uuid CONSTRAINT recipes_pkey PRIMARY KEY,
  user_id    uuid NOT NULL CONSTRAINT recipes_user_id_fkey
             REFERENCES vitallink.users (id) ON DELETE CASCADE,
  name       text NOT NULL,
  servings   smallint NOT NULL,
  notes      text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT recipes_name_length CHECK (char_length(name) BETWEEN 1 AND 60),
  CONSTRAINT recipes_servings_range CHECK (servings BETWEEN 1 AND 20),
  CONSTRAINT recipes_notes_length CHECK (notes IS NULL OR char_length(notes) <= 500)
);

CREATE INDEX recipes_user_id_idx ON vitallink.recipes (user_id, name);

CREATE TABLE vitallink.recipe_items (
  id         uuid CONSTRAINT recipe_items_pkey PRIMARY KEY,
  recipe_id  uuid NOT NULL CONSTRAINT recipe_items_recipe_id_fkey
             REFERENCES vitallink.recipes (id) ON DELETE CASCADE,
  food_id    uuid NOT NULL CONSTRAINT recipe_items_food_id_fkey
             REFERENCES vitallink.foods (id) ON DELETE CASCADE,
  position   smallint NOT NULL,
  quantity_g numeric(7, 1) NOT NULL,
  CONSTRAINT recipe_items_quantity_range CHECK (quantity_g > 0 AND quantity_g <= 5000),
  CONSTRAINT recipe_items_position_key UNIQUE (recipe_id, position)
);

CREATE INDEX recipe_items_food_id_idx ON vitallink.recipe_items (food_id);

ALTER TABLE vitallink.recipes ENABLE ROW LEVEL SECURITY;

ALTER TABLE vitallink.recipe_items ENABLE ROW LEVEL SECURITY;
