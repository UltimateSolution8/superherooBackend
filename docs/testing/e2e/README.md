# End-to-end and load tests

Run against a **local or staging** stack, never production — both create and
cancel real tasks.

## Setup

```bash
createdb him_e2e && psql -d him_e2e -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"
```

Start the API with a local Postgres and Redis, `OTP_RETURN_IN_RESPONSE=true`
(so the script can read codes back), `REVIEWER_SEED_ENABLED=true` and the
`REVIEWER_*` credentials the scripts expect. Zego properties need dummy values —
the app will not start without them.

## API end-to-end

```bash
python3 api_e2e_test.py
```

55 checks: auth bypass regressions, signup validation, forgot/reset, session
revocation, reviewer accounts, geofence, payment-mode enforcement, matching,
decline, the task lifecycle, and rate limiting. Exits non-zero on any failure.

## Load

```bash
k6 run k6_launch_load.js
```

Thresholds: booking p95 < 3s, marketplace p95 < 1.2s, presence p95 < 600ms,
failures < 2%.

Local numbers are a lower bound — production Postgres and Redis are remote.
