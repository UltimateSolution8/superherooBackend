-- Update check constraint for booking_batches.status to support new mediator and audit statuses
ALTER TABLE booking_batches
  DROP CONSTRAINT IF EXISTS chk_booking_batch_status;

ALTER TABLE booking_batches
  ADD CONSTRAINT chk_booking_batch_status
  CHECK (status IN (
    'CREATED',
    'PARTIAL',
    'COMPLETED',
    'CANCELLED',
    'PENDING_AUDIT',
    'ON_HOLD',
    'PENDING_MEDIATOR',
    'MEDIATOR_ACCEPTED',
    'MEDIATOR_DISPATCHING',
    'MEDIATOR_IN_PROGRESS',
    'MEDIATOR_STARTED',
    'MEDIATOR_COMPLETED'
  ));
