-- Commission rates, changeable without a deploy.
--
-- Until now the platform's take was a single environment variable read at boot
-- (PLATFORM_COMMISSION_BPS), so changing it meant a restart, and there was no way
-- to vary it per category or per partner. Worse, the admin revenue reports had
-- 0.15 hardcoded in four places, so any change to the env var silently made the
-- reports disagree with the ledger.
--
-- Rows are append-only and effective-dated. A rate change closes the current row
-- and opens a new one; it never rewrites history, so a ledger entry booked last
-- month still reconciles against the rate that was in force when it was booked.

CREATE TABLE commission_settings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- GLOBAL applies to everyone; CATEGORY to one task category; HELPER to one
    -- partner. Resolution is most-specific-first: HELPER, then CATEGORY, then
    -- GLOBAL, then the configured default.
    scope           VARCHAR(16)  NOT NULL,
    -- Null for GLOBAL. The category name or the helper's user id otherwise.
    scope_ref       VARCHAR(128),
    commission_bps  INTEGER      NOT NULL,
    effective_from  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Null means "still in force".
    effective_to    TIMESTAMPTZ,
    created_by      UUID,
    note            TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_commission_scope
        CHECK (scope IN ('GLOBAL', 'CATEGORY', 'HELPER')),
    -- 0%–50%. The upper bound matches the @Max on the property it replaces; a
    -- typo that sets 95% should fail loudly rather than quietly take a partner's
    -- earnings.
    CONSTRAINT ck_commission_bps_range
        CHECK (commission_bps >= 0 AND commission_bps <= 5000),
    CONSTRAINT ck_commission_scope_ref
        CHECK ((scope = 'GLOBAL' AND scope_ref IS NULL)
            OR (scope <> 'GLOBAL' AND scope_ref IS NOT NULL)),
    CONSTRAINT ck_commission_effective_order
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- At most one rate in force per scope at any time. Without this a
-- double-submitted admin form leaves two live rows and resolution becomes a
-- coin toss between them.
CREATE UNIQUE INDEX uq_commission_current_global
    ON commission_settings (scope)
    WHERE effective_to IS NULL AND scope = 'GLOBAL';

CREATE UNIQUE INDEX uq_commission_current_scoped
    ON commission_settings (scope, scope_ref)
    WHERE effective_to IS NULL AND scope <> 'GLOBAL';

CREATE INDEX idx_commission_lookup
    ON commission_settings (scope, scope_ref, effective_from DESC);

-- Records the rate each commission entry was booked at, so a historical row is
-- self-describing and does not have to be re-derived from the settings table.
ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS commission_bps INTEGER;
