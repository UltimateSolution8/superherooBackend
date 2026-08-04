#!/usr/bin/env bash
set -euo pipefail

expected_ip=168.144.64.250
for hostname in livekit.mysuperhero.xyz turn.mysuperhero.xyz; do
  resolved_ip="$(getent ahostsv4 "$hostname" | awk 'NR == 1 { print $1 }')"
  if [[ "$resolved_ip" != "$expected_ip" ]]; then
    echo "$hostname must resolve to $expected_ip before activation" >&2
    exit 1
  fi
done

install -o root -g root -m 0644 nginx-livekit-acme.conf /etc/nginx/sites-enabled/livekit-acme.conf
nginx -t
systemctl reload nginx

certbot certonly --non-interactive --agree-tos --webroot -w /var/www/html \
  -m ops@mysuperhero.xyz -d livekit.mysuperhero.xyz
certbot certonly --non-interactive --agree-tos --webroot -w /var/www/html \
  -m ops@mysuperhero.xyz -d turn.mysuperhero.xyz

install -d -o root -g livekit -m 0750 /etc/livekit/certs
install -o root -g livekit -m 0640 \
  /etc/letsencrypt/live/turn.mysuperhero.xyz/fullchain.pem \
  /etc/livekit/certs/turn-fullchain.pem
install -o root -g livekit -m 0640 \
  /etc/letsencrypt/live/turn.mysuperhero.xyz/privkey.pem \
  /etc/livekit/certs/turn-privkey.pem
install -o root -g root -m 0755 livekit-cert-renew-hook.sh \
  /etc/letsencrypt/renewal-hooks/deploy/livekit-cert-renew-hook.sh
install -o root -g root -m 0644 99-livekit-udp-buffer.conf \
  /etc/sysctl.d/99-livekit-udp-buffer.conf
sysctl --system >/dev/null

install -o root -g root -m 0644 nginx-livekit.conf /etc/nginx/sites-enabled/livekit.mysuperhero.xyz
rm -f /etc/nginx/sites-enabled/livekit-acme.conf
nginx -t

ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw allow 7881/tcp
ufw allow 7882/udp
ufw allow 5349/tcp
ufw --force enable

systemctl enable --now livekit-server.service
systemctl reload nginx
systemctl --no-pager --full status livekit-server.service
