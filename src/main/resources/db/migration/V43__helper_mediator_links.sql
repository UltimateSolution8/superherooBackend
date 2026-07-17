CREATE TABLE IF NOT EXISTS helper_mediator_links (
  id UUID PRIMARY KEY,
  helper_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  mediator_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_by VARCHAR(24) NOT NULL DEFAULT 'HELPER',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uk_helper_mediator_links_pair UNIQUE (helper_id, mediator_id)
);

CREATE INDEX IF NOT EXISTS idx_helper_mediator_links_helper
  ON helper_mediator_links (helper_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_helper_mediator_links_mediator
  ON helper_mediator_links (mediator_id, status, created_at DESC);
