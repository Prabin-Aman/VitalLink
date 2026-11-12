-- Foods found in USDA FoodData Central (public domain, CC0): a shared catalogue like the seeded foods, filled as
-- people search. The new enum value is in its own migration: Postgres can't use it in the same transaction.
ALTER TYPE vitallink.food_source ADD VALUE IF NOT EXISTS 'usda';
