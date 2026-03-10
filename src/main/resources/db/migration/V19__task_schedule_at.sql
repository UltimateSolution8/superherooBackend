ALTER TABLE tasks
  ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_tasks_status_scheduled_at ON tasks(status, scheduled_at);
