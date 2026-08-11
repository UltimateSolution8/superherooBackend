#!/usr/bin/env bash
#
# Ship Backend/target/api-0.1.0.jar to the API host and restart the service.
#
# Uploads beside the live jar, keeps the outgoing one as app.jar.prev, then waits
# for /actuator/health to come back 200 before declaring success. A failed health
# check rolls back to app.jar.prev automatically — the jar is only swapped in
# after the upload has fully landed, so a dropped connection cannot leave a
# truncated file running.
#
#   ./deploy/deploy-api.sh
#
set -euo pipefail

HOST="${API_HOST:-root@142.93.208.120}"
REMOTE_DIR="${API_REMOTE_DIR:-/opt/superheroo}"
JAR="$(cd "$(dirname "$0")/.." && pwd)/target/api-0.1.0.jar"
# Seconds to wait for the app to answer. A cold boot on this box is Flyway
# validation plus Hibernate metadata plus the startup runners — comfortably 40s,
# and a window shorter than that rolls back a deploy that was merely still
# starting.
HEALTH_TRIES="${HEALTH_TRIES:-120}"

[ -f "$JAR" ] || { echo "no jar at $JAR — run: mvn package -DskipTests" >&2; exit 1; }

echo "==> uploading $(du -h "$JAR" | cut -f1) to $HOST:$REMOTE_DIR/app.jar.new"
scp -o ConnectTimeout=15 "$JAR" "$HOST:$REMOTE_DIR/app.jar.new"

echo "==> swapping in and restarting"
ssh -o ConnectTimeout=15 "$HOST" REMOTE_DIR="$REMOTE_DIR" HEALTH_TRIES="$HEALTH_TRIES" 'bash -seu' <<'REMOTE'
cd "$REMOTE_DIR"

# Read the port from the unit's own env file rather than assuming 8080. Probing
# the wrong port looks exactly like a failed boot, and the script would then roll
# back a deploy that was in fact healthy.
PORT="$(sed -n 's/^PORT=\([0-9]\+\).*/\1/p' /etc/superheroo/api.env | tail -1)"
PORT="${PORT:-8080}"

# A short jar is a failed upload, not a deploy. Refuse before touching the live one.
if [ "$(stat -c %s app.jar.new)" -lt 50000000 ]; then
  echo "uploaded jar is implausibly small — aborting" >&2
  rm -f app.jar.new
  exit 1
fi

[ -f app.jar ] && cp -f app.jar app.jar.prev
mv -f app.jar.new app.jar
chown superheroo:superheroo app.jar
systemctl restart superheroo-api

for i in $(seq 1 "$HEALTH_TRIES"); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/actuator/health" || true)"
  if [ "$code" = "200" ]; then
    echo "healthy on port ${PORT} after ${i}s"
    exit 0
  fi
  sleep 1
done

echo "health check on port ${PORT} never returned 200 (last: ${code:-none}) — rolling back" >&2
if [ -f app.jar.prev ]; then
  mv -f app.jar.prev app.jar
  chown superheroo:superheroo app.jar
  systemctl restart superheroo-api
fi
journalctl -u superheroo-api -n 40 --no-pager >&2
exit 1
REMOTE

echo "==> verifying the geo chain"
ssh -o ConnectTimeout=15 "$HOST" \
  "curl -s 'http://127.0.0.1:8080/api/v1/geo/autocomplete?q=madhapur' | head -c 400; echo"
