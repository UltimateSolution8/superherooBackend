-- Keep individual helper checkout as the default while allowing one consolidated
-- collection for a completed mediator-managed batch. Funds are collected by the
-- platform merchant account; beneficiary settlement remains a separate phase.
ALTER TABLE payments
  ALTER COLUMN task_id DROP NOT NULL,
  ADD COLUMN IF NOT EXISTS batch_id UUID REFERENCES booking_batches(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS mediator_id UUID REFERENCES users(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS payment_scope VARCHAR(30) NOT NULL DEFAULT 'TASK';

ALTER TABLE payments
  ADD CONSTRAINT chk_payments_target
  CHECK (
    (payment_scope = 'TASK' AND task_id IS NOT NULL AND batch_id IS NULL AND mediator_id IS NULL)
    OR
    (payment_scope = 'MEDIATOR_BATCH' AND task_id IS NULL AND batch_id IS NOT NULL AND mediator_id IS NOT NULL)
  );

CREATE INDEX IF NOT EXISTS idx_payments_batch_created_at
  ON payments(batch_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_mediator_created_at
  ON payments(mediator_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_one_captured_per_batch
  ON payments(batch_id)
  WHERE status IN ('CAPTURED', 'PARTIALLY_REFUNDED');

ALTER TABLE booking_batches
  ADD COLUMN IF NOT EXISTS payment_mode VARCHAR(30);

ALTER TABLE booking_batches
  ADD CONSTRAINT chk_booking_batches_payment_mode
  CHECK (payment_mode IS NULL OR payment_mode IN ('PER_HELPER', 'CONSOLIDATED_MEDIATOR'));
