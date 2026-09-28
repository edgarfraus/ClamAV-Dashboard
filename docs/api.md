# REST API

Everything under `/api` uses **HTTP Basic** authentication and is exempt from CSRF, so it works
from scripts. The browser forms are a separate, CSRF-protected set of endpoints.

In the examples below, replace `HOST` and the credentials. Behind a reverse proxy, use the public
URL (for example `https://av.example.org`) instead of `http://HOST:8080`.

## At a glance

| Method | Path | Minimum role | Purpose |
|---|---|---|---|
| `GET` | [`/api/health`](#health) | `VIEWER` | Liveness plus the list of endpoint names |
| `GET` | [`/api/endpoints/{id}/status`](#endpoint-status) | `OPERATOR` | Online state and signature level of one endpoint |
| `GET` | [`/api/stats/timeseries`](#dashboard-statistics) | `VIEWER` | Jobs per day by verdict, for charts |
| `GET` | [`/api/jobs`](#jobs) | `OPERATOR` | The 200 most recent jobs |
| `GET` | [`/api/jobs/active`](#jobs) | `OPERATOR` | Jobs queued or running right now |
| `GET` | [`/api/jobs/{id}`](#jobs) | `OPERATOR` | One job |
| `POST` | [`/api/scan/upload`](#upload) | `OPERATOR` | Scan uploaded files |
| `POST` | [`/api/scan/path`](#path) | `OPERATOR` | Scan a path on an endpoint |
| `POST` | [`/api/scan/report`](#reporting-a-scan-run-elsewhere) | `AGENT` or `OPERATOR` | Push a result into the console |
| `GET` | [`/api/agent/commands`](#agent-endpoints) | `AGENT` | Poll for work; also the heartbeat |
| `GET` | [`/api/agent/mode`](#agent-endpoints) | `AGENT` | Desired realtime mode, read-only |
| `GET` | [`/agent/install.sh`, `/agent/install.ps1`](#agent-endpoints) | `AGENT` or `ADMIN` | Generated installer |
| `GET` | [`/agent/files/{name}`](#agent-endpoints) | `AGENT` or `ADMIN` | One agent script |
| `GET` | [`/actuator/**`](#actuator) | any authenticated user | Spring Boot health, info, metrics |

Roles are cumulative: an `ADMIN` can call everything an `OPERATOR` can, and an `OPERATOR`
everything a `VIEWER` can. `AGENT` is not a user role: it is what an
[enrollment key](agents.md#enrolling-a-machine) grants, and it opens only `/agent/**`,
`/api/agent/**` and `POST /api/scan/report`.

### Errors

Validation failures on `/api/scan/report` and the agent endpoints return a JSON body with a
readable reason, for example `{"error": "verdict must be OK, VIRUS_FOUND or ERROR"}`. Anything
else uses Spring's standard error body:

```json
{ "timestamp": "...", "status": 404, "error": "Not Found", "message": "job not found", "path": "/api/jobs/x" }
```

| Status | Meaning |
|---|---|
| `400` | Bad or missing parameter. Read `message` or `error`. |
| `401` | No credentials, or wrong ones. |
| `403` | Authenticated, but the role is not enough, or the agent key is invalid or its endpoint disabled. |
| `404` | Unknown job id. |
| `500` | Unknown **endpoint** id currently returns `500` rather than `404`. |

---

## Health

```bash
curl -sS -u admin:admin http://HOST:8080/api/health
```
```json
{ "status": "ok", "endpoints": ["default", "warehouse-nas"] }
```

It says the console is up and lists endpoint **names**. It does not contact any of them: use
[endpoint status](#endpoint-status) for that.

## Endpoint status

```bash
curl -sS -u admin:admin http://HOST:8080/api/endpoints/33/status
```

The numeric id is the one in the Endpoints page URLs and in `data-ep-id` on its table rows.

For a **direct `clamd`** endpoint the console connects, pings with a 5-second timeout, and parses
the version:

```json
{ "online": true, "clamVersion": "1.5.4", "dbVersion": "28129",
  "dbDate": "Tue Sep 22 06:24:12 2026", "dbAgeDays": 2, "stale": false }
```

When it is down, `error` carries a diagnosis rather than a generic failure: `Connection refused`
(nothing listening, **not** a firewall), `No answer` or `Timed out` (packets dropped, which is a
firewall or a wrong address), `Unknown host`, `No route`.

```json
{ "online": false,
  "error": "Connection refused on 10.0.0.5:3310: the machine answers but nothing is listening on that port (this is not the firewall). ..." }
```

For an **agent-managed** endpoint it contacts nothing. `online` means the agent checked in within
the last **15 minutes**, and the version is the one the agent attached to its last request:

```json
{ "online": true, "agent": true, "lastSeen": "2026-09-28T21:09:38Z",
  "clamVersion": "1.5.4", "dbVersion": "28137", "dbDate": "Mon Sep 28 08:24:12 2026",
  "dbAgeDays": 0, "stale": false }
```

When it is not, `error` says `Agent never seen: install the agent on this machine` or
`Agent silent since <timestamp>`.

| Field | Notes |
|---|---|
| `online` | The one field to alert on. |
| `agent` | Present and `true` only for agent-managed endpoints. |
| `lastSeen` | ISO-8601 UTC. Empty if the agent never checked in. |
| `dbVersion` | Empty means **no signature database**: every scan on that machine reports clean. See [troubleshooting](troubleshooting.md#a-machine-with-no-signature-database-reports-everything-clean). |
| `stale` | `true` when the signatures are old enough to worry about. |

## Dashboard statistics

```bash
curl -sS -u admin:admin 'http://HOST:8080/api/stats/timeseries?days=14'
```
```json
{ "labels": ["2026-09-15", "..."],
  "total": [0, 12, 0, 340],  "ok": [...], "virus": [...], "error": [...],
  "endpoints": [0, 1, 0, 2],
  "lastScanAt": "2026-09-28T21:34:10Z" }
```

`days` is clamped to 1–90 (default 14). Days are grouped in the server's time zone. **Days with no
scans are included as zeros**: a series that skipped them would draw a quiet week and a busy week
identically. `endpoints` counts distinct endpoints scanned per day. `lastScanAt` lets a caller
tell "no scans in this window" apart from "no scans ever".

## Jobs

```bash
curl -sS -u admin:admin http://HOST:8080/api/jobs          # 200 most recent, newest first
curl -sS -u admin:admin http://HOST:8080/api/jobs/active   # queued or running right now
curl -sS -u admin:admin http://HOST:8080/api/jobs/<jobId>  # one job, 404 if unknown
```

`/api/jobs` and `/api/jobs/{id}` return the full job:

| Field | Notes |
|---|---|
| `id` | 32 hex characters. |
| `type` | `UPLOAD`, `PATH`, `WATCH`, `AGENT` (dispatched to an agent), `EXTERNAL` (reported batch scan), `REALTIME` (reported on-access event). |
| `status` | `QUEUED` → `RUNNING` → `FINISHED`. |
| `verdict` | `OK`, `VIRUS_FOUND`, `ERROR`, `SKIPPED`; null until finished. |
| `target` | The path, the uploaded file name, or the reporting host when a report carried no path. |
| `endpoint` | The endpoint object (name, host, port, group, agent last-seen, version and OS), or null if it was deleted since. The agent key is never included. |
| `sourceHost` | The `hostname` a report came from. |
| `storedPath`, `sha256`, `sizeBytes` | Upload jobs only. |
| `foundVirusesJson` | `{"<path>": ["<signature>", ...]}` as a JSON string. |
| `errorMessage` | For `ERROR`. |
| `remediationStatus` | `QUARANTINED`, `REMOVED`, `FAILED`, `NOT_ATTEMPTED`. |
| `quarantinePath` | Where the file ended up. |
| `acknowledged`, `acknowledgedBy`, `acknowledgedAt` | Whether someone dismissed the alert. |
| `submittedBy`, `submittedAt`, `startedAt`, `finishedAt` | Who and when. `submittedBy` is `agent:<endpoint>` for agent reports. Timestamps are ISO-8601 UTC. |

`/api/jobs/active` returns a lighter list, suited to polling:

```json
[ { "id": "3f2a...", "idShort": "3f2a1b9c", "type": "AGENT", "status": "RUNNING",
    "target": "C:\\Users", "endpointName": "W11", "submittedAt": "2026-09-28T21:10:02Z" } ]
```

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
```json
{ "created": 2, "jobIds": ["3f2a...", "91cc..."] }
```

`Required part 'files' is not present` means you sent `file`. `Required parameter 'endpointId' is
not present` means you sent the endpoint's name.

The console streams each file to `clamd` over TCP (INSTREAM), so the endpoint needs a reachable
host and port. An agent-only endpoint cannot take uploads.

### Path

```bash
curl -u admin:admin -H 'Content-Type: application/json' \
  -d '{"path":"/scandir/file","endpointId":33}' \
  http://HOST:8080/api/scan/path
```
```json
{ "jobId": "3f2a..." }
```

`endpointId` is optional; without it the default endpoint is used.

- For a **direct `clamd`** endpoint the path must be under the configured allowed roots, or the
  request is refused, and it must be readable by that `clamd`.
- For an **agent-managed** endpoint the scan is queued for the agent instead: the job appears as
  type `AGENT`, status `QUEUED`, and starts when the agent next polls (within 5 minutes). The path
  is a path **on that machine**, such as `C:\Users` or `/home`.

Poll `/api/jobs/{jobId}` until `status` is `FINISHED`.

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
```json
{ "jobId": "3f2a..." }
```

| Field | Notes |
|---|---|
| `hostname` | **Required.** Also the fallback target when `path` is absent — a spontaneous report may have no path, and the job's target column cannot be null. |
| `path` | What was scanned. |
| `verdict` | **Required.** `VIRUS_FOUND`, `ERROR`, or `OK` **only together with `commandId`** (see below). |
| `findings` | Raw `path: SIGNATURE FOUND` lines — exactly `clamdscan`'s and `clamonacc`'s own output format. |
| `errorMessage` | For `ERROR`. |
| `source` | `realtime` marks an on-access event (job type `REALTIME`); anything else is a batch scan (`EXTERNAL`). |
| `commandId` | Set when this answers a scan the console asked for. The existing `AGENT` job is finished instead of a new one being created. |
| `remediation` | `quarantined`, `removed`, `failed`; anything else means not attempted. |
| `remediationPath` | Where the file ended up, for `quarantined`. |

Authenticated with an agent key, the job is bound to that key's endpoint. With OPERATOR
credentials it is attached to no endpoint and shows under its `hostname`.

> [!NOTE]
> **`OK` is rejected without a `commandId`.** For a scan a user launched, "nothing found" is the
> answer they are waiting for. For spontaneous reports — cron, on-access — accepting `OK` would
> fill the job list with clean runs and bury the ones that matter.

A `commandId` is accepted only from the agent of the endpoint it was queued for; any other gets
`400 unknown commandId for this agent`.

## Agent endpoints

Used by the agents, and authenticated with an enrollment key rather than a password. The key is
accepted, in order of preference, as:

1. `X-Agent-Key: cav_...`
2. `Authorization: Bearer cav_...`
3. `?key=cav_...` — only because `curl … | sudo bash` and `irm … | iex` cannot send headers. The
   key then lands in proxy and shell history logs, so use it to download the installer and
   nothing else. If one leaks, **Rotate** it under Admin › Endpoints and reinstall the agent.

| Endpoint | Purpose |
|---|---|
| `GET /api/agent/commands` | Fetch pending scan commands. **Fetching claims them.** This request is also the heartbeat. |
| `GET /api/agent/commands?format=text` | Same, as `<id> <base64 target>` per line, so a shell agent needs no JSON parser. |
| `GET /api/agent/mode` | The realtime mode (detect/prevent) the endpoint's group is set to, without claiming anything. |
| `GET /agent/install.sh`, `/agent/install.ps1` | The generated installer, with the console URL and key substituted in. |
| `GET /agent/files/<name>` | An individual agent script (allow-listed, no traversal). |

### Headers every agent request carries

The console learns each machine's state from these, on every request, so there is no separate
heartbeat round trip.

| Header | Example | Used for |
|---|---|---|
| `X-Agent-Clamav` | `ClamAV 1.5.4/28137/Mon Sep 28 08:24:12 2026` | Engine and signature version shown on Endpoints. |
| `X-Agent-OS` | `linux`, `macos`, `windows` | Default directories for a full-disk scan. |
| `X-Agent-OnAccess-Mode` | `detect`, `prevent`, `unsupported` | The realtime mode actually applied on the machine. |

### `GET /api/agent/commands`

```bash
curl -sS -H 'X-Agent-Key: cav_...' -H 'X-Agent-Clamav: ClamAV 1.5.4/28137/...' \
  https://HOST/api/agent/commands
```
```json
{ "endpoint": "W11",
  "commands": [ { "id": 42, "target": "C:\\Users\nC:\\ProgramData" } ],
  "onAccessMode": "detect" }
```

- `target` holds one path per line; a full-disk scan sends every directory in one command.
- Returning a command claims it: the command becomes `DISPATCHED` and its job `RUNNING`. The
  agent must answer with `POST /api/scan/report` and `commandId`.
- A command claimed and never answered is failed after **6 hours**; one never claimed, after
  **24 hours**.
- `onAccessMode` is present only when the endpoint belongs to a group.

The text form prints an optional `MODE detect|prevent` line first, then one line per command:

```text
MODE detect
42 QzpcVXNlcnMKQzpcUHJvZ3JhbURhdGE=
```

An invalid key or a disabled endpoint gets `403`.

### `GET /agent/install.ps1` and `/agent/install.sh`

```powershell
irm 'https://HOST/agent/install.ps1?key=cav_...' | iex          # Windows, as Administrator
```
```bash
curl -fsSL 'https://HOST/agent/install.sh?key=cav_...' | sudo bash   # Linux / macOS
```

Without a valid key (or an ADMIN session plus `?key=`) the answer is `400 Endpoint not identified`.
The console URL baked into the installer is the one the request arrived on, so behind a proxy it
needs `X-Forwarded-Proto` and `X-Forwarded-Host` (see [installation](installation.md)).

## Actuator

Spring Boot's actuator is exposed for monitoring, for **any authenticated user**:

```bash
curl -sS -u admin:admin http://HOST:8080/actuator/health
curl -sS -u admin:admin http://HOST:8080/actuator/metrics/jvm.memory.used
```

`/actuator/health` reports the database and disk space. `/actuator/info` and `/actuator/metrics`
are also available. Anonymous requests get `401`. `prometheus` is listed in the exposure setting
but not active: the Micrometer Prometheus registry is not bundled.

## A monitoring script

Alert when any endpoint is offline or its signatures are missing:

```bash
#!/bin/sh
# usage: check.sh https://av.example.org user:password 1 5 258
base=$1; auth=$2; shift 2
for id in "$@"; do
  s=$(curl -fsS -u "$auth" "$base/api/endpoints/$id/status") || { echo "endpoint $id: request failed"; continue; }
  echo "$s" | grep -q '"online":true'   || echo "endpoint $id: OFFLINE $s"
  echo "$s" | grep -q '"dbVersion":""'  && echo "endpoint $id: NO SIGNATURES"
done
```
