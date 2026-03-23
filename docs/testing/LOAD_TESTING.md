# Load Testing (k6)

This project includes a practical end-to-end load scenario:

- buyer login + create task
- helper polls available tasks
- helper attempts accept (if task is visible)
- admin reads summary
- realtime websocket connect (Socket.IO handshake)

Script path:

- `docs/testing/k6_buyer_helper_admin.js`

## Prerequisites

1. Install k6:
   - macOS: `brew install k6`
2. Ensure backend + realtime are reachable.

## Run

```bash
cd Backend
k6 run docs/testing/k6_buyer_helper_admin.js
```

Optional env overrides:

```bash
API_BASE=https://api.mysuperhero.xyz \
REALTIME_WS='wss://superheroorealtime.onrender.com/socket.io/?EIO=4&transport=websocket' \
BUYER_EMAIL=buyer1@helpinminutes.app \
BUYER_PASSWORD=Buyer@12345 \
HELPER_EMAIL=helper.approved@helpinminutes.app \
HELPER_PASSWORD=Helper@12345 \
ADMIN_EMAIL=admin@helpinminutes.app \
ADMIN_PASSWORD=Admin@12345 \
k6 run docs/testing/k6_buyer_helper_admin.js
```

## Current default load profile

- ramp from 1 -> 5 VUs in 30s
- ramp 5 -> 10 VUs in 1m
- ramp down to 0 in 30s

Tune this safely before higher loads in production.

## Notes

- Login endpoint may return `429` under burst (expected throttling behavior).
- Accept step allows `200` or `409` because concurrent runs can race on the same task.
- Use this script first for smoke/perf trends, then create a dedicated soak profile for capacity certification.
