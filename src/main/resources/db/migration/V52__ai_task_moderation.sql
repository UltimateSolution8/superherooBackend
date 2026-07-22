-- V52__ai_task_moderation.sql
-- Create AI Task Reviews table
CREATE TABLE IF NOT EXISTS task_ai_reviews (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    prompt_version VARCHAR(32) NOT NULL DEFAULT 'v1.0',
    model VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    confidence INT NOT NULL,
    risk_score INT NOT NULL,
    quality_score INT NOT NULL,
    reasons JSONB DEFAULT '[]'::jsonb,
    flags JSONB DEFAULT '[]'::jsonb,
    raw_response JSONB,
    review_duration_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_task_ai_reviews_task_id ON task_ai_reviews(task_id);
CREATE INDEX IF NOT EXISTS idx_task_ai_reviews_status ON task_ai_reviews(status);
CREATE INDEX IF NOT EXISTS idx_task_ai_reviews_created_at ON task_ai_reviews(created_at DESC);

-- Create Task Audit Log table
CREATE TABLE IF NOT EXISTS task_audit_logs (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    action VARCHAR(64) NOT NULL,
    performed_by VARCHAR(128) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remarks TEXT
);

CREATE INDEX IF NOT EXISTS idx_task_audit_logs_task_id ON task_audit_logs(task_id, timestamp DESC);

-- Add index on tasks status for fast admin queue filtering
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
