-- VitalLink is English only: Spanish (es-US) is no longer offered, so any profile that chose it reads English.
UPDATE vitallink.profiles SET language = 'en-US' WHERE language <> 'en-US';

ALTER TABLE vitallink.profiles DROP CONSTRAINT profiles_language_known;
ALTER TABLE vitallink.profiles ADD CONSTRAINT profiles_language_known CHECK (language = 'en-US');
