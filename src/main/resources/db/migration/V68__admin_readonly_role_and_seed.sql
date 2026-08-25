-- V68: Rotate full-access admin passwords and seed Read-Only Admin account
--
-- 1. Rotate credentials for existing Full Access Admin accounts (admin1, admin2)
-- 2. Provision new dedicated ADMIN_READONLY role account (readonly.admin@superheroo.test)
-- 3. Invalidate previous refresh tokens for rotated accounts to force clean re-authentication.

-- Update password for Admin One (admin1@superheroo.test)
UPDATE users
SET password_hash = '$2a$10$4p.2a3HwwU2cU95/cNiMEuFkJP3eb2jfiE/CDRIXqW.Ztbk41GKi6',
    updated_at = now()
WHERE email = 'admin1@superheroo.test' OR phone = '9999999001';

-- Update password for Admin Two (admin2@superheroo.test)
UPDATE users
SET password_hash = '$2a$10$VKHnmzgMGqOrlfbSLJsn5.eLIIp2Hiny9DKylvTRmjB3WFbBtqtT.',
    updated_at = now()
WHERE email = 'admin2@superheroo.test' OR phone = '9999999002';

-- Provision Read-Only Admin account
INSERT INTO users (
    id,
    phone,
    email,
    display_name,
    role,
    status,
    email_verified,
    password_hash,
    created_at,
    updated_at
)
VALUES (
    gen_random_uuid(),
    '9999999009',
    'readonly.admin@superheroo.test',
    'Read-Only Admin',
    'ADMIN_READONLY',
    'ACTIVE',
    TRUE,
    '$2a$10$ryNubBdr1LY44I9PKy.x3uka4FBnv/SN33wldznpTCDWZZKCuqrSa',
    now(),
    now()
)
ON CONFLICT (email) DO UPDATE SET
    role = 'ADMIN_READONLY',
    status = 'ACTIVE',
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    updated_at = now();

-- Revoke any active refresh tokens for the modified accounts
DELETE FROM refresh_tokens
WHERE user_id IN (
    SELECT id FROM users
    WHERE email IN ('admin1@superheroo.test', 'admin2@superheroo.test', 'readonly.admin@superheroo.test')
);
