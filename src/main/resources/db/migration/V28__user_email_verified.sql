ALTER TABLE users
  ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users
SET email_verified = TRUE
WHERE email IS NOT NULL AND email <> '' AND email_verified = FALSE;
