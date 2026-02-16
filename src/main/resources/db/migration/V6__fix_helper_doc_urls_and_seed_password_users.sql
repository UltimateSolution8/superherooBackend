-- Convert any S3 endpoint URLs to public object URLs for Supabase.
UPDATE helper_profiles
SET kyc_doc_front_url = replace(kyc_doc_front_url,
  'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/',
  'https://ifpfyijghgvioweflcgi.supabase.co/storage/v1/object/public/helpinminutes/')
WHERE kyc_doc_front_url LIKE 'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/%';

UPDATE helper_profiles
SET kyc_doc_back_url = replace(kyc_doc_back_url,
  'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/',
  'https://ifpfyijghgvioweflcgi.supabase.co/storage/v1/object/public/helpinminutes/')
WHERE kyc_doc_back_url LIKE 'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/%';

UPDATE helper_profiles
SET kyc_selfie_url = replace(kyc_selfie_url,
  'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/',
  'https://ifpfyijghgvioweflcgi.supabase.co/storage/v1/object/public/helpinminutes/')
WHERE kyc_selfie_url LIKE 'https://ifpfyijghgvioweflcgi.storage.supabase.co/storage/v1/s3/helpinminutes/%';

-- Seed password-based demo users (keeps OTP flow untouched).
-- Password hashes use PostgreSQL pgcrypto bcrypt via crypt(..., gen_salt('bf')).

-- Ensure bootstrap admin can also login with email/password.
UPDATE users
SET email = COALESCE(email, 'admin@helpinminutes.app'),
    display_name = COALESCE(display_name, 'Platform Admin'),
    password_hash = COALESCE(password_hash, crypt('Admin@12345', gen_salt('bf', 10)))
WHERE role = 'ADMIN' AND phone = '9999999999';

INSERT INTO users (id, role, status, phone, email, display_name, password_hash)
VALUES
  ('10000000-0000-0000-0000-000000000911', 'BUYER', 'ACTIVE', '9777777001', 'buyer1@helpinminutes.app', 'Buyer One', crypt('Buyer@12345', gen_salt('bf', 10))),
  ('10000000-0000-0000-0000-000000000912', 'BUYER', 'ACTIVE', '9777777002', 'buyer2@helpinminutes.app', 'Buyer Two', crypt('Buyer@12345', gen_salt('bf', 10))),
  ('10000000-0000-0000-0000-000000000913', 'HELPER', 'ACTIVE', '9777777011', 'helper.approved@helpinminutes.app', 'Helper Approved', crypt('Helper@12345', gen_salt('bf', 10))),
  ('10000000-0000-0000-0000-000000000914', 'HELPER', 'ACTIVE', '9777777012', 'helper.pending@helpinminutes.app', 'Helper Pending', crypt('Helper@12345', gen_salt('bf', 10)))
ON CONFLICT (email) DO NOTHING;

INSERT INTO helper_profiles (user_id, kyc_status, rating, kyc_full_name, kyc_id_number, created_at, updated_at)
VALUES
  ('10000000-0000-0000-0000-000000000913', 'APPROVED', 0, 'Helper Approved', 'DL-APP-7711', now(), now()),
  ('10000000-0000-0000-0000-000000000914', 'PENDING', 0, 'Helper Pending', 'DL-PEN-7712', now(), now())
ON CONFLICT (user_id) DO NOTHING;
