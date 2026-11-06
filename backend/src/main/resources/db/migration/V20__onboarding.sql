-- When someone finished or chose to leave Create Account's setup steps. Null sends them there, however they signed
-- up (Google sign-ups skip the form). Accounts that already gave details aren't asked again.
ALTER TABLE vitallink.profiles ADD COLUMN onboarded_at timestamptz;

UPDATE vitallink.profiles
SET onboarded_at = now()
WHERE date_of_birth IS NOT NULL OR height_cm IS NOT NULL OR phone IS NOT NULL OR zip IS NOT NULL;
