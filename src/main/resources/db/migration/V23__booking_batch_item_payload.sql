alter table if exists booking_batch_items
  add column if not exists payload_json text null;

