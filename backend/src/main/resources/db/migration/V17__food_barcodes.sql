-- Nutrition › Scan Barcode: a food found by its barcode (Open Food Facts) is kept with it, so the next scan
-- is answered without asking Open Food Facts again.
ALTER TABLE vitallink.foods ADD COLUMN barcode text;
ALTER TABLE vitallink.foods ADD CONSTRAINT foods_barcode_shape CHECK (barcode ~ '^[0-9]{8,14}$');
CREATE UNIQUE INDEX foods_owner_barcode_key ON vitallink.foods (owner_user_id, barcode) WHERE barcode IS NOT NULL;
