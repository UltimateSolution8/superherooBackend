-- Matching engine: an explicit "searching since" clock, wave escalation state, and
-- the fairness counters ranking needs.
--
-- The bug this fixes: TaskStaleCleanupJob measured the search timeout from
-- created_at, and there was no other clock available. A booking held in
-- ADMIN_REVIEW for two hours and then released by an admin was already past
-- `created_at + TASK_SEARCH_TIMEOUT_SECONDS`, so the very next cleanup tick
-- cancelled it — within 30s of approval, with "No helper accepted your task in
-- time", a refund request and a support ticket, while its fresh offers were
-- still live. The same shape hit any prepaid capture landing more than 5 minutes
-- after booking.

ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS searching_started_at timestamptz,
    ADD COLUMN IF NOT EXISTS dispatch_wave integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_dispatched_at timestamptz;

-- Backfill: for tasks already searching, created_at is the best estimate we have
-- and preserves current behaviour for them. Scheduled tasks start their window at
-- scheduled_at, which is when they actually entered SEARCHING.
UPDATE tasks
SET searching_started_at = COALESCE(scheduled_at, created_at)
WHERE searching_started_at IS NULL;

-- The timeout/re-dispatch queries now scan on searching_started_at, so the
-- partial index from V55 has to follow. Dropping and recreating is safe: it is a
-- non-unique index used only by the cleanup job.
DROP INDEX IF EXISTS idx_tasks_searching_unassigned;
CREATE INDEX IF NOT EXISTS idx_tasks_searching_unassigned
    ON tasks (searching_started_at)
    WHERE status = 'SEARCHING' AND assigned_helper_id IS NULL;

-- Fairness and acceptance-rate inputs for candidate scoring. Ranking was purely
-- straight-line distance, so the same nearest partners were re-offered every
-- wave while everyone else starved.
ALTER TABLE helper_profiles
    ADD COLUMN IF NOT EXISTS offers_seen bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS offers_accepted bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_offered_at timestamptz;
