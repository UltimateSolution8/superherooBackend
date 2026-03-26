create table if not exists booking_batches (
  id uuid primary key,
  created_by_user_id uuid not null references users(id),
  title varchar(160) not null,
  notes text null,
  scheduled_window_start timestamptz null,
  scheduled_window_end timestamptz null,
  status varchar(24) not null default 'CREATED',
  idempotency_key varchar(120) null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint chk_booking_batch_status check (status in ('CREATED', 'PARTIAL', 'COMPLETED', 'CANCELLED'))
);

create unique index if not exists uq_booking_batches_creator_idempotency
  on booking_batches(created_by_user_id, idempotency_key)
  where idempotency_key is not null;

create table if not exists booking_batch_items (
  id uuid primary key,
  batch_id uuid not null references booking_batches(id) on delete cascade,
  task_id uuid null references tasks(id),
  line_no int not null,
  external_ref varchar(120) null,
  priority int not null default 3,
  line_status varchar(24) not null default 'CREATED',
  error_message varchar(400) null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint chk_booking_batch_line_status check (line_status in ('CREATED', 'FAILED'))
);

create unique index if not exists uq_booking_batch_items_line
  on booking_batch_items(batch_id, line_no);

create index if not exists idx_booking_batch_items_batch
  on booking_batch_items(batch_id);

create index if not exists idx_booking_batch_items_task
  on booking_batch_items(task_id);

create table if not exists booking_batch_events (
  id uuid primary key,
  batch_id uuid not null references booking_batches(id) on delete cascade,
  event_type varchar(60) not null,
  payload_json text null,
  created_at timestamptz not null default now()
);

create index if not exists idx_booking_batch_events_batch_created
  on booking_batch_events(batch_id, created_at desc);

