# REST API

Everything under `/api` uses **HTTP Basic** authentication and is exempt from CSRF, so it works
from scripts. The browser forms are a separate, CSRF-protected set of endpoints.

| Path | Minimum role |
|---|---|
| `GET /api/health`, `GET /api/stats/**` | `VIEWER` |
| `POST /api/scan/report` | `AGENT`, or `OPERATOR` |
| everything else under `/api/**` | `OPERATOR` |
| `/api/agent/**`, `/agent/**` | `AGENT` (an enrollment key, not a password) |

In the examples below, replace `HOST` and the credentials.

---

## Health

```bash
curl -sS -u admin:admin http://HOST:8080/api/health
```
```json
{ "status": "ok", "endpoints": ["default", "warehouse-nas"] }
```

## Endpoint status

```bash
curl -sS -u admin:admin http://HOST:8080/api/endpoints/33/status
```

For a direct `clamd` endpoint it pings and parses the version:

```json
{ "online": true, "clamVersion": "1.5.4", "dbVersion": "28129",
  "dbDate": "...", "dbAgeDays": 2, "stale": false }
```

For an **agent-managed** endpoint it does not ping anything — `online` means the agent checked in
within the last 15 minutes:

```json
{ "online": true, "agent": true, "lastSeen": "2026-09-22T19:40:11Z", "dbVersion": "28129" }
```

When it is down, `error` carries a diagnosis rather than a generic failure — `Connection refused`,
`No answer` (packets dropped, i.e. a firewall), `Unknown host`, `No route`.

## Jobs

```bash
curl -sS -u admin:admin http://HOST:8080/api/jobs          # 200 most recent
curl -sS -u admin:admin http://HOST:8080/api/jobs/active   # queued or running right now
curl -sS -u admin:admin http://HOST:8080/api/jobs/<jobId>  # one job
```

## Dashboard statistics

```bash
curl -sS -u admin:admin 'http://HOST:8080/api/stats/timeseries?days=14'
```
```json
{ "labels": ["2026-09-09", "..."],
  "total": [0, 12, 0, 340],  "ok": [...], "virus": [...], "error": [...],
  "endpoints": [0, 1, 0, 2],
  "lastScanAt": "2026-08-05T21:34:10Z" }
```

`days` is clamped to 1–90. **Days with no scans are included as zeros** — a series that skipped
them would draw a quiet week and a busy week identically. `lastScanAt` lets a caller tell "no
scans in this window" apart from "no scans ever".

## Starting a scan

### Upload

The field names matter and are the most common source of 400s: the part is **`files`** (repeatable)
and **`endpointId`** is numeric.

```bash
curl -u admin:admin \
  -F 'endpointId=33' \
  -F 'files=@/path/a.txt' -F 'files=@/path/b.txt' \
  http://HOST:8080/api/scan/upload
```

`Required part 'files' is not present` means you sent `file`. `Required parameter 'endpointId' is
not present` means you sent the endpoint's name.

### Path

```bash
curl -u admin:admin -H 'Content-Type: application/json' \
  -d '{"path":"/scandir/file","endpointId":33}' \
  http://HOST:8080/api/scan/path
```

The path must be under the configured allowed roots, or the request is refused.

## Reporting a scan run elsewhere

This is how a machine pushes a result *into* the console. It creates an already-finished job, so
notifications fire immediately.

```bash
curl -u operator:secret -H 'Content-Type: application/json' \
  -d '{
        "hostname": "web-01",
        "path": "/srv/uploads",
        "verdict": "VIRUS_FOUND",
        "findings": ["/srv/uploads/x.zip: Eicar-Test-Signature FOUND"],
        "source": "realtime",
        "remediation": "quarantined",
        "remediationPath": "/var/lib/clamav/quarantine/x.zip"
      }' \
  http://HOST:8080/api/scan/report
```

| Field | Notes |
|---|---|
| `hostname` | **Required.** Also the fallback target when `path` is absent — a spontaneous report may have no path, and the job's target column cannot be null. |
| `verdict` | `VIRUS_FOUND`, `ERROR`, or `OK` **only together with `commandId`** (see below). |
| `findings` | Raw `path: SIGNATURE FOUND` lines — exactly `clamdscan`'s and `clamonacc`'s own output format. |
| `errorMessage` | For `ERROR`. |
| `source` | `realtime` marks an on-access event; anything else is treated as a batch scan. |
| `commandId` | Set when this answers a scan the console asked for. |
| `remediation` | `quarantined`, `removed`, `failed`; anything else means not attempted. |
| `remediationPath` | Where the file ended up, for `quarantined`. |

> [!NOTE]
> **`OK` is rejected without a `commandId`.** For a scan a user launched, "nothing found" is the
> answer they are waiting for. For spontaneous reports — cron, on-access — accepting `OK` would
> fill the job list with clean runs and bury the ones that matter.

## Agent endpoints

Used by the agents, authenticated with an enrollment key (`X-Agent-Key`, `Authorization: Bearer`,
or `?key=`) rather than a password.

| Endpoint | Purpose |
|---|---|
| `GET /api/agent/commands` | Fetch pending scan commands. **Fetching claims them.** This request is also the heartbeat. |
| `GET /api/agent/commands?format=text` | Same, as `<id> <base64 target>` per line, so a shell agent needs no JSON parser. |
| `GET /api/agent/mode` | The realtime mode (detect/prevent) the endpoint's group is set to. |
| `GET /agent/install.sh`, `/agent/install.ps1` | The generated installer, with the console URL and key substituted in. |
| `GET /agent/files/<name>` | An individual agent script (allow-listed, no traversal). |

Every agent request should carry `X-Agent-Clamav` with the local ClamAV version — that is how the
console learns each machine's signature level without a separate round trip.
