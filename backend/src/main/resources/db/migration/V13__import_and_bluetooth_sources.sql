-- Readings from an uploaded CSV (POST /me/health/import) and from Bluetooth devices paired in the browser.
-- (An Apple Health export imports as 'healthkit', so it merges with phone sync instead of doubling it.)
ALTER TYPE vitallink.reading_source ADD VALUE IF NOT EXISTS 'file_import';
ALTER TYPE vitallink.reading_source ADD VALUE IF NOT EXISTS 'bluetooth';
