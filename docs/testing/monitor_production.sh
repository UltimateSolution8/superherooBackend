#!/bin/bash
# Production Health Monitor for Help in Minutes
# Usage: ./monitor_production.sh [interval_seconds]
# Logs to monitor_production.log

API_BASE="https://api.mysuperhero.xyz"
INTERVAL=${1:-30}
LOG_FILE="monitor_production.log"
ALERT_THRESHOLD_MS=500
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"; }

log "=== Production Monitor Started (interval: ${INTERVAL}s) ==="
log "Target: $API_BASE | Alert threshold: ${ALERT_THRESHOLD_MS}ms"

while true; do
  # Health check with timing
  RESULT=$(curl -s -o /tmp/health_resp.json -w "%{http_code} %{time_total}" \
    --connect-timeout 5 --max-time 10 "$API_BASE/actuator/health" 2>/dev/null)
  HTTP_CODE=$(echo $RESULT | awk '{print $1}')
  TIME_S=$(echo $RESULT | awk '{print $2}')
  TIME_MS=$(echo "$TIME_S * 1000" | bc | cut -d. -f1)

  if [ "$HTTP_CODE" = "200" ]; then
    STATUS=$(python3 -c "import json,sys; d=json.load(open('/tmp/health_resp.json')); print(d.get('status','?'))" 2>/dev/null || echo "?")
    if [ "$STATUS" = "UP" ]; then
      if [ "$TIME_MS" -gt "$ALERT_THRESHOLD_MS" ] 2>/dev/null; then
        log "⚠️  SLOW: health=${HTTP_CODE} status=${STATUS} time=${TIME_MS}ms (>${ALERT_THRESHOLD_MS}ms)"
      else
        log "✅ OK: health=${HTTP_CODE} status=${STATUS} time=${TIME_MS}ms"
      fi
    else
      log "🔴 DEGRADED: health=${HTTP_CODE} status=${STATUS} time=${TIME_MS}ms"
    fi
  else
    log "🔴 DOWN: health=${HTTP_CODE} time=${TIME_MS}ms — ALERT: service may be down!"
  fi

  # SSL certificate expiry check (every 6 hours = every 720 intervals at 30s)
  if [ $(( $(date +%s) % 21600 )) -lt "$INTERVAL" ] 2>/dev/null; then
    DAYS=$(echo | openssl s_client -connect api.mysuperhero.xyz:443 \
      -servername api.mysuperhero.xyz 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | \
      awk -F= '{print $2}' | xargs -I{} python3 -c \
      "from datetime import datetime; import sys; \
       d=datetime.strptime('{}','%b %d %H:%M:%S %Y %Z'); \
       print((d-datetime.utcnow()).days)" 2>/dev/null || echo "?")
    log "🔐 SSL: $DAYS days until certificate expiry"
    if [ "$DAYS" != "?" ] && [ "$DAYS" -lt 14 ] 2>/dev/null; then
      log "⚠️  SSL certificate expires in $DAYS days — RENEW SOON!"
    fi
  fi

  sleep "$INTERVAL"
done
