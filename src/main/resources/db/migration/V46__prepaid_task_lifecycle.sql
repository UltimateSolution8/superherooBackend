ALTER TABLE tasks
  ADD COLUMN IF NOT EXISTS payment_collection_mode VARCHAR(30) NOT NULL DEFAULT 'PAY_AFTER_SERVICE';

ALTER TABLE booking_batches
  ADD COLUMN IF NOT EXISTS payment_collection_mode VARCHAR(30) NOT NULL DEFAULT 'PAY_AFTER_SERVICE';

ALTER TABLE payments
  ADD COLUMN IF NOT EXISTS fulfillment_status VARCHAR(30),
  ADD COLUMN IF NOT EXISTS earning_released_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS refund_requested_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS refund_requested_amount_paise BIGINT,
  ADD COLUMN IF NOT EXISTS refund_attempts INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS refund_last_error VARCHAR(500);
ALTER TABLE payments
  ADD COLUMN IF NOT EXISTS provider_refund_id VARCHAR(128);

ALTER TABLE booking_batches DROP CONSTRAINT IF EXISTS chk_booking_batch_status;
ALTER TABLE booking_batches ADD CONSTRAINT chk_booking_batch_status CHECK (status IN (
  'PAYMENT_PENDING', 'CREATED', 'PARTIAL', 'COMPLETED', 'CANCELLED',
  'PENDING_AUDIT', 'ON_HOLD', 'PENDING_MEDIATOR', 'MEDIATOR_ACCEPTED',
  'MEDIATOR_DISPATCHING', 'MEDIATOR_IN_PROGRESS', 'MEDIATOR_STARTED', 'MEDIATOR_COMPLETED'
));

ALTER TABLE payments DROP CONSTRAINT IF EXISTS chk_payments_target;
ALTER TABLE payments ADD CONSTRAINT chk_payments_target CHECK (
  (payment_scope = 'TASK' AND task_id IS NOT NULL AND batch_id IS NULL AND mediator_id IS NULL)
  OR
  (payment_scope = 'MEDIATOR_BATCH' AND task_id IS NULL AND batch_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_payments_refund_queue
  ON payments(fulfillment_status, updated_at)
  WHERE fulfillment_status = 'REFUND_PENDING';

ALTER TABLE payments ADD CONSTRAINT chk_payments_refund_requested_amount
  CHECK (refund_requested_amount_paise IS NULL OR (
    refund_requested_amount_paise > 0 AND refund_requested_amount_paise <= amount_paise
  ));

UPDATE payments p
SET fulfillment_status = CASE
  WHEN p.status IN ('REFUNDED') THEN 'REFUNDED'
  WHEN p.status IN ('CAPTURED', 'PARTIALLY_REFUNDED') AND EXISTS (
    SELECT 1 FROM tasks t WHERE t.id = p.task_id AND t.status = 'COMPLETED'
  ) THEN 'EARNED'
  WHEN p.status IN ('CAPTURED', 'PARTIALLY_REFUNDED') THEN 'HELD'
  ELSE fulfillment_status
END
WHERE fulfillment_status IS NULL;
