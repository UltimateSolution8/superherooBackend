-- Revoke the well-known demo credentials seeded by V5, V6 and V9.
--
-- V6 and V9 seeded production-reachable accounts with passwords that are
-- committed in this repository ('Admin@12345', 'Buyer@12345', 'Helper@12345').
-- Anyone who has read the repo can log in as a platform admin. Flyway migrations
-- are immutable once applied, so this is a forward fix rather than an edit.
--
-- password_hash = NULL makes password login fail closed: AuthService rejects a
-- null/blank hash with "Password login not enabled for this user". The rows are
-- kept rather than deleted because tasks, payments and audit rows reference them.

-- 1. Demo buyer/helper accounts seeded by V6.
UPDATE users
SET password_hash = NULL
WHERE id IN (
  '10000000-0000-0000-0000-000000000911',
  '10000000-0000-0000-0000-000000000912',
  '10000000-0000-0000-0000-000000000913',
  '10000000-0000-0000-0000-000000000914'
);

-- 2. Any account still carrying a seeded demo password, matched by the exact
--    literals used in V6/V9 rather than by id, so re-seeded rows are caught too.
UPDATE users
SET password_hash = NULL
WHERE password_hash IS NOT NULL
  AND (
    password_hash = crypt('Admin@12345', password_hash)
    OR password_hash = crypt('Buyer@12345', password_hash)
    OR password_hash = crypt('Helper@12345', password_hash)
  );

-- 3. Kill any live session belonging to an account whose password we just
--    revoked. Access tokens are stateless and expire on their own TTL.
UPDATE refresh_tokens
SET revoked_at = now()
WHERE revoked_at IS NULL
  AND user_id IN (SELECT id FROM users WHERE password_hash IS NULL);

-- 4. Demote the hardcoded reviewer accounts previously auto-created on every
--    boot by AuthService.initReviewerAccounts (now deleted). Their KYC was
--    force-approved in code; drop that so they cannot take live work. Real
--    reviewer accounts are provisioned by ReviewerAccountRunner instead.
UPDATE helper_profiles
SET kyc_status = 'PENDING'
WHERE user_id IN (
  SELECT id FROM users WHERE phone IN ('9999999991', '9999999992', '9999999993')
);
