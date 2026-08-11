# Self-hosted OSRM — Greater Hyderabad

This is the initial, route-only OpenStreetMap deployment. It supplies the API's
driving route geometry and dispatch ETA matrix. It deliberately does **not** run
Nominatim, Photon, or a tile server on the 2 GB host.

| Capability | Provider order |
|---|---|
| Autocomplete — create-task address entry | Google (Places API New) → Ola → local |
| Autocomplete — everywhere else | Ola → local |
| Place details | Ola → local, plus Google for its own place ids |
| Reverse geocode | API proxy: Ola → Google, then the Expo device geocoder |
| Route and ETA matrix | self-hosted OSRM → Ola → Google/local estimate |
| Map rendering | package-restricted Google Maps SDK |

Ola and OSRM lead everywhere; Google is reached on one screen. Address entry while
creating a task is where a citizen types a place they have not saved, so suggestion
quality decides whether the booking happens — and it is also our lowest-frequency
text call, a couple of dozen a month at launch. `GEO_PREMIUM_CONTEXTS` is the
server-side allowlist of request contexts allowed to reach it; clearing that
variable turns Google off without an app release.

Note that Google still serves place details for its own suggestions regardless of
`GEO_PLACE_DETAILS_ORDER`: a `google:` place id can only be resolved by Google, so
that is one Essentials call per accepted suggestion. Ola suggestions carry
coordinates inline and cost no details call at all.

Two ceilings bound the result — `GEO_GOOGLE_MONTHLY_CALL_CAP` on the bill and
`GEO_GOOGLE_USER_DAILY_CALL_CAP` on any one account. Past either, the chain falls
back to Ola. Routing is deliberately never capped: it only reaches Google when both
OSRM and Ola are down, and no route at all is worse than the call.

## Capacity and security gate

The OSRM service is capped at 512 MB and listens only on `127.0.0.1:5001`.
Enable it only after `deploy-osrm-hyderabad.sh` completes successfully: it warms
both route and table APIs, verifies Docker's exact 512 MB container limit, and
requires at least 350 MiB host `MemAvailable`.
On failure, the script stops and disables OSRM so the API automatically falls
through to Ola. Do not expose port 5001 through NGINX or the firewall.

## Build and deploy

Install prerequisites once:

```bash
sudo apt-get install -y curl osmium-tool
sudo install -m 0755 Backend/deploy/build-osrm-hyderabad.sh /usr/local/sbin/
sudo install -m 0755 Backend/deploy/deploy-osrm-hyderabad.sh /usr/local/sbin/
sudo install -m 0644 Backend/deploy/superheroo-osrm.service /etc/systemd/system/
```

Build the release from the current Telangana regional OSM extract. The build script
clips it to the existing 55 km Hyderabad service radius plus a 15 km routing
buffer (`77.8267,16.7550,79.1467,18.0150`) before running OSRM's MLD pipeline.

```bash
sudo /usr/local/sbin/build-osrm-hyderabad.sh
sudo /usr/local/sbin/deploy-osrm-hyderabad.sh /var/lib/osrm/releases/<release-id>
```

The service pins the tested OSRM v26.8.0 image digest. The build aborts if the
image does not report that version. Releases are immutable under
`/var/lib/osrm/releases/`; `/var/lib/osrm/current` is the only active pointer.

## Verify

```bash
systemctl status superheroo-osrm --no-pager
ss -ltnp | grep ':5001'
curl -s 'http://127.0.0.1:5001/route/v1/driving/78.4867,17.3850;78.3872,17.4435?overview=false'
curl -sS https://api.mysuperhero.xyz/actuator/health
```

The listener must show `127.0.0.1:5001`, never `0.0.0.0:5001`. With
`OSRM_BASE_URL=http://127.0.0.1:5001` and
`GEO_ROUTING_ORDER=osrm,ola,google`, `GET /api/v1/geo/route` reports provider
`osrm` when the local service is healthy.

## Two settings the API must keep in step with this host

`OsrmGeoProvider` refuses requests the extract cannot serve, so it never spends a
timeout learning what it could have known locally. Both bounds are config, and both
have to match what this unit actually runs.

| API env var | Must equal | Why |
|---|---|---|
| `OSRM_COVERAGE_BBOX` | the clip in `build-osrm-hyderabad.sh` | A route with an endpoint outside the extract gets `NoSegment`. Declining locally sends it straight to Ola instead. |
| `OSRM_MAX_TABLE_SIZE` | `--max-table-size` in the unit file (64) | OSRM fails an oversized `/table` **entirely**. That would drop dispatch ranking back to straight-line distance with nothing in the logs to explain it. |

The bbox is written lat-first in the API (`16.7550,77.8267,18.0150,79.1467`) and
lng-first by osmium in the build script (`77.8267,16.7550,79.1467,18.0150`). Same box,
two conventions — change both together, or routing will quietly stop for part of the
city.

Requests outside the box are not errors. `GET /api/v1/geo/route` between Hyderabad and
Vijayawada should report provider `ola`, not `osrm`, and should not appear in the OSRM
logs at all. That is the guard working.

Once a national extract is deployed, set `OSRM_COVERAGE_BBOX=` (blank) to disable the
check, and raise `--max-table-size` and `OSRM_MAX_TABLE_SIZE` together if the dispatch
fanout grows.

## Refresh and attribution

Run a new build and guarded deploy monthly, retain the previous release until a
successful capacity check, then remove only the older release after confirming
the new one has served production traffic. Whenever an app displays an
OSRM-derived route or ETA, show `© OpenStreetMap contributors` visibly beside
that route or ETA.
