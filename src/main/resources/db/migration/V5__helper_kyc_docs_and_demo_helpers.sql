ALTER TABLE helper_profiles
  ADD COLUMN IF NOT EXISTS kyc_full_name VARCHAR(120),
  ADD COLUMN IF NOT EXISTS kyc_id_number VARCHAR(64),
  ADD COLUMN IF NOT EXISTS kyc_doc_front_url TEXT,
  ADD COLUMN IF NOT EXISTS kyc_doc_back_url TEXT,
  ADD COLUMN IF NOT EXISTS kyc_selfie_url TEXT,
  ADD COLUMN IF NOT EXISTS kyc_submitted_at TIMESTAMPTZ;

INSERT INTO users (id, role, status, phone)
VALUES
  ('10000000-0000-0000-0000-000000000901', 'HELPER', 'ACTIVE', '8888888901'),
  ('10000000-0000-0000-0000-000000000902', 'HELPER', 'ACTIVE', '8888888902')
ON CONFLICT (phone) DO NOTHING;

INSERT INTO helper_profiles (
  user_id,
  kyc_status,
  rating,
  kyc_full_name,
  kyc_id_number,
  kyc_doc_front_url,
  kyc_doc_back_url,
  kyc_selfie_url,
  kyc_submitted_at
)
VALUES
  (
    '10000000-0000-0000-0000-000000000901',
    'PENDING',
    0,
    'Ravi Kumar',
    'DL-TS-459201',
    'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/demo-kyc/ravi-id-front.jpg',
    'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/demo-kyc/ravi-id-back.jpg',
    'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/demo-kyc/ravi-selfie.jpg',
    now() - interval '2 day'
  ),
  (
    '10000000-0000-0000-0000-000000000902',
    'PENDING',
    0,
    'Sita Reddy',
    'AADHAAR-66442211',
    'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/demo-kyc/sita-id-front.jpg',
    'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/demo-kyc/sita-id-back.jpg',
    'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/demo-kyc/sita-selfie.jpg',
    now() - interval '1 day'
  )
ON CONFLICT (user_id) DO NOTHING;
