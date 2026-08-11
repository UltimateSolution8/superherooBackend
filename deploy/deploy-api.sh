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
HEALTH_TRIES="${HEALTH_TRIES:-30}"

[ -f "$JAR" ] || { echo "no jar at $JAR — run: mvn package -DskipTests" >&2; exit 1; }

echo "==> uploading $(du -h "$JAR" | cut -f1) to $HOST:$REMOTE_DIR/app.jar.new"
scp -o ConnectTimeout=15 "$JAR" "$HOST:$REMOTE_DIR/app.jar.new"

echo "==> swapping in and restarting"
ssh -o ConnectTimeout=15 "$HOST" REMOTE_DIR="$REMOTE_DIR" HEALTH_TRIES="$HEALTH_TRIES" 'bash -seu' <<'REMOTE'
cd "$REMOTE_DIR"

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
  code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true)"
  if [ "$code" = "200" ]; then
    echo "healthy after ${i}s"
    exit 0
  fi
  sleep 1
done

echo "health check never returned 200 (last: ${code:-none}) — rolling back" >&2
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
