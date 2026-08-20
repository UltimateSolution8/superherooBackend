-- Penny drop: proof that a partner's bank account exists and is theirs.
--
-- Until now nothing ever moved helper_payout_accounts.verification_status off
-- NOT_STARTED, so the "eligible" flag was permanently false and, worse,
-- PayoutService never consulted it at all. Turning PAYOUTS_ENABLED on would have
-- sent money to destinations that had never been checked — an IFSC and a
-- plausible-looking account number are not evidence that an account exists.
--
-- A validation credits ₹1 and the bank replies with the name it actually holds
-- the account under. That name is compared to KYC; a mismatch goes to a human,
-- never to auto-verified.

CREATE TABLE payout_account_validations (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_account_id      UUID        NOT NULL REFERENCES helper_payout_accounts(id) ON DELETE CASCADE,
    helper_id              UUID        NOT NULL,
    provider               VARCHAR(32) NOT NULL DEFAULT 'RAZORPAYX',
    -- Razorpay's validation id. Null only between our insert and their response.
    provider_validation_id VARCHAR(64),
    -- PENDING → VERIFIED | FAILED | MANUAL_REVIEW.
    status                 VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    amount_paise           BIGINT      NOT NULL DEFAULT 100,
    -- The name the bank holds the account under, as returned by the drop.
    registered_name        VARCHAR(200),
    name_match_score       INTEGER,
    utr                    VARCHAR(64),
    failure_reason         VARCHAR(300),
    attempts               INTEGER     NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,

    CONSTRAINT ck_validation_status
        CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED', 'MANUAL_REVIEW'))
);

-- A replayed webhook must be a no-op, not a second row.
CREATE UNIQUE INDEX uq_validation_provider_id
    ON payout_account_validations (provider_validation_id)
    WHERE provider_validation_id IS NOT NULL;

-- One validation in flight per account. Mirrors uq_payout_items_one_in_flight:
-- each drop costs real money, and a double-submitted form must not buy two.
CREATE UNIQUE INDEX uq_validation_one_in_flight
    ON payout_account_validations (payout_account_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_validation_account ON payout_account_validations (payout_account_id, created_at DESC);
CREATE INDEX idx_validation_helper ON payout_account_validations (helper_id, created_at DESC);
-- Drives the daily per-account cap and the polling job.
CREATE INDEX idx_validation_pending ON payout_account_validations (status, created_at)
    WHERE status = 'PENDING';
