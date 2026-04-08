-- Dual-role support allows multiple sessions to be issued in quick succession.
-- Refresh JWTs without a unique JWT ID can produce identical token hashes when
-- generated within the same second. Deduplicate legacy rows, then enforce uniqueness.
WITH ranked AS (
  SELECT
    id,
    row_number() OVER (
      PARTITION BY token_hash
      ORDER BY created_at DESC, issued_at DESC, id DESC
    ) AS rn
  FROM refresh_tokens
)
UPDATE refresh_tokens rt
SET revoked_at = COALESCE(rt.revoked_at, now())
FROM ranked r
WHERE rt.id = r.id
  AND r.rn > 1;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'uk_refresh_tokens_token_hash'
  ) THEN
    ALTER TABLE refresh_tokens
      ADD CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash);
  END IF;
END
$$;
