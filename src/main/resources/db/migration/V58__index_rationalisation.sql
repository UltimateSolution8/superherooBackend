-- Index rationalisation.
--
-- Two problems, opposite directions:
--
--   1. `tasks`, `task_offers` and `payments` — the three highest-insert tables —
--      accumulated overlapping indexes across V1, V13, V14, V42, V51 and V55.
--      Several are strict prefixes of a wider index, and one pair is the same
--      definition under two names. Every one of them is maintained on every
--      insert and update for no read benefit.
--
--   2. Several query paths had no index at all, including two retention jobs
--      that are being wired up alongside this migration.
--
-- Deliberately NOT using CREATE/DROP INDEX CONCURRENTLY. Flyway detects those
-- and runs the whole script outside a transaction, so a mid-script failure
-- leaves a partially applied migration plus a failed flyway_schema_history row
-- (the app then refuses to start until someone runs `flyway repair`), and a
-- failed concurrent build leaves an INVALID index behind that still costs
-- writes. At current volume plain DDL takes well under a second and rolls back
-- cleanly.
--
-- Switch-over rule: once `tasks` passes roughly 1M rows, move index work into a
-- separate CONCURRENTLY-only migration containing no other statement types, run
-- it against the database out of band, and mark it applied.

-- ---------------------------------------------------------------------------
-- A. Drop redundant indexes.
--
-- Each is either a strict column prefix of a wider index that can satisfy the
-- same predicate, or a byte-identical definition under a second name.
--
-- Deliberately KEPT:
--   idx_tasks_helper_created_at   (V13) - findTop50ByAssignedHelperIdOrderByCreatedAtDesc
--                                         has no status predicate and so cannot
--                                         skip the middle column of the V42 index.
--   idx_tasks_created_status      (V51) - different leading column; backs the
--                                         admin moderation queue listing.
--   idx_tasks_helper_active       (V55) - partial and small; hit on every dispatch.
--   idx_helper_mediator_links_*   (V43) - already (id, status, created_at DESC)
--                                         composites, not single-column.
-- ---------------------------------------------------------------------------

-- (buyer_id) is covered by (buyer_id, created_at DESC).
DROP INDEX IF EXISTS idx_tasks_buyer_id;

-- Identical definition to idx_tasks_buyer_created_at (V13) under another name.
DROP INDEX IF EXISTS idx_tasks_buyer_created;

-- (status) is covered by (status, created_at DESC).
DROP INDEX IF EXISTS idx_tasks_status;

-- (assigned_helper_id, status) is covered by
-- idx_tasks_helper_status_created (assigned_helper_id, status, created_at DESC).
DROP INDEX IF EXISTS idx_tasks_helper_status;

-- (task_id) is covered by idx_task_offers_task_helper_status.
DROP INDEX IF EXISTS idx_task_offers_task_id;

-- (buyer_id) is covered by idx_payments_buyer_created_at.
DROP INDEX IF EXISTS idx_payments_buyer_id;

-- Replaced by the (action_type, created_at DESC) composite in section B, which
-- also serves the ORDER BY that this one forced into a sort.
DROP INDEX IF EXISTS idx_sys_audit_logs_action;

-- ---------------------------------------------------------------------------
-- B. Add missing indexes.
-- ---------------------------------------------------------------------------

-- MediatorService looks workers up by task on job start, completion and payment
-- breakdown. V39 indexed only batch_id and helper_id, so every one of those was
-- a sequential scan.
CREATE INDEX IF NOT EXISTS idx_mediator_job_workers_task
  ON mediator_job_workers (task_id)
  WHERE task_id IS NOT NULL;

-- Required by the stale push-token purge. V15 indexed only user_id, so the
-- retention sweep would have scanned the whole table.
CREATE INDEX IF NOT EXISTS idx_push_tokens_last_seen
  ON push_tokens (last_seen_at);

-- Required by the KYC raw-payload purge. Partial, because the job only ever
-- looks for rows that still carry a provider payload.
CREATE INDEX IF NOT EXISTS idx_kyc_requests_retention_purge
  ON kyc_requests (retention_expires_at)
  WHERE raw_result IS NOT NULL AND retention_expires_at IS NOT NULL;

-- Admin audit log filtered by action and sorted by recency; V51's single-column
-- index (dropped above) satisfied the filter but left the sort to a Sort node.
CREATE INDEX IF NOT EXISTS idx_sys_audit_logs_action_created
  ON system_audit_logs (action_type, created_at DESC);

-- ---------------------------------------------------------------------------
-- C. Dead schema objects.
-- ---------------------------------------------------------------------------

-- V51 added haversine_km() in plpgsql. Nothing calls it: distance is computed in
-- Java (GeoUtils.distanceMeters), and proximity matching runs on Redis GEO + H3.
DROP FUNCTION IF EXISTS haversine_km(double precision, double precision,
                                     double precision, double precision);

-- V1's audit_logs table has no JPA entity — the live audit table is
-- system_audit_logs (reports/model/AuditLogEntity). It is orphaned but still
-- carries two indexes and appears in every dump.
--
-- CHECK BEFORE APPLYING IN PRODUCTION:  SELECT count(*) FROM audit_logs;
-- If it is non-empty, dump it first; the rows are not reachable from the app.
DROP INDEX IF EXISTS idx_audit_logs_actor_user_id;
DROP INDEX IF EXISTS idx_audit_logs_entity;
DROP TABLE IF EXISTS audit_logs;

-- ---------------------------------------------------------------------------
-- D. Autovacuum and statistics.
--
-- The 0.2 default scale factor means a 1M-row table waits for 200k dead tuples
-- before a vacuum. These three churn constantly (status transitions, offer
-- expiry, payment state), so they need to be swept far more eagerly.
-- ---------------------------------------------------------------------------

ALTER TABLE tasks       SET (autovacuum_vacuum_scale_factor = 0.05,
                             autovacuum_analyze_scale_factor = 0.02);
ALTER TABLE task_offers SET (autovacuum_vacuum_scale_factor = 0.05,
                             autovacuum_analyze_scale_factor = 0.02);
ALTER TABLE payments    SET (autovacuum_vacuum_scale_factor = 0.05,
                             autovacuum_analyze_scale_factor = 0.02);

-- Refresh planner statistics so the first queries after the drops choose the
-- surviving indexes rather than replanning off stale estimates.
ANALYZE tasks;
ANALYZE task_offers;
ANALYZE payments;
ANALYZE mediator_job_workers;
