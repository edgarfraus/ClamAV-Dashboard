# Configuration

Configuration lives in **two places that do not overlap**. Knowing which is which saves a lot of
looking in the wrong file.

| | **Database** | **Properties file** |
|---|---|---|
| Where | `./data/` (H2) or PostgreSQL | `./conf/clamav-web-client.properties` |
| Holds | endpoints, groups, users, jobs, alerts, schedules, watch dirs, exclusions, audit | the application settings below |
| Edited from | the relevant Admin page | **Admin › Settings** |
| Survives a rebuild | yes (bind mount) | yes (bind mount) |

Neither is in git. A `git pull` never changes your settings, and moving an install to another
machine means copying both by hand.

## Settings

Everything here is editable at **Admin › Settings** and written atomically to the properties
file. The keys are the names on that page.

### Scan roots and uploads

| Key | Default | What it does |
|---|---|---|
| `app.allowedScanRoots` | `/scandir` | Comma-separated absolute paths. A path scan or watch directory is refused unless it sits under one of these. |
| `app.upload.maxBytes` | `2147483648` (2 GiB) | Total size accepted per upload request. |
| `app.concurrentScans` | `2` | Size of the scan thread pool. |
| `app.storage.uploadDir` | `./data/uploads` | Where uploaded files are stored before being streamed to `clamd`. |

`app.allowedScanRoots` is the **main guard against scanning arbitrary paths on the host**. Widen
it deliberately. To allow whole-filesystem scans on Linux, add `/` — and read
[security.md](security.md) first, because that also means anyone with the operator role can ask
`clamd` to read any file it can reach.

> [!IMPORTANT]
> **Changing `app.concurrentScans` requires a restart.** The thread pool is built once at startup.
> The new value is saved and shown, but the running pool keeps its old size until you restart the
> container — which is exactly the kind of setting that looks applied and is not.

### Quarantine

| Key | Default | What it does |
|---|---|---|
| `app.quarantine.enabled` | `false` | Move infected files out of the way when a scan finds one. |
| `app.storage.quarantineDir` | `./data/quarantine` | Where they go. |

The quarantine has to be on the same filesystem as the file it holds, which usually puts it
*inside* a directory that later gets scanned. The console and the agents both ignore findings
whose path is inside a quarantine directory — without that, every scan re-detects what the last
one quarantined, forever. See [scanning.md](scanning.md#quarantine).

### Notifications

| Key | Default | What it does |
|---|---|---|
| `app.webhook.enabled` | `false` | POST a JSON body on a detection. |
| `app.webhook.url` | *(empty)* | Where to POST it. |
| `app.telegram.enabled` | `false` | Send a Telegram message on a detection. |
| `app.telegram.botToken` | *(empty)* | Bot token from [@BotFather](https://t.me/botfather). |
| `app.telegram.chatId` | *(empty)* | Chat or channel id to send to. |

Notifications fire for **`VIRUS_FOUND` and `ERROR` only**, including results reported by agents.
A clean scan is silent by design — a scheduled scan whose job is to stay quiet should stay quiet.

> [!WARNING]
> The bot token is stored in plaintext in the properties file, because the console has to send it
> to Telegram on every alert. Treat `conf/` as secret: `chmod 600`, and keep it out of backups
> that others can read.

### Watch directories

| Key | Default | What it does |
|---|---|---|
| `app.watch.enabled` | `false` | Master switch for directory watching. |
| `app.watch.pollSeconds` | `30` | How often to re-walk the watched directories. |

Directories themselves are managed at **Admin › Watch dirs**. Watching walks each enabled
directory (depth 5, at most 1000 files per pass), remembers what it has already seen by path +
modification time + size, and queues a scan for anything new or changed. It is polling, not
inotify: a file written and deleted between two passes is never seen.

### Signature database reload

| Key | Default | What it does |
|---|---|---|
| `app.signatureReload.enabled` | `false` | Send `RELOAD` to every enabled endpoint on a schedule. |
| `app.signatureReload.cron` | `0 0 2 * * *` | Spring cron, **six fields**: second minute hour day-of-month month day-of-week. |

This tells a **remote `clamd`** to re-read a database that `freshclam` has already refreshed. It
is not an update: it cannot fetch anything, and it does nothing at all for an agent-managed
endpoint, which has no `clamd` for the console to talk to. Those machines keep themselves current
with their own scheduled `freshclam`. The console says so rather than silently reporting success.

## Legacy keys

`clamav.service.host`, `clamav.service.port` and `clamav.service.platform` appear in the file but
are **only** used once: to seed the first endpoint when the database is empty. After that,
endpoints live in the database and these are ignored. The `CLAMAV_HOST` / `CLAMAV_PORT`
environment variables in the compose file feed those defaults.

## Environment variables

| Variable | Used for |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Point at PostgreSQL instead of H2. |
| `CLAMAV_HOST` / `CLAMAV_PORT` | Defaults for the seeded first endpoint only. |
| `TZ` | The timezone used to group the dashboard charts by day. |

Anything else Spring Boot understands works too — `application.properties` inside the jar is
ordinary Spring configuration and can be overridden the usual ways.
