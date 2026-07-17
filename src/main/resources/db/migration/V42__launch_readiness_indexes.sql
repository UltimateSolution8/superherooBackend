CREATE INDEX IF NOT EXISTS idx_tasks_buyer_status_created
  ON tasks (buyer_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tasks_helper_status_created
  ON tasks (assigned_helper_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tasks_scheduled_dispatch
  ON tasks (status, scheduled_at, assigned_helper_id)
  WHERE status = 'SCHEDULED_PENDING';

CREATE INDEX IF NOT EXISTS idx_task_offers_task_helper_status
  ON task_offers (task_id, helper_id, status);

CREATE INDEX IF NOT EXISTS idx_booking_batches_status_created
  ON booking_batches (status, created_at DESC);
