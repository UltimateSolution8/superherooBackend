ALTER TABLE kyc_requests
  ADD COLUMN live_room_id VARCHAR(128),
  ADD COLUMN live_record_task_id VARCHAR(128),
  ADD COLUMN live_recording_url TEXT,
  ADD COLUMN selfie_path TEXT,
  ADD COLUMN live_started_at TIMESTAMPTZ,
  ADD COLUMN live_ended_at TIMESTAMPTZ;
