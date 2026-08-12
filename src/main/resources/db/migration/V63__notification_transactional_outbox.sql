CREATE TABLE notification_outbox (
    id UUID PRIMARY KEY,
    job_json JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_notification_outbox_due
    ON notification_outbox (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_notification_outbox_stuck
    ON notification_outbox (locked_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_notification_outbox_published_retention
    ON notification_outbox (published_at)
    WHERE status = 'PUBLISHED';

CREATE INDEX idx_notification_outbox_dead_retention
    ON notification_outbox (updated_at)
    WHERE status = 'DEAD';
