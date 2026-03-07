-- V17__create_photos_table.sql
CREATE TABLE photos (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id uuid NOT NULL,                       -- reference to tasks
  user_id uuid NOT NULL,
  photo_type varchar(32) NOT NULL,            -- 'arrival' | 'completion'
  filename text NOT NULL,
  storage_path text NOT NULL,                 -- S3 key
  content_type varchar(100),
  size_bytes bigint,
  status varchar(32) NOT NULL DEFAULT 'created', -- created, presigned_issued, uploaded, processing, processed, failed
  metadata jsonb DEFAULT '{}'::jsonb,
  created_at timestamptz DEFAULT now(),
  uploaded_at timestamptz,
  processed_at timestamptz,
  CONSTRAINT fk_photos_task FOREIGN KEY (job_id) REFERENCES tasks(id) ON DELETE CASCADE
);

CREATE INDEX idx_photos_job ON photos(job_id);
CREATE INDEX idx_photos_status ON photos(status);
