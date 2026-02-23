# Deployment Costing (Superheroo)

Last updated: 2026-02-23

This document provides a **pragmatic cost model** for three tiers:
1) Minimum/basic version for ~10,000 users
2) Cost-optimized alternative stack
3) Enterprise-grade production

All figures are **monthly USD estimates** based on public pricing. Actual costs depend on traffic, data size, and API usage. For maps and storage, usage-based costs can dominate if not controlled.

## Assumptions (baseline for 10,000 users)
- 10,000 monthly active users (MAU)
- 10,000 tasks/month
- ~100,000 map loads/month (buyer + helper tracking)
- ~25 GB/month egress for app assets, photos, and map tiles
- 2 web frontends (landing + admin)
- 2 backend services (API + realtime)


## 1) Minimum/basic version (~10,000 users)

### Core services
- **Backend API + Realtime** (Render Starter, 2 services)
  - Starter is $7/month per service (billed per instance). 2 services = **$14/month**. citeturn4search1turn4search7
- **Postgres + Storage** (Supabase Pro)
  - Pro plan is **$25/month**. Includes quotas (storage 100GB, egress 250GB). citeturn3search1turn3search3
- **Redis** (Upstash Pay-as-you-go)
  - $0.20 per 100k commands. For 10k users, this is typically low unless you push location updates aggressively. citeturn1search3
- **Frontend hosting (Landing + Admin)** (Vercel Pro)
  - **$20 per paid seat/month**. One seat can host multiple projects. citeturn1search6

### Maps
- **Google Maps Platform**
  - Pay-as-you-go with free usage caps (as of 2025). Pricing varies by API. citeturn1search5

### Typical minimum total (baseline)
- Render (2x Starter): **$14**
- Supabase Pro: **$25**
- Vercel Pro (1 seat): **$20**
- Redis: **$0–$5** (depends on commands)
- Maps: **$0–$50** (depends on map loads, directions)

**Estimated total: ~$60–$110/month** (excluding unexpected usage spikes).


## 2) Cost-optimized alternative stack (without hurting UX)

If Google Maps costs are high, the most practical swap is **MapLibre + MapTiler** or **Mapbox**:
- **MapTiler Flex**: $25/month for 500k requests/month. citeturn2search1
- **Mapbox**: Pay-as-you-go with free tiers for many services. citeturn1search1

Supabase storage/egress costs are predictable if you keep selfies/kyc small:
- Storage overage: **$0.021/GB** after included quota. citeturn3search3
- Egress overage: **$0.09/GB** after included quota. citeturn3search1

**Cost-optimized baseline total**
- Render (2x Starter): **$14** citeturn4search1turn4search7
- Supabase Pro: **$25** citeturn3search1
- Vercel Pro (1 seat): **$20** citeturn1search6
- MapTiler Flex: **$25** citeturn2search1
- Upstash PAYG: **$0–$5** citeturn1search3

**Estimated total: ~$85–$120/month** (more predictable than Google Maps for mid-usage).


## 3) Enterprise-grade (funded) deployment

### Backend & Realtime
- Render Standard or higher for API + Realtime (2–4 instances, autoscaling). Starter is $7; Standard is commonly cited around $25 per service. citeturn4search1turn4search7
- Dedicated Redis (Upstash Fixed + Prod Pack for SLA/RBAC) adds **$10–$200/month** depending on size + prod pack. citeturn1search3

### Database & Storage
- Supabase Team or Enterprise with higher quotas, backups, PITR, and support.
- Expect extra costs for:
  - IOPS beyond included: $0.024/IOPS-month (gp3). citeturn3search0
  - Storage overage: $0.021/GB. citeturn3search3

### Maps
- Google Maps Platform at scale (paid) or MapTiler Unlimited ($295/month) for predictable usage. citeturn2search1

### Monitoring & Observability
- Add APM/logging (Datadog/Grafana/Prometheus). This is often **$100–$1000+/month** depending on logs and traces.

### Enterprise total (rough)
- Backend (4 services @ $25): **$100**
- DB & Storage: **$100–$500** (depends on compute + storage + egress)
- Redis: **$10–$200**
- Maps: **$200–$1000**
- Observability + Alerts: **$200–$1000**
- Vercel seats (team): **$40–$200**

**Estimated total: ~$650–$2,900+/month**, scaling mostly with maps + storage + observability.


## Notes and risk controls
- **Maps cost is the biggest unknown**. Track map loads, direction requests, and geocoding traffic aggressively.
- Use **usage caps** (Supabase spend cap) to protect against runaway costs. citeturn3search4
- Cache map tiles or reduce frequent route re-calculation to control Directions API spend.
- Keep media file size small (selfies compressed) to stay within storage/egress quotas.


## Practical recommendation for launch
- Start with **Render Starter + Supabase Pro + Vercel Pro + Upstash PAYG**.
- Use **Google Maps initially**, but keep a fallback plan to MapLibre + MapTiler if costs spike.
- Enable cost caps and monitoring early.

