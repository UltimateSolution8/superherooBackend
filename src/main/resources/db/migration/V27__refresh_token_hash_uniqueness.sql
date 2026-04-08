-- Dual-role support allows multiple sessions to be issued in quick succession.
-- Legacy refresh JWTs were minted without a random JWT ID, so two sessions created
-- in the same second could produce the same token hash. Keep one row per hash active
-- and revoke the rest; then enforce uniqueness only for non-revoked rows.
WITH ranked AS (
  SELECT
    id,
    row_number() OVER (
      PARTITION BY token_hash
      ORDER BY (CASE WHEN revoked_at IS NULL THEN 1 ELSE 0 END) DESC, created_at DESC, issued_at DESC, id DESC
    ) AS rn
  FROM refresh_tokens
)
UPDATE refresh_tokens rt
SET revoked_at = COALESCE(rt.revoked_at, now())
FROM ranked r
WHERE rt.id = r.id
  AND r.rn > 1;

-- If an earlier rollout created a strict unique constraint, remove it first.
ALTER TABLE refresh_tokens
  DROP CONSTRAINT IF EXISTS uk_refresh_tokens_token_hash;

-- Enforce uniqueness only for active (non-revoked) refresh token hashes.
CREATE UNIQUE INDEX IF NOT EXISTS uq_refresh_tokens_active_hash
  ON refresh_tokens (token_hash)
  WHERE revoked_at IS NULL;
