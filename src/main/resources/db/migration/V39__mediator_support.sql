DO $$ 
BEGIN 
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='mediator_id') THEN
    ALTER TABLE booking_batches ADD COLUMN mediator_id UUID REFERENCES users(id);
  END IF;
  
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='requested_helper_count') THEN
    ALTER TABLE booking_batches ADD COLUMN requested_helper_count INTEGER;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='mediator_accepted_at') THEN
    ALTER TABLE booking_batches ADD COLUMN mediator_accepted_at TIMESTAMPTZ;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='scheduled_dispatch_at') THEN
    ALTER TABLE booking_batches ADD COLUMN scheduled_dispatch_at TIMESTAMPTZ;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='mediator_notes') THEN
    ALTER TABLE booking_batches ADD COLUMN mediator_notes TEXT;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='mediator_commission_paise') THEN
    ALTER TABLE booking_batches ADD COLUMN mediator_commission_paise BIGINT;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='task_template_json') THEN
    ALTER TABLE booking_batches ADD COLUMN task_template_json TEXT;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='booking_batches' AND column_name='source_recurring_task_id') THEN
    ALTER TABLE booking_batches ADD COLUMN source_recurring_task_id UUID REFERENCES recurring_tasks(id);
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS mediator_job_workers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES booking_batches(id),
    helper_id UUID NOT NULL REFERENCES users(id),
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attendance_status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, PRESENT, ABSENT
    attendance_marked_at TIMESTAMPTZ,
    task_id UUID REFERENCES tasks(id),  -- linked after dispatch
    payment_status VARCHAR(20) DEFAULT 'PENDING',     -- PENDING, PAID, SKIPPED
    payment_amount_paise BIGINT,
    UNIQUE(batch_id, helper_id)
);

CREATE INDEX IF NOT EXISTS idx_mediator_job_workers_batch ON mediator_job_workers(batch_id);
CREATE INDEX IF NOT EXISTS idx_mediator_job_workers_helper ON mediator_job_workers(helper_id);
CREATE INDEX IF NOT EXISTS idx_booking_batches_mediator ON booking_batches(mediator_id);
CREATE INDEX IF NOT EXISTS idx_booking_batches_status ON booking_batches(status);

CREATE INDEX IF NOT EXISTS idx_booking_batches_source_recurring ON booking_batches(source_recurring_task_id, scheduled_window_start);
