-- Normalize existing contact values before enforcing stronger validation.
UPDATE users
SET email = NULLIF(lower(trim(email)), '')
WHERE email IS NOT NULL;

UPDATE users
SET phone = NULLIF(trim(phone), '')
WHERE phone IS NOT NULL;

UPDATE users
SET phone = regexp_replace(phone, '\D', '', 'g')
WHERE phone IS NOT NULL;

-- Canonicalize common India prefixes.
UPDATE users
SET phone = substring(phone FROM 3)
WHERE phone ~ '^91[6-9][0-9]{9}$';

UPDATE users
SET phone = substring(phone FROM 2)
WHERE phone ~ '^0[6-9][0-9]{9}$';

-- Drop duplicate canonical values safely (keep oldest record value).
WITH email_ranked AS (
  SELECT id,
         row_number() OVER (PARTITION BY email ORDER BY created_at ASC, id ASC) AS rn
  FROM users
  WHERE email IS NOT NULL
)
UPDATE users u
SET email = NULL
FROM email_ranked r
WHERE u.id = r.id
  AND r.rn > 1;

WITH phone_ranked AS (
  SELECT id,
         row_number() OVER (PARTITION BY phone ORDER BY created_at ASC, id ASC) AS rn
  FROM users
  WHERE phone IS NOT NULL
)
UPDATE users u
SET phone = NULL
FROM phone_ranked r
WHERE u.id = r.id
  AND r.rn > 1;

-- Remove invalid values.
UPDATE users
SET email = NULL
WHERE email IS NOT NULL
  AND (
    length(email) > 254
    OR email !~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,63}$'
    OR email LIKE '%..%'
  );

UPDATE users
SET phone = NULL
WHERE phone IS NOT NULL
  AND phone !~ '^[6-9][0-9]{9}$';

ALTER TABLE users
  ADD CONSTRAINT chk_users_email_format
  CHECK (email IS NULL OR email ~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,63}$');

ALTER TABLE users
  ADD CONSTRAINT chk_users_phone_india
  CHECK (phone IS NULL OR phone ~ '^[6-9][0-9]{9}$');
