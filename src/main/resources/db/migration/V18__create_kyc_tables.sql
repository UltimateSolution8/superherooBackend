CREATE TABLE kyc_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ DEFAULT now(),
  status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED', 
  video_path TEXT,
  doc_front_path TEXT,
  doc_back_path TEXT,
  ocr_extracted_data JSONB,
  face_match_score NUMERIC,
  liveness_score NUMERIC,
  recommended_action VARCHAR(16), 
  processed_at TIMESTAMPTZ,
  reviewer_admin_id UUID REFERENCES users(id),
  reviewer_notes TEXT,
  retention_expires_at TIMESTAMPTZ,
  raw_result JSONB
);

CREATE TABLE kyc_audit_logs (
  id BIGSERIAL PRIMARY KEY,
  kyc_request_id UUID REFERENCES kyc_requests(id) ON DELETE CASCADE,
  actor_id UUID REFERENCES users(id),
  action VARCHAR(64),
  payload JSONB,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_kyc_requests_user_id ON kyc_requests(user_id);
CREATE INDEX idx_kyc_requests_status ON kyc_requests(status);
