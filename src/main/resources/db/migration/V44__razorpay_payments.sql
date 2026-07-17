-- Replace the demo wallet/escrow ledger with auditable Razorpay payment attempts.
DROP TABLE IF EXISTS payment_webhook_events;
DROP TABLE IF EXISTS payments;

CREATE TABLE payments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE RESTRICT,
  buyer_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  helper_id UUID REFERENCES users(id) ON DELETE SET NULL,
  amount_paise BIGINT NOT NULL CHECK (amount_paise >= 100),
  currency VARCHAR(3) NOT NULL DEFAULT 'INR',
  provider VARCHAR(20) NOT NULL DEFAULT 'RAZORPAY',
  method VARCHAR(30),
  status VARCHAR(30) NOT NULL,
  receipt VARCHAR(40) NOT NULL UNIQUE,
  idempotency_key VARCHAR(100) NOT NULL,
  provider_order_id VARCHAR(128) UNIQUE,
  provider_payment_id VARCHAR(128) UNIQUE,
  failure_code VARCHAR(80),
  failure_description VARCHAR(500),
  amount_refunded_paise BIGINT NOT NULL DEFAULT 0 CHECK (amount_refunded_paise >= 0),
  paid_at TIMESTAMPTZ,
  captured_at TIMESTAMPTZ,
  failed_at TIMESTAMPTZ,
  refunded_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (buyer_id, idempotency_key)
);

CREATE INDEX idx_payments_task_created_at ON payments(task_id, created_at DESC);
CREATE INDEX idx_payments_buyer_created_at ON payments(buyer_id, created_at DESC);
CREATE INDEX idx_payments_helper_created_at ON payments(helper_id, created_at DESC);
CREATE UNIQUE INDEX uq_payments_one_captured_per_task
  ON payments(task_id)
  WHERE status IN ('CAPTURED', 'PARTIALLY_REFUNDED');

CREATE TABLE payment_attempts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
  provider_payment_id VARCHAR(128) NOT NULL UNIQUE,
  status VARCHAR(30) NOT NULL,
  method VARCHAR(30),
  amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
  amount_refunded_paise BIGINT NOT NULL DEFAULT 0 CHECK (amount_refunded_paise >= 0),
  currency VARCHAR(3) NOT NULL,
  failure_code VARCHAR(80),
  failure_description VARCHAR(500),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_attempts_payment_created_at
  ON payment_attempts(payment_id, created_at DESC);

CREATE TABLE payment_webhook_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider_event_id VARCHAR(128) NOT NULL UNIQUE,
  event_type VARCHAR(80) NOT NULL,
  payload_sha256 VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  error_message VARCHAR(500),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ
);

CREATE INDEX idx_payment_webhook_events_received_at
  ON payment_webhook_events(received_at DESC);

-- Demo wallet and pseudo-escrow were not backed by money and must not survive launch.
ALTER TABLE users DROP COLUMN IF EXISTS demo_balance_paise;
DROP INDEX IF EXISTS idx_tasks_escrow_release_at;
ALTER TABLE tasks
  DROP COLUMN IF EXISTS escrow_status,
  DROP COLUMN IF EXISTS escrow_amount_paise,
  DROP COLUMN IF EXISTS escrow_held_at,
  DROP COLUMN IF EXISTS escrow_release_at,
  DROP COLUMN IF EXISTS escrow_released_at,
  DROP COLUMN IF EXISTS escrow_released_to_helper_id;
