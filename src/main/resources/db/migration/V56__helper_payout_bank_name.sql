ALTER TABLE helper_payout_accounts
  ADD COLUMN IF NOT EXISTS bank_name VARCHAR(160);
