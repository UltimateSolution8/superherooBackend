-- Indexes for the queries that run on every dispatch, every accept, and every
-- 30-second cleanup tick. Several of these previously had no supporting index
-- at all and forced a scan of the tasks table.

-- 1. Offer expiry sweep: UPDATE ... WHERE status='OFFERED' AND expires_at <= now
--    Runs every 30s. Partial index keeps it tiny — only live offers qualify.
CREATE INDEX IF NOT EXISTS idx_task_offers_open_expiry
  ON task_offers (expires_at)
  WHERE status = 'OFFERED';

-- 2. "Is this helper already busy?" — checked on every accept AND for every
--    candidate on every dispatch. idx_tasks_helper_status covers it, but a
--    partial index over just the active states is far smaller and stays hot.
CREATE INDEX IF NOT EXISTS idx_tasks_helper_active
  ON tasks (assigned_helper_id)
  WHERE status IN ('ASSIGNED', 'ARRIVED', 'STARTED');

-- 3. findTimedOutSearchingTasks — runs every 30s for both the timeout sweep and
--    the new re-dispatch pass. Had no matching index; idx_tasks_status forced a
--    scan of every SEARCHING row.
CREATE INDEX IF NOT EXISTS idx_tasks_searching_unassigned
  ON tasks (created_at, scheduled_at)
  WHERE status = 'SEARCHING' AND assigned_helper_id IS NULL;

-- 4. findTop100ByStatusAndUpdatedAtBefore — the stale-ASSIGNED sweep, every 30s.
--    There was no index on tasks.updated_at at all.
CREATE INDEX IF NOT EXISTS idx_tasks_status_updated_at
  ON tasks (status, updated_at);

-- 5. Recurring task lookups. tasks.recurring_task_id was added in V35 and never
--    indexed, yet resumeRecurringTask queries it inside a loop over occurrences.
CREATE INDEX IF NOT EXISTS idx_tasks_recurring_task_id
  ON tasks (recurring_task_id)
  WHERE recurring_task_id IS NOT NULL;

-- 6. Refresh-token revocation by user (password reset signs out every device).
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_active
  ON refresh_tokens (user_id)
  WHERE revoked_at IS NULL;
