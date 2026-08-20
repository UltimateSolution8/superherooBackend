-- The partner half of the money flow.
--
-- Until now the platform recorded what a citizen paid and nothing about what a
-- partner earned: commission was a hardcoded 0.15 inside ReportService, used only
-- to draw an admin chart, and partner balance had no representation at all.
--
-- These tables are written from day one even though payouts ship switched off. A
-- ledger that starts on the day payouts go live has no history to reconcile
-- against, and every balance would begin at zero regardless of the work already done.

-- Append-only. Rows are never updated or deleted; a mistake is corrected by a
-- compensating entry, which is what makes the history auditable.
CREATE TABLE IF NOT EXISTS ledger_entries (
  id                  UUID PRIMARY KEY,
  user_id             UUID NOT NULL REFERENCES users(id),
  entry_type          VARCHAR(32) NOT NULL,
  -- Signed, in paise, from the perspective of what the platform owes this user.
  -- EARNING is positive; COMMISSION, DIRECT_COLLECTION and PAYOUT are negative.
  amount_paise        BIGINT NOT NULL,
  task_id             UUID REFERENCES tasks(id),
  batch_id            UUID,
  payout_item_id      UUID,
  -- Free-text provenance: a Razorpay payment id, a payout id, an admin note.
  reference           VARCHAR(120),
  description         VARCHAR(300),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN (
    'EARNING', 'COMMISSION', 'DIRECT_COLLECTION', 'ADJUSTMENT', 'PAYOUT', 'PAYOUT_REVERSAL'
  ))
);

-- The idempotency guarantee. A completion that is processed twice — a retry, a
-- redelivered webhook, a double tap — cannot book a second earning, because the
-- second insert violates this index.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_task_entry
  ON ledger_entries(task_id, user_id, entry_type) WHERE task_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ledger_user_created
  ON ledger_entries(user_id, created_at DESC);

-- Groups payout items into a settlement run.
--
-- Nothing writes this yet: withdrawals today are one partner asking for their own
-- balance, and payout_items.batch_id stays null. It exists now because adding the
-- grouping later means a migration against a live payouts table, and because the
-- foreign key below is what keeps a future batch id honest.
CREATE TABLE IF NOT EXISTS payout_batches (
  id             UUID PRIMARY KEY,
  status         VARCHAR(24) NOT NULL,
  provider       VARCHAR(32) NOT NULL DEFAULT 'RAZORPAYX',
  item_count     INTEGER NOT NULL DEFAULT 0,
  total_paise    BIGINT NOT NULL DEFAULT 0,
  created_by     UUID REFERENCES users(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_payout_batch_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE IF NOT EXISTS payout_items (
  id                   UUID PRIMARY KEY,
  batch_id             UUID REFERENCES payout_batches(id),
  user_id              UUID NOT NULL REFERENCES users(id),
  payout_account_id    UUID REFERENCES helper_payout_accounts(id),
  amount_paise         BIGINT NOT NULL CHECK (amount_paise > 0),
  status               VARCHAR(24) NOT NULL,
  provider             VARCHAR(32) NOT NULL DEFAULT 'RAZORPAYX',
  provider_payout_id   VARCHAR(64),
  utr                  VARCHAR(64),
  failure_code         VARCHAR(64),
  failure_description  VARCHAR(300),
  attempts             INTEGER NOT NULL DEFAULT 0,
  -- Sent to the provider as its idempotency key, so a retried request after a
  -- timeout reuses the payout the provider already created rather than paying twice.
  idempotency_key      VARCHAR(120) NOT NULL,
  requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  settled_at           TIMESTAMPTZ,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_payout_item_status CHECK (status IN (
    'PENDING', 'PROCESSING', 'PROCESSED', 'FAILED', 'REVERSED'
  ))
);

-- Paying the same request twice is the failure that costs real money, so the key
-- is unique in our database as well as at the provider.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payout_items_idempotency
  ON payout_items(idempotency_key);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payout_items_provider_id
  ON payout_items(provider_payout_id) WHERE provider_payout_id IS NOT NULL;

-- A partner may have at most one payout in flight. Without this, two requests a
-- second apart each see the full balance and both pay out.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payout_items_one_in_flight
  ON payout_items(user_id) WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX IF NOT EXISTS idx_payout_items_user_created
  ON payout_items(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payout_items_open
  ON payout_items(status, requested_at) WHERE status IN ('PENDING', 'PROCESSING');

-- Same dedup shape as payment_webhook_events: providers redeliver, and a replayed
-- "payout failed" must not reverse a ledger entry a second time.
CREATE TABLE IF NOT EXISTS payout_webhook_events (
  event_id     VARCHAR(120) PRIMARY KEY,
  event_type   VARCHAR(64) NOT NULL,
  received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
