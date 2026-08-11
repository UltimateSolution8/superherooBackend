#!/usr/bin/env bash
# Promotes a built OSRM release only when the 2 GB host stays within its memory gate.
set -Eeuo pipefail

DATA_ROOT=/var/lib/osrm
RELEASE_DIR="${1:?usage: deploy-osrm-hyderabad.sh /var/lib/osrm/releases/<release-id>}"
CURRENT_LINK="$DATA_ROOT/current"
MIN_AVAILABLE_MIB=350
MAX_OSRM_BYTES=$((512 * 1024 * 1024))

test -s "$RELEASE_DIR/greater-hyderabad.osrm.mldgr"
test "$(id -u)" -eq 0 || { echo 'Run as root.' >&2; exit 1; }

ln -sfn "$RELEASE_DIR" "$CURRENT_LINK"
systemctl daemon-reload
systemctl enable --now superheroo-osrm

fail_closed() {
  echo "OSRM capacity gate failed; disabling it and retaining Ola routing fallback." >&2
  systemctl disable --now superheroo-osrm || true
  exit 1
}

sleep 5
curl --fail --silent --show-error \
  'http://127.0.0.1:5001/route/v1/driving/78.4867,17.3850;78.3872,17.4435?overview=false' >/dev/null \
  || fail_closed

coords='78.4867,17.3850'
for offset in $(seq 1 24); do
  coords="$coords;78.$((4867 + offset)),17.3$((850 + offset))"
done
curl --fail --silent --show-error \
  "http://127.0.0.1:5001/table/v1/driving/$coords?destinations=0&annotations=duration" >/dev/null \
  || fail_closed

available_mib="$(free -m | awk '/^Mem:/ {print $7}')"
osrm_limit="$(docker inspect --format '{{.HostConfig.Memory}}' superheroo-osrm)"
osrm_usage="$(docker stats --no-stream --format '{{.MemUsage}}' superheroo-osrm)"
if [ "${available_mib:-0}" -lt "$MIN_AVAILABLE_MIB" ] \
  || [ "${osrm_limit:-0}" -ne "$MAX_OSRM_BYTES" ]; then
  fail_closed
fi

printf 'OSRM enabled: %s MiB available; container memory %s (limit %s bytes).\n' \
  "$available_mib" "$osrm_usage" "$osrm_limit"
