#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT_PATH="${ROOT_DIR}/k6_buyer_helper_admin.js"

API_BASE="${API_BASE:-https://api.mysuperhero.xyz}"
REALTIME_WS="${REALTIME_WS:-wss://superheroorealtime.onrender.com/socket.io/?EIO=4&transport=websocket}"

echo "== K6 One-Click Runner =="
echo "API_BASE=${API_BASE}"
echo "REALTIME_WS=${REALTIME_WS}"
echo "SCRIPT=${SCRIPT_PATH}"

if [[ ! -f "${SCRIPT_PATH}" ]]; then
  echo "ERROR: k6 script not found at ${SCRIPT_PATH}" >&2
  exit 1
fi

echo
echo "== Preflight: backend =="
curl -fsS "${API_BASE}/actuator/health" >/tmp/k6_backend_health.json
cat /tmp/k6_backend_health.json | sed -n '1,5p'

echo
echo "== Preflight: realtime =="
REALTIME_HTTP="${REALTIME_WS#ws://}"
REALTIME_HTTP="${REALTIME_HTTP#wss://}"
REALTIME_HTTP="https://${REALTIME_HTTP%%/*}/health"
curl -fsS "${REALTIME_HTTP}" >/tmp/k6_realtime_health.json
cat /tmp/k6_realtime_health.json | sed -n '1,5p'

echo
if ! command -v k6 >/dev/null 2>&1; then
  echo "ERROR: k6 is not installed." >&2
  echo "Install: brew install k6" >&2
  exit 2
fi

echo "== Running k6 scenario =="
API_BASE="${API_BASE}" \
REALTIME_WS="${REALTIME_WS}" \
k6 run "${SCRIPT_PATH}" "$@"
