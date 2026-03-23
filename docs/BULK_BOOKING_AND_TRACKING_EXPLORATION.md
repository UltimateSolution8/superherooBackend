# Bulk Booking And Helper Tracking (Exploration Only)

## Goal
Allow a citizen (single user or business) to create multiple tasks in one action and track assigned Superherooos in a single operational view.

## Recommended Product Shape
1. `Bulk draft`:
Create N task lines in one form (CSV upload + in-app editable grid).
2. `Validation + pricing preview`:
Before submit, validate location, schedule, and suggested budget per row.
3. `Batch submission`:
Create one `booking_batch` with many `batch_tasks` (each becomes a normal task internally).
4. `Live tracking board`:
Map + table showing per-task status (`SEARCHING`, `ASSIGNED`, `ARRIVED`, `STARTED`, `COMPLETED`, `CANCELLED`) and assigned helper ETA.
5. `Exception queue`:
Rows needing action: no helper assigned, helper cancelled, SLA breach, OTP mismatch.

## Why This Approach
- Reuses existing task lifecycle and assignment logic (lowest regression risk).
- Supports gradual rollout behind a feature flag.
- Keeps auditability: each row is still a normal task with its own OTP/selfie/rating/payment events.

## Data Model (New, Minimal)
- `booking_batches`
- `id`, `created_by_user_id`, `title`, `notes`, `scheduled_window_start`, `scheduled_window_end`, `status`, `created_at`
- `booking_batch_items`
- `id`, `batch_id`, `task_id`, `line_no`, `external_ref`, `priority`, `created_at`
- `booking_batch_events`
- `id`, `batch_id`, `event_type`, `payload_json`, `created_at`

## API Design (Draft)
- `POST /api/v1/batches/preview`
- validate + return per-line errors and suggested budget.
- `POST /api/v1/batches`
- create batch + tasks atomically where possible.
- `GET /api/v1/batches/{id}`
- summary counts + SLA indicators.
- `GET /api/v1/batches/{id}/items`
- paginated item list with helper/location metadata.
- `GET /api/v1/batches/{id}/live`
- websocket bootstrap snapshot for live board.

## Realtime Design
- New room: `batch:{batchId}`
- Emit aggregated events:
  - `batch.item.status_changed`
  - `batch.item.helper_location`
  - `batch.sla_alert`
- Client can open one socket room instead of subscribing to N task rooms.

## Matching/Dispatch Strategy
- Default: dispatch each task independently (reuse current matching).
- Add optional `batch-aware` fairness:
  - cap per-helper assignments inside same batch.
  - optional zone-aware balancing.

## Pricing Suggestion Strategy
- Per row:
  - base pickup fee + time multiplier + urgency multiplier + distance multiplier.
- Batch-level:
  - optional managed-dispatch fee.
- UI:
  - show `recommended`, `entered`, and confidence badge.

## Tracking UX (Admin + Citizen)
- Grouped views:
  - `Unassigned`, `In Progress`, `Completed`, `Exceptions`.
- Map clusters by area.
- One-click actions:
  - retry dispatch line
  - re-price line
  - cancel line
  - export incident report

## Guardrails
- Per-account max rows per batch.
- Rate limit for batch creation.
- Idempotency key for upload/submit.
- Strict validation for schedule window and geofence.
- Backpressure when realtime consumer lag exceeds threshold.

## Rollout Plan (Safe)
1. Feature flag + schema migration.
2. Internal admin-only batch creation.
3. Pilot with selected citizens/business accounts.
4. Enable citizen self-serve CSV upload.
5. Add SLA analytics and auto-remediation.

## Non-Goals (Phase 1)
- No change to OTP/selfie/rating core flow.
- No helper app workflow changes except receiving normal task offers.

## Success Metrics
- Batch create success rate.
- Median time to first assignment per line.
- `% lines completed within SLA`.
- Dispatch retries per 100 lines.
- Citizen support tickets per batch.

## Risks And Mitigations
- Risk: dispatch storms on large batch submit.
  - Mitigation: staged enqueue with worker concurrency caps.
- Risk: map/UI overload for 100+ active lines.
  - Mitigation: pagination + map clustering + selective subscriptions.
- Risk: partial failures in batch create.
  - Mitigation: preview-first + per-line transactional status + retry endpoint.
