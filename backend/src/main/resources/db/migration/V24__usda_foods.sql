-- The FoodData Central id of a USDA food, so each is stored once however many people search for it.
ALTER TABLE vitallink.foods ADD COLUMN fdc_id integer;
ALTER TABLE vitallink.foods ADD CONSTRAINT foods_fdc_id_matches_source CHECK ((source = 'usda') = (fdc_id IS NOT NULL));
ALTER TABLE vitallink.foods ADD CONSTRAINT foods_fdc_id_positive CHECK (fdc_id > 0);
CREATE UNIQUE INDEX foods_fdc_id_key ON vitallink.foods (fdc_id) WHERE fdc_id IS NOT NULL;
