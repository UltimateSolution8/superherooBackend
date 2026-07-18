-- Production settlement readiness for Razorpay Route. No bank transfer is
-- attempted until Route is activated and a verified linked account is present.
CREATE TABLE IF NOT EXISTS helper_payout_accounts (
  id UUID PRIMARY KEY,
  helper_id UUID NOT NULL REFERENCES users(id),
  provider VARCHAR(32) NOT NULL DEFAULT 'RAZORPAY_ROUTE',
  provider_linked_account_id VARCHAR(128),
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_KYC',
  account_holder_name VARCHAR(160),
  bank_account_last4 VARCHAR(4),
  ifsc_code VARCHAR(20),
  upi_id_masked VARCHAR(160),
  verified_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_helper_payout_provider UNIQUE (helper_id, provider),
  CONSTRAINT ck_helper_payout_status CHECK (status IN ('PENDING_KYC','ACTIVE','SUSPENDED','REJECTED'))
);

CREATE TABLE IF NOT EXISTS payout_transfers (
  id UUID PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments(id),
  beneficiary_user_id UUID NOT NULL REFERENCES users(id),
  payout_account_id UUID REFERENCES helper_payout_accounts(id),
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  currency VARCHAR(3) NOT NULL DEFAULT 'INR',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_ACCOUNT',
  idempotency_key VARCHAR(160) NOT NULL UNIQUE,
  provider_transfer_id VARCHAR(128) UNIQUE,
  provider_error_code VARCHAR(80),
  provider_error_message VARCHAR(500),
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ,
  processed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_payout_transfer_status CHECK (status IN ('PENDING_ACCOUNT','READY','PROCESSING','PROCESSED','FAILED','REVERSED'))
);

CREATE INDEX IF NOT EXISTS idx_payout_transfer_status_retry
  ON payout_transfers(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_payout_transfer_beneficiary
  ON payout_transfers(beneficiary_user_id, created_at DESC);
