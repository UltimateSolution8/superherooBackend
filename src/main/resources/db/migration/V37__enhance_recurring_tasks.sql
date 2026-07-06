ALTER TABLE recurring_tasks ADD COLUMN recurrence_interval INT DEFAULT 1 NOT NULL;
ALTER TABLE recurring_tasks ADD COLUMN by_day INT[];
ALTER TABLE recurring_tasks ADD COLUMN by_month_day INT;
ALTER TABLE recurring_tasks ADD COLUMN timezone VARCHAR(64) DEFAULT 'Asia/Kolkata' NOT NULL;
ALTER TABLE recurring_tasks ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;

CREATE INDEX IF NOT EXISTS idx_tasks_scheduled_dispatch
  ON tasks (scheduled_at)
  WHERE status = 'SEARCHING' AND assigned_helper_id IS NULL AND scheduled_at IS NOT NULL;
