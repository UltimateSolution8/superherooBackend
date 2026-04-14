ALTER TABLE users
  ADD COLUMN IF NOT EXISTS bulk_csv_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Allow one phone number per role (BUYER/HELPER) instead of globally unique phone numbers.
ALTER TABLE users
  DROP CONSTRAINT IF EXISTS users_phone_key;

-- Keep the oldest row when historical imports accidentally created same role+phone pairs.
WITH ranked AS (
  SELECT id,
         row_number() OVER (PARTITION BY role, phone ORDER BY created_at ASC, id ASC) AS rn
  FROM users
  WHERE phone IS NOT NULL
)
UPDATE users u
SET phone = NULL
FROM ranked r
WHERE u.id = r.id
  AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_role_phone
  ON users (role, phone)
  WHERE phone IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_users_phone_lookup
  ON users (phone)
  WHERE phone IS NOT NULL;
