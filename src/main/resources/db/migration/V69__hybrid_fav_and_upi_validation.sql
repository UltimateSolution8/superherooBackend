-- V69: Upgrade payout_account_validations for Hybrid FAV + UPI VPA support.
--
-- Background: Razorpay Hybrid FAV (penniless + penny-drop fallback) requires tracking
-- the validation mode and account type. We also capture Razorpay's own name_match_score
-- to prefer over our local computation, and structured failure details from status_details
-- for cleaner error reporting and admin tooling.

ALTER TABLE payout_account_validations
  -- "bank_account" or "vpa": which verification rail was used.
  ADD COLUMN IF NOT EXISTS account_type     VARCHAR(20)  NOT NULL DEFAULT 'bank_account',
  -- "optimized" (hybrid FAV) or "standard" (classic penny-drop only).
  ADD COLUMN IF NOT EXISTS validation_type  VARCHAR(20)  NOT NULL DEFAULT 'standard',
  -- Razorpay's own 0-100 name match score; preferred over our local word-set score.
  ADD COLUMN IF NOT EXISTS provider_name_match_score INTEGER,
  -- Structured failure source from status_details.source (e.g. "beneficiary_bank").
  ADD COLUMN IF NOT EXISTS failure_source   VARCHAR(40),
  -- Structured reason code from status_details.reason (e.g. "invalid_account_number").
  ADD COLUMN IF NOT EXISTS failure_reason_code VARCHAR(60);

-- Upgrade helper_payout_accounts for UPI payout destinations.
-- The upi_id_masked column already exists; add encrypted storage and account_type.
ALTER TABLE helper_payout_accounts
  -- "bank_account" (default) or "vpa" — distinguishes which payout rail to use.
  ADD COLUMN IF NOT EXISTS account_type        VARCHAR(20)  NOT NULL DEFAULT 'bank_account',
  -- Encrypted UPI VPA (same AES-GCM pattern as bank account number).
  ADD COLUMN IF NOT EXISTS upi_id_ciphertext   TEXT,
  ADD COLUMN IF NOT EXISTS upi_id_key_id       VARCHAR(32);

-- Back-fill existing rows: all pre-upgrade validations used the classic penny-drop.
UPDATE payout_account_validations
  SET validation_type = 'standard'
  WHERE validation_type = 'standard'; -- no-op, but documents intent.

COMMENT ON COLUMN payout_account_validations.validation_type IS
  '"optimized" = Hybrid FAV (penniless + penny-drop fallback, Razorpay default since V69). '
  '"standard" = explicit penny-drop only (pre-V69 rows).';
COMMENT ON COLUMN payout_account_validations.provider_name_match_score IS
  'Razorpay name match score (0-100) from validation_results.name_match_score. '
  'Preferred over name_match_score (local) when non-null.';
COMMENT ON COLUMN payout_account_validations.failure_source IS
  'Structured source from status_details.source. '
  'Values: beneficiary_bank, gateway, business, customer, internal.';
COMMENT ON COLUMN payout_account_validations.failure_reason_code IS
  'Structured reason code from status_details.reason. '
  'Examples: invalid_account_number, invalid_vpa, account_closed, bank_down.';
