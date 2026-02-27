-- Performance indexes (safe for repeat runs)
CREATE INDEX IF NOT EXISTS idx_tasks_status_created_at ON tasks (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tasks_buyer_created_at ON tasks (buyer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tasks_helper_created_at ON tasks (assigned_helper_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_offers_task_helper_status ON task_offers (task_id, helper_id, status);
CREATE INDEX IF NOT EXISTS idx_support_tickets_status_last_message_at ON support_tickets (status, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_messages_ticket_created_at ON support_messages (ticket_id, created_at ASC);
