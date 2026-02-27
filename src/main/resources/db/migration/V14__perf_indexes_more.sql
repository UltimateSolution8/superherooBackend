-- Additional performance indexes for admin/support lists
CREATE INDEX IF NOT EXISTS idx_helper_profiles_kyc_status_created_at ON helper_profiles (kyc_status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_tickets_created_by_last_message_at ON support_tickets (created_by_user_id, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_buyer_created_at ON payments (buyer_id, created_at DESC);
