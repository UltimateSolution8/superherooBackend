-- V51__reporting_module.sql

-- 1. Haversine Distance Calculation Function (in Kilometers)
CREATE OR REPLACE FUNCTION haversine_km(
    lat1 FLOAT, lon1 FLOAT,
    lat2 FLOAT, lon2 FLOAT
) RETURNS FLOAT AS $$
DECLARE
    dlat FLOAT;
    dlon FLOAT;
    a FLOAT;
    c FLOAT;
    r FLOAT := 6371.0; -- Earth's radius in kilometers
BEGIN
    IF lat1 IS NULL OR lon1 IS NULL OR lat2 IS NULL OR lon2 IS NULL THEN
        RETURN NULL;
    END IF;
    IF lat1 = lat2 AND lon1 = lon2 THEN
        RETURN 0.0;
    END IF;
    
    dlat := radians(lat2 - lat1);
    dlon := radians(lon2 - lon1);
    a := sin(dlat / 2.0)^2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon / 2.0)^2;
    c := 2.0 * atan2(sqrt(a), sqrt(1.0 - a));
    RETURN r * c;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- 2. System Audit Logs Table for Security and Admin Activity Tracking
CREATE TABLE IF NOT EXISTS system_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID,
    actor_email VARCHAR(255),
    actor_role VARCHAR(50) NOT NULL,
    action_type VARCHAR(100) NOT NULL,
    target_resource VARCHAR(100),
    target_id VARCHAR(255),
    details TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sys_audit_logs_created_at ON system_audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sys_audit_logs_action ON system_audit_logs(action_type);
CREATE INDEX IF NOT EXISTS idx_sys_audit_logs_actor ON system_audit_logs(actor_id);

-- 3. Daily Aggregate Summary Fact Table
CREATE TABLE IF NOT EXISTS reporting_daily_metrics (
    metric_date DATE PRIMARY KEY,
    total_gmv_paise BIGINT NOT NULL DEFAULT 0,
    net_revenue_paise BIGINT NOT NULL DEFAULT 0,
    platform_commission_paise BIGINT NOT NULL DEFAULT 0,
    helper_payout_paise BIGINT NOT NULL DEFAULT 0,
    total_bookings INT NOT NULL DEFAULT 0,
    completed_bookings INT NOT NULL DEFAULT 0,
    cancelled_bookings INT NOT NULL DEFAULT 0,
    searches_count INT NOT NULL DEFAULT 0,
    new_buyers_count INT NOT NULL DEFAULT 0,
    new_helpers_count INT NOT NULL DEFAULT 0,
    active_buyers_count INT NOT NULL DEFAULT 0,
    active_helpers_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. Materialized Views for High-Performance Aggregations

-- Materialized View: Location Performance
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_location_performance AS
SELECT
    COALESCE(address_text, 'Unknown') AS location_name,
    COUNT(id) AS total_bookings,
    COUNT(CASE WHEN status = 'COMPLETED' THEN 1 END) AS completed_bookings,
    COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END) AS cancelled_bookings,
    SUM(CASE WHEN status = 'COMPLETED' THEN COALESCE(budget_paise, 0) ELSE 0 END) AS total_gmv_paise,
    AVG(CASE WHEN status = 'COMPLETED' THEN COALESCE(budget_paise, 0) ELSE NULL END) AS avg_booking_value_paise
FROM tasks
GROUP BY COALESCE(address_text, 'Unknown');

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_location_perf ON mv_location_performance(location_name);

-- Materialized View: Service Category Performance
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_service_performance AS
SELECT
    title AS service_title,
    COUNT(id) AS total_bookings,
    COUNT(CASE WHEN status = 'COMPLETED' THEN 1 END) AS completed_bookings,
    COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END) AS cancelled_bookings,
    SUM(CASE WHEN status = 'COMPLETED' THEN COALESCE(budget_paise, 0) ELSE 0 END) AS total_gmv_paise,
    AVG(CASE WHEN status = 'COMPLETED' THEN COALESCE(time_minutes, 0) ELSE NULL END) AS avg_duration_minutes,
    AVG(buyer_rating) AS avg_rating
FROM tasks
GROUP BY title;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_service_perf ON mv_service_performance(service_title);

-- Materialized View: Helper Performance Summary
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_helper_performance_summary AS
SELECT
    t.assigned_helper_id AS helper_id,
    COUNT(t.id) AS total_assigned,
    COUNT(CASE WHEN t.status = 'COMPLETED' THEN 1 END) AS completed_count,
    COUNT(CASE WHEN t.status = 'CANCELLED' AND t.cancelled_by_role = 'HELPER' THEN 1 END) AS cancelled_count,
    AVG(t.helper_rating) AS avg_rating_given_to_buyer,
    AVG(t.buyer_rating) AS avg_rating_received,
    SUM(CASE WHEN t.status = 'COMPLETED' THEN COALESCE(t.budget_paise, 0) ELSE 0 END) AS total_earnings_paise
FROM tasks t
WHERE t.assigned_helper_id IS NOT NULL
GROUP BY t.assigned_helper_id;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_helper_perf ON mv_helper_performance_summary(helper_id);

-- Performance Indexes on Core Operational Tables
CREATE INDEX IF NOT EXISTS idx_tasks_created_status ON tasks(created_at DESC, status);
CREATE INDEX IF NOT EXISTS idx_tasks_buyer_created ON tasks(buyer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tasks_helper_status ON tasks(assigned_helper_id, status);
CREATE INDEX IF NOT EXISTS idx_payments_created_status ON payments(created_at DESC, status);
