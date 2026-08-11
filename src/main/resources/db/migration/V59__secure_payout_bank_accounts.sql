-- Store payout destinations as immutable, encrypted versions. Existing rows
-- contain only the last four digits and remain visible but are not payout-ready.
ALTER TABLE helper_payout_accounts
  ADD COLUMN IF NOT EXISTS account_number_ciphertext TEXT,
  ADD COLUMN IF NOT EXISTS account_number_key_id VARCHAR(32),
  ADD COLUMN IF NOT EXISTS ifsc_verified_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS verification_status VARCHAR(40) NOT NULL DEFAULT 'DETAILS_INCOMPLETE',
  ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS superseded_at TIMESTAMPTZ;

ALTER TABLE helper_payout_accounts
  DROP CONSTRAINT IF EXISTS uq_helper_payout_provider;

ALTER TABLE helper_payout_accounts
  DROP CONSTRAINT IF EXISTS ck_helper_payout_status;

ALTER TABLE helper_payout_accounts
  ADD CONSTRAINT ck_helper_payout_status CHECK (
    status IN (
      'PENDING_KYC', 'PENDING_ACCOUNT_VERIFICATION', 'ACTIVE',
      'SUSPENDED', 'REJECTED', 'SUPERSEDED'
    )
  );

ALTER TABLE helper_payout_accounts
  ADD CONSTRAINT ck_helper_payout_verification_status CHECK (
    verification_status IN ('DETAILS_INCOMPLETE', 'NOT_STARTED', 'PENDING', 'VERIFIED', 'FAILED')
  );

CREATE INDEX IF NOT EXISTS idx_helper_payout_helper_current
  ON helper_payout_accounts(helper_id, is_current, updated_at DESC);

-- Provider identifiers live outside the canonical encrypted destination so the
-- same account version can be onboarded to Razorpay Route, RazorpayX, or a
-- replacement provider without changing the mobile contract.
CREATE TABLE IF NOT EXISTS payout_beneficiary_links (
  id UUID PRIMARY KEY,
  payout_account_id UUID NOT NULL REFERENCES helper_payout_accounts(id),
  provider VARCHAR(40) NOT NULL,
  external_contact_id VARCHAR(160),
  external_fund_account_id VARCHAR(160),
  external_linked_account_id VARCHAR(160),
  status VARCHAR(40) NOT NULL DEFAULT 'NOT_ONBOARDED',
  last_error_code VARCHAR(120),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_payout_beneficiary_provider UNIQUE (payout_account_id, provider),
  CONSTRAINT ck_payout_beneficiary_status CHECK (
    status IN ('NOT_ONBOARDED', 'PENDING', 'ACTIVE', 'FAILED', 'DISABLED')
  )
);

CREATE INDEX IF NOT EXISTS idx_payout_beneficiary_account
  ON payout_beneficiary_links(payout_account_id);

INSERT INTO payout_beneficiary_links (
  id, payout_account_id, provider, external_linked_account_id, status, created_at, updated_at
)
SELECT gen_random_uuid(), id, provider, provider_linked_account_id,
       CASE WHEN status = 'ACTIVE' THEN 'ACTIVE' ELSE 'PENDING' END,
       created_at, updated_at
FROM helper_payout_accounts
WHERE provider_linked_account_id IS NOT NULL
ON CONFLICT (payout_account_id, provider) DO NOTHING;

-- From this point the payout account row is provider-neutral. Provider-specific
-- identifiers live in payout_beneficiary_links.
-- A historical database could contain one row per provider for the same user.
-- Retain the newest as the canonical destination before collapsing providers.
WITH ranked_accounts AS (
  SELECT id,
         row_number() OVER (
           PARTITION BY helper_id
           ORDER BY updated_at DESC, created_at DESC, id DESC
         ) AS version_rank
  FROM helper_payout_accounts
  WHERE is_current
)
UPDATE helper_payout_accounts account
SET is_current = FALSE,
    status = 'SUPERSEDED',
    superseded_at = COALESCE(account.superseded_at, now()),
    updated_at = now()
FROM ranked_accounts ranked
WHERE account.id = ranked.id
  AND ranked.version_rank > 1;

UPDATE helper_payout_accounts SET provider = 'INTERNAL' WHERE provider <> 'INTERNAL';
ALTER TABLE helper_payout_accounts ALTER COLUMN provider SET DEFAULT 'INTERNAL';

CREATE UNIQUE INDEX IF NOT EXISTS uq_helper_payout_current_provider
  ON helper_payout_accounts(helper_id, provider)
  WHERE is_current;

ALTER TABLE helper_payout_accounts
  ADD CONSTRAINT ck_helper_payout_secure_details CHECK (
    verification_status = 'DETAILS_INCOMPLETE'
    OR (
      account_number_ciphertext IS NOT NULL
      AND account_number_key_id IS NOT NULL
      AND bank_account_last4 ~ '^[0-9]{4}$'
      AND ifsc_code ~ '^[A-Z]{4}0[A-Z0-9]{6}$'
      AND ifsc_verified_at IS NOT NULL
    )
  ),
  ADD CONSTRAINT ck_helper_payout_current_status CHECK (
    (is_current AND status <> 'SUPERSEDED')
    OR (NOT is_current AND status = 'SUPERSEDED')
  );
