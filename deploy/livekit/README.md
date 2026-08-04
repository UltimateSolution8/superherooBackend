# LiveKit v1.13.5 production deployment

Create `livekit.mysuperhero.xyz` and `turn.mysuperhero.xyz` A records pointing
to `168.144.64.250` before requesting certificates. Both certificates must be
trusted public certificates; WebRTC clients reject self-signed certificates.

Install the pinned `livekit-server` v1.13.5 binary, create a locked `livekit`
system user, and copy the example configuration to
`/etc/livekit/livekit.yaml` with owner `root:livekit` and mode `0640`. Generate
an API key and a high-entropy secret on the server; do not commit them.

Required inbound ports are TCP 22/80/443/7881/5349 and UDP 443/7882. Port 7880
is not public; Nginx is its TLS/WebSocket entry point. TURN/UDP uses UDP 443,
which does not conflict with Nginx on TCP 443.

Backend environment:

```text
LIVEKIT_URL=wss://livekit.mysuperhero.xyz
LIVEKIT_API_KEY=<same key as livekit.yaml>
LIVEKIT_API_SECRET=<same secret as livekit.yaml>
LIVEKIT_TOKEN_TTL_SECONDS=900
```

No Egress service is installed. The retained KYC evidence remains the three
snapshot uploads.

After DNS propagation, run `activate-after-dns.sh` from this directory. It
refuses to issue certificates or enable the firewall unless both hostnames
resolve to the expected droplet first. The activation copies the TURN
certificate into a group-readable LiveKit directory, installs a Certbot deploy
hook to refresh that copy on renewal, and applies the recommended UDP socket
buffer limits before starting the restricted systemd service.
