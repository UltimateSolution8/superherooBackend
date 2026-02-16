-- Ensure admin has email/password for demo login even if bootstrapped after earlier migrations.
UPDATE users
SET email = COALESCE(email, 'admin@helpinminutes.app'),
    display_name = COALESCE(display_name, 'Platform Admin'),
    password_hash = COALESCE(password_hash, crypt('Admin@12345', gen_salt('bf', 10)))
WHERE role = 'ADMIN'
  AND phone = '9999999999';
