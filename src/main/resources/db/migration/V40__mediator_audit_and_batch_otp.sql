DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='audit_notes') THEN
    ALTER TABLE booking_batches ADD COLUMN audit_notes TEXT;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='audited_by_user_id') THEN
    ALTER TABLE booking_batches ADD COLUMN audited_by_user_id UUID REFERENCES users(id);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='audited_at') THEN
    ALTER TABLE booking_batches ADD COLUMN audited_at TIMESTAMPTZ;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='batch_start_otp') THEN
    ALTER TABLE booking_batches ADD COLUMN batch_start_otp VARCHAR(10);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='batch_completion_otp') THEN
    ALTER TABLE booking_batches ADD COLUMN batch_completion_otp VARCHAR(10);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_booking_batches_audit_queue ON booking_batches(status, created_at);
