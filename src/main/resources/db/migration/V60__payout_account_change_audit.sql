ALTER TABLE helper_payout_accounts
  ADD COLUMN IF NOT EXISTS supersedes_account_id UUID REFERENCES helper_payout_accounts(id),
  ADD COLUMN IF NOT EXISTS change_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY';

ALTER TABLE helper_payout_accounts
  ADD CONSTRAINT ck_helper_payout_change_source CHECK (
    change_source IN ('LEGACY', 'INITIAL_KYC', 'PROFILE')
  );

CREATE TABLE IF NOT EXISTS payout_account_change_events (
  id UUID PRIMARY KEY,
  beneficiary_user_id UUID NOT NULL REFERENCES users(id),
  actor_user_id UUID NOT NULL REFERENCES users(id),
  actor_role VARCHAR(32) NOT NULL,
  action_type VARCHAR(40) NOT NULL,
  change_source VARCHAR(32) NOT NULL,
  previous_account_id UUID REFERENCES helper_payout_accounts(id),
  new_account_id UUID NOT NULL REFERENCES helper_payout_accounts(id),
  previous_last4 VARCHAR(4),
  new_last4 VARCHAR(4) NOT NULL,
  ip_address VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_payout_change_action CHECK (action_type IN ('BANK_ACCOUNT_CREATED', 'BANK_ACCOUNT_REPLACED')),
  CONSTRAINT ck_payout_change_source CHECK (change_source IN ('INITIAL_KYC', 'PROFILE')),
  CONSTRAINT ck_payout_change_last4 CHECK (
    new_last4 ~ '^[0-9]{4}$' AND (previous_last4 IS NULL OR previous_last4 ~ '^[0-9]{4}$')
  )
);

CREATE INDEX IF NOT EXISTS idx_payout_change_beneficiary_created
  ON payout_account_change_events(beneficiary_user_id, created_at DESC);

