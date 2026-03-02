alter table tasks
  add column if not exists cancel_reason text,
  add column if not exists cancelled_by_role text,
  add column if not exists cancelled_by_user_id uuid,
  add column if not exists cancelled_at timestamptz;
