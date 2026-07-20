-- Matching can be triggered by task creation and a helper heartbeat at the
-- same time. Keep the strongest existing state, then make dispatch idempotent
-- at the database boundary as a final concurrency safeguard.
WITH ranked AS (
  SELECT id,
         row_number() OVER (
           PARTITION BY task_id, helper_id
           ORDER BY
             CASE status
               WHEN 'ACCEPTED' THEN 4
               WHEN 'OFFERED' THEN 3
               WHEN 'DECLINED' THEN 2
               ELSE 1
             END DESC,
             updated_at DESC,
             created_at DESC
         ) AS position
  FROM task_offers
)
DELETE FROM task_offers
WHERE id IN (SELECT id FROM ranked WHERE position > 1);

CREATE UNIQUE INDEX IF NOT EXISTS uq_task_offers_task_helper
  ON task_offers (task_id, helper_id);
