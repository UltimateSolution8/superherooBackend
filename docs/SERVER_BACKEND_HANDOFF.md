# Superherooo Backend Server Handoff

Last verified: 2026-07-05 (IST)

## Active Server

- Provider: DigitalOcean
- Public IP: `168.144.64.250`
- Private IP: `10.122.0.2`
- Hostname observed: `ubuntu-s-1vcpu-1gb-blr1`
- API domain: `https://api.mysuperhero.xyz`
- Spring Boot service: `superheroo-api.service`
- App directory: `/opt/superheroo`
- JAR path: `/opt/superheroo/app.jar`
- Environment file: `/etc/superheroo/api.env`
- Backend port behind Nginx: `8081`
- Nginx listens on: `80`, `443`

## SSH

Use the Mac default Ed25519 key that was added to the new server:

```bash
ssh root@168.144.64.250
```

If you need to force the key path:

```bash
ssh -i ~/.ssh/id_ed25519 root@168.144.64.250
```

Previous old server, likely no longer used/accessed:

```bash
ssh -i ~/.ssh/id_rsa_do root@159.89.167.248
```

## Health Checks

Fast app health:

```bash
curl -sS https://api.mysuperhero.xyz/health
curl -sS https://api.mysuperhero.xyz/api/v1/health
```

Actuator health:

```bash
curl -sS https://api.mysuperhero.xyz/actuator/health
```

For demo stability, actuator external dependency indicators are disabled on the server via:

```bash
MANAGEMENT_HEALTH_DEFAULTS_ENABLED=false
MANAGEMENT_HEALTH_REDIS_ENABLED=false
MANAGEMENT_HEALTH_RABBIT_ENABLED=false
MANAGEMENT_HEALTH_DB_ENABLED=false
```

This keeps the public health endpoint fast and prevents Supabase/Redis/Rabbit probe latency from making health checks hang.

## Service Commands

```bash
systemctl status superheroo-api --no-pager -l
systemctl restart superheroo-api
journalctl -u superheroo-api -n 200 --no-pager
journalctl -u superheroo-api -f
```

Nginx:

```bash
nginx -t
systemctl reload nginx
systemctl status nginx --no-pager
```

Ports:

```bash
ss -ltnp | grep -E ':(80|443|8081)'
```

## Deploy JAR

From local backend repo:

```bash
cd Backend
JAVA_TOOL_OPTIONS='-Dnet.bytebuddy.experimental=true' mvn -q -DskipTests package
scp target/*.jar root@168.144.64.250:/tmp/superheroo-api.jar
ssh root@168.144.64.250 'cp /opt/superheroo/app.jar /opt/superheroo/app.jar.bak.$(date +%Y%m%d%H%M%S) && mv /tmp/superheroo-api.jar /opt/superheroo/app.jar && chown superheroo:superheroo /opt/superheroo/app.jar && systemctl restart superheroo-api && sleep 20 && curl -sS https://api.mysuperhero.xyz/actuator/health'
```

## Important Environment Variables

Secrets are intentionally not written in this document. Keep actual values only in `/etc/superheroo/api.env` or a secure password manager.

Required categories:

- App/JWT: `APP_ENV`, `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`, TTL values
- Database: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, SSL flags
- Redis: `REDIS_URL` or Upstash-compatible Redis URL
- Supabase S3: access key, secret, bucket, endpoint, region
- OTP: `OTP_RETURN_IN_RESPONSE`, Exotel/Twilio provider env vars if enabled
- Realtime: `REALTIME_HTTP_PUBLISH_URL`, `REALTIME_HTTP_PUBLISH_SECRET`, `REALTIME_REDIS_CHANNEL`
- Firebase/Push: `FIREBASE_SERVICE_ACCOUNT_JSON` or base64 equivalent
- Zego: `ZEGO_APP_ID`, `ZEGO_SERVER_SECRET`, `ZEGO_CALLBACK_SECRET`
- Sentry: `SENTRY_DSN`, sampling flags

## Current Verification Commands Used

```bash
curl -sS https://api.mysuperhero.xyz/actuator/health
curl -sS https://api.mysuperhero.xyz/health
curl -sS -X POST https://api.mysuperhero.xyz/api/v1/auth/otp/start \
  -H 'Content-Type: application/json' \
  -d '{"phone":"9000000101","role":"BUYER"}'
```
