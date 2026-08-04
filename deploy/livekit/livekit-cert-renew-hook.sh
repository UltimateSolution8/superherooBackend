#!/usr/bin/env bash
set -euo pipefail

case "${RENEWED_DOMAINS:-}" in
  *turn.mysuperhero.xyz*) ;;
  *) exit 0 ;;
esac

install -d -o root -g livekit -m 0750 /etc/livekit/certs
install -o root -g livekit -m 0640 \
  /etc/letsencrypt/live/turn.mysuperhero.xyz/fullchain.pem \
  /etc/livekit/certs/turn-fullchain.pem
install -o root -g livekit -m 0640 \
  /etc/letsencrypt/live/turn.mysuperhero.xyz/privkey.pem \
  /etc/livekit/certs/turn-privkey.pem
systemctl try-restart livekit-server.service
