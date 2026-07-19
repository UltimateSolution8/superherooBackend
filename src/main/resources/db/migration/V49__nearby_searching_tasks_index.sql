-- Supports the helper marketplace bounding-box query without scanning the
-- complete task history. The exact 3 km circle is still verified in Java.
CREATE INDEX IF NOT EXISTS idx_tasks_searching_location_created
  ON tasks (lat, lng, created_at DESC)
  WHERE status = 'SEARCHING' AND assigned_helper_id IS NULL;
