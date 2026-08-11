#!/usr/bin/env bash
# Builds a bounded Greater Hyderabad OSRM release from the Telangana extract.
# Run as root on the maps host. It does not start or enable the service.
set -Eeuo pipefail

DATA_ROOT=/var/lib/osrm
RELEASE_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RELEASE_DIR="$DATA_ROOT/releases/$RELEASE_ID"
PBF_URL=https://download.openstreetmap.fr/extracts/asia/india/telangana-latest.osm.pbf
OSRM_IMAGE=ghcr.io/project-osrm/osrm-backend@sha256:3ac496ff8fd7e1af53846179d73d06a97f719c8ad2217d008ed868942398665c
# 55 km Hyderabad service radius plus a 15 km routing buffer.
HYDERABAD_BBOX=77.8267,16.7550,79.1467,18.0150

require() { command -v "$1" >/dev/null || { echo "Missing required command: $1" >&2; exit 1; }; }
require curl
require docker
require osmium

install -d -m 0755 "$DATA_ROOT/releases" "$RELEASE_DIR"
trap 'rm -rf "$RELEASE_DIR"' ERR

docker pull "$OSRM_IMAGE"
docker run --rm "$OSRM_IMAGE" osrm-routed --version | grep -qx 'v26.8.0'

curl --fail --location --retry 3 --output "$RELEASE_DIR/telangana-latest.osm.pbf" "$PBF_URL"
osmium extract --strategy=simple --bbox="$HYDERABAD_BBOX" \
  --output="$RELEASE_DIR/greater-hyderabad.osm.pbf" "$RELEASE_DIR/telangana-latest.osm.pbf"
rm "$RELEASE_DIR/telangana-latest.osm.pbf"

docker run --rm -v "$RELEASE_DIR:/data" "$OSRM_IMAGE" \
  osrm-extract -p /opt/car.lua /data/greater-hyderabad.osm.pbf
docker run --rm -v "$RELEASE_DIR:/data" "$OSRM_IMAGE" \
  osrm-partition /data/greater-hyderabad.osrm
docker run --rm -v "$RELEASE_DIR:/data" "$OSRM_IMAGE" \
  osrm-customize /data/greater-hyderabad.osrm

test -s "$RELEASE_DIR/greater-hyderabad.osrm.mldgr"
printf 'Built OSRM release: %s\n' "$RELEASE_DIR"
printf 'Validate it with deploy-osrm-hyderabad.sh before promoting it.\n'
