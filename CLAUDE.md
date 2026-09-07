# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot 3.3.5 / Java 17 web application (`info.trizub.clamav:clamav-web-client`) that is a web UI and **job tracker** in front of one or more `clamd` (ClamAV daemon) servers. It accepts scans (file upload or filesystem path), dispatches them to a clamd endpoint via the `xyz.capybara:clamav-client` library, persists every scan as a **job**, and adds RBAC, directory watching, quarantine, webhook notifications, and an audit log on top.

Key mental model: this app only tracks scans initiated **through it** (UI or `/api`). Scans run directly against clamd (e.g. `clamdscan`) never appear here.

## Build / run / test

```bash
mvn clean package              # build the jar (target/clamav-web-client-<version>.jar), runs tests
mvn spring-boot:run            # run locally on http://localhost:8080
mvn test                       # run all tests
mvn -Dtest=SomeClassName test  # run a single test class
mvn -DskipTests package        # build without tests

./build_docker.sh              # mvn package + docker build (tags rguziy/clamav-web-client:latest)
docker compose up --build      # bring up clamav-server + clamav-web-client together
```

Notes:
- Local build targets **JDK 17** (`pom.xml`); the Docker multi-stage build uses **JDK 21** (Maven build stage + `eclipse-temurin:21-jre` runtime). Either JDK 17+ works locally.
- There are **no unit tests** in the tree yet (only the `spring-boot-starter-test` dependency); `mvn test` is effectively a no-op today.
- First run auto-creates a default admin user **`admin` / `admin`** — change it via `/admin/users`.

## Configuration: two separate stores

This is the most important architectural nuance. Configuration lives in **two places that do not overlap**:

1. **JPA database** (`spring.datasource.*` in `application.properties`) — holds `ScanJob`, `AppUser`, `ClamdEndpoint`, `WatchedDirectory`, `ProcessedFile`, `AuditEvent`. Defaults to **H2 file** at `./data/clamav-web-client`. Override to PostgreSQL via `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` env vars (the postgres driver is bundled).
2. **A plain properties file** `conf/clamav-web-client.properties` — holds *admin settings* (allowed scan roots, upload limits, concurrency, quarantine/webhook/watch toggles). Managed by `SettingsService` via `AtomicPropertiesFile` (atomic write), **not** JPA. This file is bind-mounted `rw` in Docker so settings survive restarts. `application.properties` (in the jar) is Spring boilerplate; runtime-tunable settings live in the external properties file.

When adding a new admin-tunable setting: add the key + default in `SettingsService.init()`, a typed getter there, add it to the allow-list in `WebUiController.adminSettingsSave()`, and surface it in `templates/pages/settings.html`.

### clamd endpoints: DB-backed, not config
The clamd servers the app talks to are `ClamdEndpoint` **entities in the DB** (managed under `/admin/endpoints`), each with host/port/`Platform` (UNIX/JNA). `ClamavClientProvider.clientFor(endpoint)` builds a fresh `ClamavClient` per call. The `clamav.service.*` keys in the properties file are **legacy** — used only by `StartupInitializer.ensureDefaultEndpoint()` to seed the first endpoint on an empty DB.

## Scan job lifecycle

The core flow spans several classes — read them together:

1. **Create** (`ScanJobService`): `createUploadJobs` (stores file to `uploadDir`, computes SHA-256), `createPathJob` (validates against allowed roots via `PathPolicy`), or `createWatchFileJob`. Job is saved as `QUEUED`.
2. **Enqueue** (`ScanJobService.enqueueAfterCommit`): registers a `TransactionSynchronization` so the job is only handed to the executor **after the DB commit** — avoids the worker reading a not-yet-persisted job.
3. **Execute** (`ScanExecutionService`): a fixed `ThreadPoolExecutor` sized by `settings.concurrentScans()`, built once in `@PostConstruct` (so **changing concurrency needs an app restart**). Threads are deliberately **non-daemon** (see comment — container lifecycle can kill daemon threads mid-scan). UPLOAD jobs stream the stored file (`client.scan(InputStream)` → INSTREAM); PATH/WATCH jobs use `client.parallelScan(path)`.
4. **Result** (`ScanExecutionService.handleResult`): sets verdict OK / VIRUS_FOUND / ERROR. On virus: quarantine (`QuarantineService`) + webhook (`NotificationService.notifyIfNeeded`).
5. **Restart recovery** (`ScanJobService.resumeQueued` `@PostConstruct`): any non-`FINISHED` job is reset to `QUEUED` and re-enqueued on startup.

Status enum: `QUEUED → RUNNING → FINISHED`. Verdict enum: `OK | VIRUS_FOUND | ERROR | SKIPPED`. Type enum: `UPLOAD | PATH | WATCH | EXTERNAL | REALTIME` (the last two are created already-FINISHED by `/api/scan/report`, never enqueued). Note `finish*`/`markRunning` helpers are duplicated in both `ScanJobService` and `ScanExecutionService`; the executor uses its own copies.

## Path safety

Any path-based scan or watch dir must pass `PathPolicy.isUnderAllowedRoots()` against `settings.allowedRoots()` (comma-separated, normalized to absolute). This is the primary guard against scanning arbitrary host paths — preserve it when touching path handling. In Docker, allowed roots must also be volume-mounted into the web-client container or the path won't be visible.

## Watching

`WatcherService.poll()` is `@Scheduled(fixedDelay=30s)` but self-throttles to `settings.watchPollSeconds()` and no-ops unless `watchEnabled()`. It walks each enabled `WatchedDirectory` (depth 5, max 1000 files), dedupes via `ProcessedFile` (path + lastModified + size), and queues a WATCH job for new/changed files. `start()/stop()` only reset in-process timing — they do **not** flip the persisted setting.

## Web layer

- `WebUiController` (`@Controller`) — Thymeleaf pages, one template per tab under `src/main/resources/templates/pages/` (`dashboard`, `main`, `scan`, `jobs`, `job`, `settings`, `endpoints`, `users`, `watch`, `audit`). Every admin mutation calls `audit.record(...)`.
- `ApiController` (`@RestController`, `/api`) — programmatic scan/query. Upload expects multipart fields **`files`** (repeatable) and **`endpointId`** (numeric); path scan is JSON `{path, endpointId}`.
- `ClamAVWebClientController` / `ClamAVWebClientService` are **legacy** carryovers from the upstream `rguziy/clamav-web-client` project; the active paths are the two controllers above.
- `UiModelAdvice` / `WebUiController.addCommonModelAttributes` inject non-null `settings` + health flags into every model so fragments never NPE.

## Security (`SecurityConfig`)

BCrypt, DB-backed auth (`DbUserDetailsService`), form login + HTTP Basic (Basic is what `/api/**` uses). Three cumulative roles — a user is granted all roles up to their level (see `WebUiController.adminUsersCreate`):
- `VIEWER` — dashboard/settings/main read-only + `/api/health`
- `OPERATOR` — scan + jobs + `/api/**`
- `ADMIN` — `/admin/**` and the H2 console at `/h2`

CSRF is disabled for `/api/**` and `/h2/**`. The **H2 console is enabled** (`/h2`) — note this if hardening for production.

## Postgres LOB migration

`PostgresLobMigration` runs on `ApplicationReadyEvent`, only on Postgres. Older schemas created `@Lob String` columns as OID large objects, which break in autocommit mode. It converts `scan_jobs.found_viruses_json` / `error_message` from OID to `TEXT` in-place. It logs and continues on failure rather than crashing startup. Harmless no-op on H2 or already-migrated schemas.

## REST API quick reference

All `/api/**` endpoints require HTTP Basic auth (`OPERATOR`+; `/api/health` allows `VIEWER`+). Multipart field names and types matter — the common curl errors below are caused by getting them wrong.

```bash
# List recent jobs (top 200, newest first)
curl -sS -u admin:admin http://HOST:8080/api/jobs

# Single job by id
curl -sS -u admin:admin http://HOST:8080/api/jobs/<jobId>

# Health (endpoint names)
curl -sS -u admin:admin http://HOST:8080/api/health

# Upload scan — fields MUST be `files` (repeatable) and `endpointId` (numeric)
curl -u admin:admin \
  -F 'endpointId=33' \
  -F 'files=@/path/a.txt' -F 'files=@/path/b.txt' \
  http://HOST:8080/api/scan/upload

# Path scan — JSON body; path must be under allowed roots
curl -u admin:admin -H 'Content-Type: application/json' \
  -d '{"path":"/scandir/file","endpointId":33}' \
  http://HOST:8080/api/scan/path
```

The equivalent UI form endpoints (`POST /scan/upload`, `POST /scan/path`) are CSRF-protected and used by the browser; the `/api/*` variants are CSRF-exempt for programmatic use.

## Gotchas

- **Concurrency change needs a restart** — the executor thread pool is built once in `ScanExecutionService.@PostConstruct` from `app.concurrentScans`. Editing it in Settings persists but doesn't resize the live pool.
- **`files` vs `file`, `endpointId` vs name** — upload requires the plural `files` part and a numeric `endpointId`; wrong names give `Required part 'files' is not present` / `Required parameter 'endpointId' is not present`.
- **Path/watch invisibility in Docker** — if the scan root isn't bind-mounted into the *web-client* container, `PathPolicy` may still pass but the file isn't there to scan.
- **INSTREAM loses filenames** — upload scans stream bytes, so clamd logs `instream(...): OK/FOUND` without the original name. Use path scans if server-side filenames in clamd logs matter.
- **Duplicated finish/mark helpers** — `finishOk/finishFound/finishError/markRunning` exist in both `ScanJobService` and `ScanExecutionService`; the executor path uses its own. Update the right copy.
- **A custom security filter must also run on the error dispatch.** `OncePerRequestFilter.shouldNotFilterErrorDispatch()` defaults to `true`, so when a request from an agent fails, the forward to `/error` runs without the agent's `Authentication` and the client gets `401 WWW-Authenticate: Basic` instead of the real 400/500. `AgentAuthenticationFilter` overrides it to `false`; without that, every agent-side error is unreadable.
- **`scan_jobs.target` is NOT NULL.** A spontaneous report (on-access, cron) may carry no `path`, so `createExternalReport` falls back to the hostname. Passing it through raw throws `DataIntegrityViolationException` at insert time.
- **No `&apos;` inside a Thymeleaf expression.** The HTML parser decodes entities *before* Thymeleaf parses the attribute, so `th:title="... 'dall&apos;agent' ..."` reaches SpringEL as a bare apostrophe closing the string literal early: `Could not parse as expression`, at render time only. Either double it (`''`, the SpringEL escape) or word the text without apostrophes — the second is harder to get wrong.
- **`ddl-auto=update` adds columns but never touches existing constraints.** On a database created before a change, this bites twice and both failures are invisible on a fresh DB, so they only appear in real deployments: a column that became nullable in the entity stays `NOT NULL` (creating an agent-managed endpoint with an empty host failed with `NULL not allowed for column HOST`), and Hibernate's generated `CHECK` constraint for an `@Enumerated(STRING)` column still lists only the *old* enum values (inserting a job of the new type `AGENT` failed with `Value not permitted for column TYPE`). `SchemaFixup` migrates both on `ApplicationReadyEvent`; altering a column's data type is what drops the stale check constraint. **Add a `SchemaFixup` step whenever you relax a constraint or add an enum value**, and test against a copy of a real database — not just an empty one.
- **The audit write must never break the operation it records.** `audit_events.details` is `VARCHAR(1024)`; recording a long failure message overflowed it, and that second failure masked the first (a handled error surfaced as an unrelated 500). `AuditService.record` truncates.
- **New DB entities need no manual DDL** — `spring.jpa.hibernate.ddl-auto=update` auto-creates/updates tables. Adding a `@Lob String` on Postgres can resurrect the OID problem `PostgresLobMigration` fixes; prefer `columnDefinition = "text"`.

## Deployment

`docker-compose.yml` runs two containers: `clamav` (the clamd server, port 3310) and `clamav-web-client` (this app, port 8080, `build: .`). PATH/WATCH scans require the scan roots to be mounted into the web-client container. `install-clamd-remote.sh` provisions clamd on a remote host.

## Direction of the connection

With an agent installed the direction is **inverted**: the machine calls the console, the console never calls the machine. Consequences, all of them already reflected in the code:

- `ClamdEndpoint.host` is **nullable**. For an agent-managed endpoint there is nothing to dial, so host/port stay empty and the Endpoints form does not require them.
- The agent's installer binds clamd to `127.0.0.1` and opens **no** firewall port. clamd is reached only by local `clamdscan`/`clamonacc`.
- `ApiController.endpointStatus` does not ping an agent endpoint. Status comes from `agentLastSeenAt` (alive within `AGENT_ALIVE_WINDOW`, 15 min — the Windows agent polls every 5), and the signature version from `agentClamdVersion`, which the agent attaches as the `X-Agent-Clamav` header on **every** request. `AgentAuthenticationFilter` stores both in one write, so there is no separate heartbeat round trip.
- `SignatureReloadService.sendReload` skips endpoints with no host: freshclam on the machine keeps signatures current there.
- The `/main` page reports the endpoint as agent-managed instead of failing to connect.

What still uses a direct TCP connection, and therefore still needs host/port: **upload scans** (the console streams the file to a clamd via INSTREAM), **watch** jobs (same), and any endpoint with no agent. A console with only agent endpoints can still run upload scans by keeping one reachable clamd — typically the one in `docker-compose.yml`.

## Agent enrollment (per-endpoint keys)

Each `ClamdEndpoint` can hold an **agent key** (`agentKey`, generated under Admin > Endpoints > the robot button). It replaces the per-machine OPERATOR user: `AgentAuthenticationFilter` maps the key to `ROLE_AGENT`, which `SecurityConfig` allows **only** on `/agent/**` and `POST /api/scan/report` — a stolen key cannot read other hosts' jobs or launch scans, which an OPERATOR account could. The key is accepted as `X-Agent-Key`, `Authorization: Bearer`, or `?key=` (the query form exists for `curl … | sudo bash`, so it lands in proxy logs — it is fine for installer download, not a reason to prefer it).

The key is stored **in plaintext** so the console can re-generate an installer for an existing endpoint at any time; it is only reachable under `/admin/**`, and "Rotate" invalidates the old one. Reports authenticated by a key are bound to that endpoint, so they stop being orphan hosts.

`AgentInstallController` serves the generated installers, substituting `@@CONSOLE_URL@@` / `@@AGENT_KEY@@` / `@@ENDPOINT_NAME@@` into `resources/agent/install.sh.tpl` and `install.ps1.tpl`. The `.sh` installer is **self-contained**: `@@EMBED_*@@` placeholders are replaced with the full text of the agent scripts, written out at run time from quoted heredocs (`__CLAIMAV_EMBED_*__` delimiters; the controller refuses to render if a script contains the delimiter). Download it, run it, done — a bootstrap that fetches more pieces breaks whenever the machine cannot reach the console mid-install. The scripts live at the repo root and are copied into the jar by a `maven-resources-plugin` execution (`copy-agent-scripts`) — one canonical copy, not two. `/agent/files/<name>` still serves them individually (allow-list, no traversal).

Both installers **ask what to install** (realtime / console-dispatched scans / scheduled scan) when they have a terminal, and install everything when they do not — `curl … | bash` has the pipe on stdin, so there is nothing to read answers from. Flags (`--all`, `--realtime`, `--central`, `--scheduled`; `-All`, `-Central`, `-Scheduled` on Windows) skip the prompts.

**The Dockerfile must copy the root-level agent scripts.** They are packaged into the jar by `maven-resources-plugin` reading `${basedir}`, so a build context without them (the Dockerfile used to copy only `pom.xml` and `src`) still succeeds — an `<include>` that matches nothing is not an error — but produces a jar with no `agent/*.sh`, and the console can no longer generate the agent installer. `mvn package` on the host works, so the failure only shows up in Docker, which is how this project actually runs. `AgentInstallController.verifyPackagedScripts()` logs an explicit error at startup if any of them is missing, rather than leaving it to be discovered from a failed download.

**`AgentInstallController` must stay out of the `web` package.** `GlobalExceptionHandler` is a `@ControllerAdvice(basePackages = "…webclient.web")` whose `@ExceptionHandler(Exception.class)` returns `redirect:/dashboard`; a download controller under `web` answers every failure with a silent redirect instead of the file, which is exactly what "the installer won't download" looks like.

**On-access cannot work inside an unprivileged container.** `fanotify_init()` needs `CAP_SYS_ADMIN` in the *initial* user namespace, which an unprivileged LXC or Docker container never has however root-like the process looks; clamonacc exits with `fanotify_init failed: Operation not permitted`. The installer detects the container up front (`systemd-detect-virt --container`), says so, and — when the service still fails — explains that specific cause instead of a generic "check your kernel". Console-driven and scheduled scans are unaffected and work fine in a container. The remedies are a privileged LXC (`unprivileged=0`) or running the agent on the host.

**Agent scripts must never hide curl's error.** `-s` plus `2>/dev/null` turns every connectivity problem into an indistinguishable "failed", which is precisely what made these installs impossible to debug. `clamav-onacc-report.sh --test` (installed at `/usr/local/bin/` regardless of which components were chosen) prints the curl exit code, the HTTP status and curl's own message, then names the cause: DNS, connection refused, timeout, TLS, rejected key, 404 through the proxy, or a 5xx from the proxy. For a console behind a reverse proxy with a private CA, set `DASHBOARD_CA_BUNDLE` in `/etc/clamav/console-report.conf` (`DASHBOARD_INSECURE=1` exists to confirm the diagnosis, not to be left on).

**The closing summary reports measured state, not intent.** It reads `systemctl is-active` for each unit, so a service that failed to start is shown as not running. An installer that prints "realtime protection active" over a dead clamonacc hides a real gap in coverage.

Coverage differs by OS, and this is a ClamAV limit, not a missing feature:

| OS | clamd | Realtime (on-access) | Scheduled scan + report |
|---|---|---|---|
| Linux | yes | **yes** (`clamonacc`, fanotify) | yes |
| macOS | via Homebrew | **no** — fanotify is Linux-only | yes (LaunchDaemon) |
| Windows | manual install | **no** | yes (Scheduled Task) |

`server.forward-headers-strategy=framework` is set so the console URL baked into a generated installer is the public one when running behind a reverse proxy (the proxy must send `X-Forwarded-Proto`/`-Host`).

## Agent command queue (console-dispatched scans)

This is what makes "Scan" in the UI work for a machine whose clamd cannot read the target: **the agent scans locally**, so there is no TCP, no root/SELinux requirement, and no "path exists here but not on the clamd host".

Flow, spanning `AgentCommandService` / `AgentApiController` / `ApiController.scanReport`:

1. `WebUiController.scanPath` / `scanFullDisk` — when `endpoint.isAgentEnrolled()`, they call `agentCommands.enqueue(...)` instead of the direct clamd path. Full-disk sends **one** command with every target (clamdscan takes several paths at once) rather than one job per directory.
2. `enqueue` creates a `ScanJob` of type **`AGENT`**, status QUEUED, and links it to the `AgentCommand`. The job is visible in Jobs immediately, before the agent has seen it.
3. The agent polls `GET /api/agent/commands` (`?format=text` returns `<id> <base64 target>` per line, so the bash agent needs no `jq`). Returning a command **claims** it: status DISPATCHED, job RUNNING.
4. The agent scans and POSTs to `/api/scan/report` with `commandId`; the existing job is finished (OK / VIRUS_FOUND / ERROR) instead of a new one being created, and notifications fire.

Two rules worth knowing before touching this:

- **`ScanJobService.resumeQueued()` skips type `AGENT`.** It re-enqueues unfinished jobs into the local executor on startup; an agent job sent there would be re-run over TCP — exactly what the agent exists to avoid.
- **`OK` is accepted by `/api/scan/report` only together with a `commandId`.** For a scan the user launched, "nothing found" is the answer they are waiting for; for spontaneous reports (cron, on-access) accepting OK would fill Jobs with clean runs.

`AgentCommandService.expireStale()` (`@Scheduled`, every 5 min) closes commands that were claimed but never answered (6h) or never claimed at all (24h), failing their job with a readable reason — so an agent that is off does not leave jobs stuck in RUNNING.

Agent side: `clamav-agent-poll.sh --loop` (systemd `clamav-agent-poll.service` on Linux, a LaunchDaemon on macOS, a 5-minute Scheduled Task on Windows). It prefers `clamdscan --fdpass` and falls back to `clamscan` when clamd is not answering.

## Reported scans: batch and realtime

Two scripts let a machine push results *into* the console instead of the console reaching out to clamd. Both POST to `/api/scan/report` (OPERATOR Basic auth) and create an already-FINISHED job, so Telegram/webhook fire immediately:

- `clamav-telegram-alert.sh` — cron-style batch `clamdscan`; job type `EXTERNAL`.
- `clamav-onacc-report.sh` — follows the `clamonacc` log and forwards each detection as it happens; job type `REALTIME` (payload field `source: "realtime"`). Installed and wired up by `install-clamd-remote.sh --on-access`.

**Why the realtime path parses a log instead of using clamd's `VirusEvent`:** `VirusEvent` does not fire for on-access scans — it is deliberately disabled in ClamAV since 0.100 (`virusaction()` is commented out in `onaccess_fan.c` over fork/deadlock concerns) and also does not fire for `clamdscan --fdpass`. The only place the **real path** of the infected file appears is clamonacc's own output, which logs `<path>: <SIG> FOUND` (`clamonacc/client/protocol.c`, for scantype `>= STREAM`, i.e. both `--fdpass` and `--stream`). That line format is exactly what the `findings` field expects.

On-access needs `clamonacc` running as root (fanotify wants CAP_SYS_ADMIN) and `OnAccessIncludePath` set to real directories — **never `/`**, which loops. Traffic is outbound-only, so no port has to be opened on the scanned machine.

## Project context & working notes

Durable context that isn't obvious from the code (kept here so it travels with the repo across machines):

- **Direction:** this is headed toward a **managed, lightweight fleet AV**. Target architecture is **model B — an agent per machine**: a local agent talks to a local clamd over a UNIX socket (fd-pass works, so clamd need not run as root nor be network-exposed) and reports to the console over **HTTPS + mTLS (private CA) + per-agent tokens**. Today's model is the reverse: the console reaches OUT to each clamd over plaintext TCP.
- **clamd over TCP constraint:** fd-pass is impossible over TCP (SCM_RIGHTS needs a local UNIX socket), so for PATH scans clamd opens the files itself. To scan a whole machine it must run as **root** + SELinux `antivirus_can_scan_system=on` — this is what `install-clamd-remote.sh --scan-system` sets up. Always restrict the clamd port (`--console-ip` / firewall).
- **Machines:** dev = a Mac (Docker only, no local Java/Maven; rebuild via `docker compose up --build -d`). "prod" = a small ARM board, deployed via `git pull` + compose — **not public / not a real prod**. Test clamd endpoint = a Fedora box, **powered off between test sessions**.
- **Runtime files are gitignored** so they stop blocking `git pull`: `data/*.db` (H2 DB — holds endpoints/users/jobs) and `conf/clamav-web-client.properties` (app settings; recreated with defaults on first run). They do **not** travel with git — copy them by hand if you need the same config/state on another machine.
- **Git workflow:** commit **directly on `main`** and push; do not create feature branches for this repo unless asked.
