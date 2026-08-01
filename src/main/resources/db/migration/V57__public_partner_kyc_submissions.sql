CREATE TABLE IF NOT EXISTS public_partner_kyc_submissions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source VARCHAR(40) NOT NULL DEFAULT 'WEB_PUBLIC_KYC',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  full_name VARCHAR(120) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  email VARCHAR(254) NOT NULL,
  doc_type VARCHAR(40) NOT NULL,
  id_number VARCHAR(64) NOT NULL,
  doc_front_url TEXT NOT NULL,
  doc_back_url TEXT,
  selfie_url TEXT NOT NULL,
  account_holder_name VARCHAR(160),
  bank_name VARCHAR(160),
  bank_account_last4 VARCHAR(4),
  ifsc_code VARCHAR(20),
  upi_id_masked VARCHAR(160),
  rejection_reason TEXT,
  reviewed_at TIMESTAMPTZ,
  reviewed_by_admin_id UUID REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_public_partner_kyc_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
  CONSTRAINT ck_public_partner_kyc_source CHECK (source IN ('WEB_PUBLIC_KYC'))
);

CREATE INDEX IF NOT EXISTS idx_public_partner_kyc_status_created
  ON public_partner_kyc_submissions(status, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_public_partner_kyc_phone_created
  ON public_partner_kyc_submissions(phone, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_public_partner_kyc_email_created
  ON public_partner_kyc_submissions(email, created_at DESC);
