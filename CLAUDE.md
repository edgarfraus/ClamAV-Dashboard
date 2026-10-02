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
- First run auto-creates a default admin user **`admin` / `admin`**, flagged `mustChangePassword`: `PasswordChangeRequiredFilter` holds it on `/account/password` (and answers `403` on `/api`, `/actuator`, `/h2`) until a new password is set. `UserService.flagDefaultPassword()` also flags, at every startup, an existing `admin` still on that password — consoles installed before the flag existed are covered, and a deployment whose scripts use `admin:admin` will start getting `403` until the password is changed.

## Configuration: two separate stores

This is the most important architectural nuance. Configuration lives in **two places that do not overlap**:

1. **JPA database** (`spring.datasource.*` in `application.properties`) — holds `ScanJob`, `AppUser`, `ClamdEndpoint`, `WatchedDirectory`, `ProcessedFile`, `AuditEvent`. Defaults to **H2 file** at `./data/clamav-web-client`. Override to PostgreSQL via `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` env vars (the postgres driver is bundled).
2. **A plain properties file** `conf/clamav-web-client.properties` — holds *admin settings* (allowed scan roots, upload limits, concurrency, quarantine/webhook/watch toggles). Managed by `SettingsService` via `AtomicPropertiesFile` (atomic write), **not** JPA. This file is bind-mounted `rw` in Docker so settings survive restarts. `application.properties` (in the jar) is Spring boilerplate; runtime-tunable settings live in the external properties file.

When adding a new admin-tunable setting: add the key + default in `SettingsService.init()`, a typed getter there, add it to the allow-list in `WebUiController.adminSettingsSave()`, and surface it in `templates/pages/settings.html`.

### clamd endpoints: DB-backed, not config
The clamd servers the app talks to are `ClamdEndpoint` **entities in the DB** (managed under `/admin/endpoints`), each with host/port/`Platform` (UNIX/JNA). `ClamavClientProvider.clientFor(endpoint)` builds a fresh `ClamavClient` per call. The `clamav.service.*` keys in the properties file are **legacy** — used only by `StartupInitializer.ensureDefaultEndpoint()` to seed the first endpoint on an empty DB.

## Deleting an endpoint

Five entities carry an `endpoint_id`: `ScanJob`, `WatchedDirectory`, `ScanExclusion`, `ScheduledScan`, `AgentCommand`. A bare `deleteById` therefore fails on the foreign key as soon as the endpoint has ever been used, and because `GlobalExceptionHandler` turns that into a redirect, the row just silently refuses to disappear. `EndpointService.delete` detaches or removes each dependent first, and the choice per entity is deliberate:

- **scan jobs** keep their history and only lose the link; a job still QUEUED/RUNNING is closed as ERROR, since without an endpoint the executor has nothing to dial.
- **watched directories, scheduled scans, agent commands** are configuration or work items tied to that endpoint — pointing them at nothing would only generate failures, so they are deleted.
- **exclusions are deleted, never detached.** `ScanExclusion.endpoint == null` means *applies to every endpoint*, so clearing the field would silently widen an exclusion to the whole fleet — the one case where detaching would be a security regression rather than a tidy-up.

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

### The UI is CoreUI, and the shell lives in two fragments

The front end is **CoreUI 5 (vendored, MIT)**, not plain Bootstrap: `css/coreui.min.css`
replaces `bootstrap.min.css` (CoreUI is a drop-in Bootstrap fork, so every Bootstrap
class still works) and `js/coreui.bundle.min.js` drives the sidebar and the dropdowns.
Charts are Chart.js (also vendored — nothing is loaded from a CDN, and there is no
webfont link, so the console renders identically on a machine with no route out).

Every page is the same four lines around its content:

```html
<head th:replace="~{fragments/head :: head('Page title')}"></head>
<div th:replace="~{fragments/layout :: sidebar}"></div>
<div th:replace="~{fragments/layout :: header('Page title')}"></div>
<div th:replace="~{fragments/layout :: footer}"></div>
<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
```

`static/css/style.css` is deliberately thin: the CoreUI admin template's own layout
layer (ported from its `src/scss/style.scss` — the `.wrapper` sidebar offset that
CoreUI publishes as `--cui-sidebar-occupy-start` but leaves the template to consume)
plus what is ours: `.badge-status` and its verdict variants, `.sig-pill`, the alert
timeline, `.row-icon`. **Add page styling as CoreUI classes first**; reach for
`style.css` only for something CoreUI has no vocabulary for.

Dark mode is CoreUI's native `data-coreui-theme` on `<html>`, resolved by an inline
script in the head fragment *before the first paint* — set it after load and every
navigation flashes white first. `script.js` owns the theme picker, the sidebar
collapse (CoreUI collapses it but does not remember it), and the header filter, which
narrows the rows of any `table[data-filterable]` on the current page and nothing more.

Two things a template must not do: define a CSS variable name that `style.css` does
not (an undefined `var()` makes the whole declaration invalid, which is how the
Endpoints modals ended up with no background at all), and use `btn-outline-light`,
which is a *white* outline meant for dark backgrounds and is invisible on CoreUI's
light theme — `btn-outline-secondary` is the neutral button in both themes.

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

# Dashboard charts: jobs per day by verdict, days with none included
# (VIEWER+, like /api/health, because the dashboard is a VIEWER page)
curl -sS -u admin:admin 'http://HOST:8080/api/stats/timeseries?days=14'

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
- **Only Linux updates its own signatures; Windows and macOS do not.** The Linux packages ship `clamav-freshclam` and the database stays current by itself. The Windows packages ship `freshclam.exe` and no service; Homebrew ships the binaries and no service. So unless the installer schedules it, the database is frozen at whatever the install downloaded — one Windows box sat 11 days behind while the console displayed its version as though all were well. The installers now register a scheduled `freshclam` (a scheduled task on Windows, `com.claimav.freshclam` on macOS), and it is registered regardless of which components were chosen, because every one of them is worthless against a frozen database. On macOS the job ran and **failed every time without anyone noticing**: `freshclam` run as root drops to `DatabaseOwner` (`_clamav`, uid 82) before writing, but the Homebrew database directory belongs to whoever ran `brew install` (or to root, if the installer created it), so every run logged *"must be writable for UID 82"* while `launchctl` showed the job loaded and the console showed a version that just kept aging. The installer now writes an explicit `DatabaseOwner` that exists, `chown -R`s the database directory to it, and checks writability as that user. **Signatures are checked every hour and at every boot, on all three OSes** — a zero-day signature is worth something only while the threat is new. macOS: a calendar slot every hour (minute derived from the hostname, to spread the fleet over ClamAV's CDN) plus `RunAtLoad`, through a helper that first waits for a default route. **Not `StartInterval`**: launchd counts it only while the machine is awake, so on a laptop that sleeps between uses a 2-hour interval had fired zero times after 2h17m of wall-clock time (58 minutes awake). Windows: an hourly repetition plus an `AtStartup` trigger delayed 2 minutes. Linux: `Checks 24` in `freshclam.conf` for the distro's freshclam daemon (which also checks at start; Fedora's default is 12), or our own `claimav-freshclam.timer` where the distro ships no freshclam unit. On Linux the database directory belongs to **freshclam's** `DatabaseOwner`, not clamd's user: on Fedora those are `clamupdate` and `clamscan`, and handing the directory to clamd would silently break every update exactly as on the Macs; clamd reads it through `a+rX`. Note that **the console's "reload signatures" button cannot help here**: clamd's `RELOAD` only makes a *remote clamd* re-read a database freshclam already refreshed, and an agent machine has no clamd for the console to talk to — `SignatureReloadService.sendReload` skips it, and says so.
- **The installer must bootstrap signatures even when ClamAV was already installed.** On macOS that bootstrap used to live inside the "ClamAV not found" branch, so a Mac where ClamAV had been installed by hand got no `freshclam.conf`, no database and no updater — and nothing said so, which is the dangerous half. **A scan with no signature database does not fail in any way the agent can see:** `clamscan` prints `Known viruses: 0`, exits 2, and its summary still reads `Infected files: 0`, which is exactly what the tolerance branch for individual unreadable files accepts as a clean result. One Mac reported clean scans of `/Users` for days with an empty database directory. All three agents now treat `Known viruses: 0` as an error, and the installers print the measured signature level in their closing summary (the presence of the `/` in `clamscan --version` — `ClamAV 1.5.4/28129/...` vs a bare `ClamAV 1.5.4` — is the honest test for "is a database actually loaded"). Same reason `clamav_version()` prefers whichever tool reports the database version: `clamdscan --version` answers with the *client's* version whenever clamd is not running, which would show a healthy number for a machine with no signatures at all.
- **A quarantine inside a scanned tree re-detects itself forever.** The quarantine has to sit on the same filesystem as the file it holds (a same-filesystem `mv` is a pure rename, so it is never intercepted by `OnAccessPrevention`), which puts it *inside* something that later gets scanned. Every scan then finds the quarantined files again, quarantines them again — `clamscan --move` appends `.001`, then `.001.001`, which is where names like `eicar_com.zip.001.001.001...` come from — and the console alerts again, forever, for a threat already dealt with. Fixed in three places, and it needs all three: the agents drop findings whose path is inside a quarantine directory (`in_quarantine()`), `NotificationService` does the same for the whole fleet regardless of agent version, and `--exclude-dir` keeps `clamscan` out of the directory in the first place. **`clamdscan` does not support `--exclude-dir` at all** — it prints `WARNING: Ignoring unsupported option` and scans anyway, so on any machine where clamd is up an exclusion list is decorative (`ExcludePath` in `clamd.conf` is the real equivalent). Note also that filtering findings changes what the exit code means: the scanner still exits 1 and still counts those files in its summary, so a scan whose every hit was filtered must be reported OK explicitly, or it falls through to "scan failed" and notifies anyway.
- **New DB entities need no manual DDL** — `spring.jpa.hibernate.ddl-auto=update` auto-creates/updates tables. Adding a `@Lob String` on Postgres can resurrect the OID problem `PostgresLobMigration` fixes; prefer `columnDefinition = "text"`.

## Deployment

`docker-compose.yml` runs two containers: `clamav` (the clamd server, port 3310) and `clamav-web-client` (this app, `build: .`, published on 8080). It is standalone and works on a fresh clone — it used to require an external macvlan network that the reader did not have, so `docker compose up` simply failed. That case is now `docker-compose.lan.yml`, an override whose network name comes from `LAN_NETWORK` in a gitignored `.env`: hardcoding it meant every host carried a local edit to a tracked file and collided with every `git pull`. PATH/WATCH scans require the scan roots to be mounted into the web-client container. `install-clamd-remote.sh` provisions clamd on a remote host.

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

**`agentKey` is `@JsonIgnore` — keep it that way.** Every `ScanJob` embeds its `ClamdEndpoint`, so any REST response containing a job serializes the endpoint too. Without the annotation `GET /api/jobs` handed every agent key in the fleet to any OPERATOR, which silently defeated "only reachable under `/admin/**`". The Endpoints page reads the key through the getter in Thymeleaf, which Jackson does not affect. Any new secret on an entity that ends up in a JSON response needs the same treatment.

**The Windows install directory is `C:\ProgramData\ClaimAV`, and the scripts the installer writes out must say so too.** `install.ps1.tpl` embeds several scripts as here-strings; each one reads its config and its siblings through `Join-Path $env:ProgramData 'ClaimAV\...'`, and the quarantine filters match `ClaimAV[\\/]quarantine` (which `NotificationService` also checks, as `/claimav/quarantine/`). The product rename changed those embedded paths to `ClamAV Dashboard\...` while `$InstallDir` stayed `ClaimAV`: every Windows agent installed in the next six days died on line 2 of `poll-agent.ps1` and showed offline, with Task Scheduler reporting only `0x1`. A search-and-replace on the product name must skip identifiers written onto machines.

`AgentInstallController` serves the generated installers, substituting `@@CONSOLE_URL@@` / `@@AGENT_KEY@@` / `@@ENDPOINT_NAME@@` into `resources/agent/install.sh.tpl` and `install.ps1.tpl`. The `.sh` installer is **self-contained**: `@@EMBED_*@@` placeholders are replaced with the full text of the agent scripts, written out at run time from quoted heredocs (`__CLAIMAV_EMBED_*__` delimiters; the controller refuses to render if a script contains the delimiter). Download it, run it, done — a bootstrap that fetches more pieces breaks whenever the machine cannot reach the console mid-install. The scripts live at the repo root and are copied into the jar by a `maven-resources-plugin` execution (`copy-agent-scripts`) — one canonical copy, not two. `/agent/files/<name>` still serves them individually (allow-list, no traversal).

Both installers **ask what to install** (realtime / console-dispatched scans / scheduled scan) when they have a terminal, and install everything when they do not — `curl … | bash` has the pipe on stdin, so there is nothing to read answers from. Flags (`--all`, `--realtime`, `--central`, `--scheduled`; `-All`, `-Central`, `-Scheduled` on Windows) skip the prompts.

**The Dockerfile must copy the root-level agent scripts.** They are packaged into the jar by `maven-resources-plugin` reading `${basedir}`, so a build context without them (the Dockerfile used to copy only `pom.xml` and `src`) still succeeds — an `<include>` that matches nothing is not an error — but produces a jar with no `agent/*.sh`, and the console can no longer generate the agent installer. `mvn package` on the host works, so the failure only shows up in Docker, which is how this project actually runs. `AgentInstallController.verifyPackagedScripts()` logs an explicit error at startup if any of them is missing, rather than leaving it to be discovered from a failed download.

**`AgentInstallController` must stay out of the `web` package.** `GlobalExceptionHandler` is a `@ControllerAdvice(basePackages = "…webclient.web")` whose `@ExceptionHandler(Exception.class)` returns `redirect:/dashboard`; a download controller under `web` answers every failure with a silent redirect instead of the file, which is exactly what "the installer won't download" looks like.

**On-access cannot work inside an unprivileged container.** Verified experimentally: the same container image, same clamonacc binary, run without `CAP_SYS_ADMIN` fails with `fanotify_init failed: Operation not permitted`, and run with `--cap-add SYS_ADMIN` gets past fanotify_init entirely. Even `clamonacc --version` trips over it. `fanotify_init()` needs `CAP_SYS_ADMIN` in the *initial* user namespace, which an unprivileged LXC or Docker container never has however root-like the process looks; clamonacc exits with `fanotify_init failed: Operation not permitted`. The installer detects the container up front (`systemd-detect-virt --container`), says so, and — when the service still fails — explains that specific cause instead of a generic "check your kernel". Console-driven and scheduled scans are unaffected and work fine in a container. The remedies are a privileged LXC (`unprivileged=0`) or running the agent on the host.

**Never refresh a signature database that already works.** `freshclam` deletes the old files before writing the new ones, so a failed download — a proxy, or the very common `429 Too Many Requests` from ClamAV's CDN — turns a healthy install into one with no signatures, and then clamd does not start at all. The installer now runs freshclam only when the database is missing, and lets `clamav-freshclam` handle updates.

**clamd's start condition is an AND of two globs.** systemd guards the unit with `ConditionPathExistsGlob` on *both* `daily.{c[vl]d,inc}` and `main.{c[vl]d,inc}`; a check that accepts either one declares success while systemd still logs `skipped, unmet condition check`, which reads like a crash but only means there are no signatures. `db_present()` requires both, across all three extensions.

**Fix database ownership when clamd's user changes.** Switching clamd back to its packaged user leaves `/var/lib/clamav` owned by whoever ran freshclam last (usually root), and the daemon then cannot read it — a failure that looks unrelated to the change. The installer chowns the database, log and run directories to the configured user before starting.

**clamonacc refuses to start without an exclusion directive.** It is a hard startup check, not advice: with none of `OnAccessExcludeUID` / `OnAccessExcludeUname` / `OnAccessExcludeRootUID` set, it exits with *"at least one of ... must be specified"*. That interacts badly with `--scan-system`, which makes clamd run as root: excluding "the clamd user" then means excluding root, which blinds realtime scanning to every root-owned process.

The way out is that **clamd does not need root when everything uses fd-passing**. clamonacc and clamdscan run as root, open the file and hand clamd the descriptor — verified: a clamd running as `clamav` detects EICAR inside a `0600` root-only file with `--fdpass`, and fails with `Permission denied` without it. So in agent mode with a LocalSocket present, the installer puts clamd back on its packaged user (taken from the owner of `/var/lib/clamav`, since a previous run may already have written `User root`) and writes `OnAccessExcludeUname <user>` — full coverage. Only when clamd really must be root does it fall back to `OnAccessExcludeRootUID yes`, and it says out loud what that costs. Stale exclusion directives from earlier runs are removed first, otherwise a machine that once ran clamd as root keeps the root exclusion forever.

**Never install a unit that cannot start.** The installer probes clamonacc before writing `clamav-onacc.service`: it runs the binary briefly and looks for `fanotify_init failed`. If on-access is impossible the unit is not installed at all, because `Restart=always` on a deterministically failing service produces an endless restart loop that floods the journal with the same two lines. The unit that does get installed uses `Restart=on-failure` plus `StartLimitIntervalSec`/`StartLimitBurst`, so even an unforeseen failure stops after three attempts. The probe bounds clamonacc with `timeout`, and falls back to background+kill where `timeout` is missing — otherwise an empty probe result would be read as "fanotify works" and the broken unit would be installed anyway.

**Under `set -u`, define before you log — and a silent script needs a crash report.** `clamav-telegram-alert.sh` (installed as `clamav-scan-report.sh`) logged "skipping missing path(s)" before `TIMESTAMP`/`LOG_FILE` existed, so the missing-path skip died with `TIMESTAMP: unbound variable` on exactly the machines it was written for, and only there — a fleet where every default path exists never triggers it. Because the script is silent on a clean scan by design, the crash looked like a quiet night on the console for days. It now has an `EXIT` trap that posts an `ERROR` report whenever it exits nonzero; every normal path ends in `exit 0`, so a nonzero exit always means the script itself broke. Test a script change with a missing path, an unreachable console and a finding, not just the happy path.

**Two Windows PowerShell 5.1 traps in the agent scripts, both silent because the scripts run hidden.** `Start-Process -ArgumentList` refuses an empty string element, so passing `'-Mode', $mode` with no group (empty mode) made `poll-agent.ps1` fail to start *every* console scan on a group-less Windows endpoint; the job just sat in RUNNING until it expired. And `ConvertTo-Json` throws "Argument types do not match" on `@($list)` of a `List[object]`, empty or not — use `.ToArray()`. Test a script change by extracting the body from `install.ps1.tpl` and running it (a scheduled task hides every error).

**Report the scan error, not the scan summary.** `clamdscan` prints the real cause first (`ERROR: Can't access file /srv`) and a summary afterwards, so `tail -5` returns "Infected files: 0 / Total errors: 1" and hides it. `scan_error_detail()` greps the error lines and only falls back to the tail.

**Agent scripts must never hide curl's error.** `-s` plus `2>/dev/null` turns every connectivity problem into an indistinguishable "failed", which is precisely what made these installs impossible to debug. `clamav-onacc-report.sh --test` (installed at `/usr/local/bin/` regardless of which components were chosen) prints the curl exit code, the HTTP status and curl's own message, then names the cause: DNS, connection refused, timeout, TLS, rejected key, 404 through the proxy, or a 5xx from the proxy. For a console behind a reverse proxy with a private CA, set `DASHBOARD_CA_BUNDLE` in `/etc/clamav/console-report.conf` (`DASHBOARD_INSECURE=1` exists to confirm the diagnosis, not to be left on).

**The closing summary reports measured state, not intent.** It reads `systemctl is-active` for each unit, so a service that failed to start is shown as not running. An installer that prints "realtime protection active" over a dead clamonacc hides a real gap in coverage.

**On macOS a copy of `/bin/bash` cannot execute, and `launchctl list` will not tell you.** TCC grants Full Disk Access per executable and evaluates a request against the *responsible* process — for a LaunchDaemon its own main process, which every child inherits (the same mechanism by which one grant on Terminal.app covers every tool run from a terminal). Pointing the daemons at `/bin/bash` therefore means the grant covers every bash-rooted chain on the machine, so the installer wants a dedicated path — but it must not get there by copying `/bin/bash`: a copy of an Apple platform binary keeps Apple's code directory and loses the signature that validated it, and the kernel SIGKILLs it on exec (verified, exit 137, on both `arm64e` — where the ABI is reserved for platform binaries — and `x86_64`). The plists installed fine, `launchctl list` showed the job, and the Mac sat "offline" in the console for four days with nothing in any log, because the daemon never started once. So: `build_agent_launcher()` compiles a small launcher that **spawns** bash and waits (after an `exec` the process would *be* `/bin/bash` again and TCC would identify it as such), probes it before any plist is written, and falls back to `/bin/bash` with a warning when there is no compiler. It never rebuilds a launcher that already works — the binary's hash is what TCC keyed the admin's grant to, so a pointless rebuild silently revokes Full Disk Access with no prompt anywhere. And a status check must look for a live PID: launchd lists a job whose program dies on exec exactly like a healthy one.

Coverage differs by OS, and this is a ClamAV limit, not a missing feature:

| OS | clamd | Realtime (on-access) | Scheduled scan + report |
|---|---|---|---|
| Linux | yes | **yes** (`clamonacc`, fanotify) | yes |
| macOS | via Homebrew | **no** — fanotify is Linux-only | yes (LaunchDaemon) |
| Windows | manual install | **no** | yes (Scheduled Task) |

`server.forward-headers-strategy=framework` is set so the console URL baked into a generated installer is the public one when running behind a reverse proxy (the proxy must send `X-Forwarded-Proto`/`-Host`).

## Third-party devices (NAS and other appliances)

A box that cannot run any of the three installers — no systemd, no Homebrew, no PowerShell — can still be a full endpoint, because the console never needs to reach *in*. What it needs on the device is only `sh`, `curl` and a ClamAV binary.

**Adding such a device by IP usually fails, and the failure mode is worth reading correctly.** A QNAP was configured as a TCP endpoint and reported offline: the cause was `Connection refused`, **immediately**, while ping and the QTS web ports answered fine. Refused means the packet arrived and the machine actively answered "nothing is listening here" — so it is *not* the firewall, there is simply no clamd on that port (QTS's Antivirus app uses ClamAV internally and exposes no network clamd). A firewall **drops** the packet and you get a timeout instead. `ConnectionDiagnosis.explain()` exists to say which of the two it was: the clamav-client library reports every network failure as the same `CommunicationException: Error while communicating with the server`, and that one sentence is what makes adding a device an afternoon of guessing.

The way in is the agent model, driven by cron instead of a service manager:

- **`clamav-agent-poll.sh --once` from cron** (every 5 min) is the heartbeat *and* the console-dispatched-scan mechanism in one: the `GET /api/agent/commands` carries `X-Agent-Key`/`X-Agent-Clamav`, which is what updates `agentLastSeenAt` and the signature version. With `--once` the scan runs synchronously in that cron invocation.
- **`CLAMSCAN_BIN` / `CLAMDSCAN_BIN`** in the config file name the binary when ClamAV is not on `PATH`, which on an appliance it never is (it ships inside an application package, e.g. `…/.qpkg/ClamAV/bin/clamscan`). Without them the agents could only answer "Neither clamdscan nor clamscan is installed" on a device where ClamAV works perfectly well.
- **The batch reporter alone is not enough to keep a device visible.** `clamav-telegram-alert.sh` deliberately stays silent when a scan is clean ("a scheduled scan whose whole point is to stay quiet"), so it never updates `agentLastSeenAt` on a healthy machine, and the console would show the device as offline between detections. Pair it with the poll above, or use the poll alone.
- Generate the endpoint's agent key (Admin > Endpoints > the robot button) and **clear the host field**: `endpointStatus` checks `isAgentEnrolled()` first, so a leftover host is only misleading, never used.

## Agent command queue (console-dispatched scans)

This is what makes "Scan" in the UI work for a machine whose clamd cannot read the target: **the agent scans locally**, so there is no TCP, no root/SELinux requirement, and no "path exists here but not on the clamd host".

Flow, spanning `AgentCommandService` / `AgentApiController` / `ApiController.scanReport`:

1. `WebUiController.scanPath` / `scanFullDisk` — when `endpoint.isAgentEnrolled()`, they call `agentCommands.enqueue(...)` instead of the direct clamd path. Full-disk sends **one** command with every target (clamdscan takes several paths at once) rather than one job per directory.
2. `enqueue` creates a `ScanJob` of type **`AGENT`**, status QUEUED, and links it to the `AgentCommand`. The job is visible in Jobs immediately, before the agent has seen it.
3. The agent polls `GET /api/agent/commands` (`?format=text` returns `<id> <base64 target>` per line, so the bash agent needs no `jq`). Returning a command **claims** it: status DISPATCHED, job RUNNING.
4. The agent scans and POSTs to `/api/scan/report` with `commandId`; the existing job is finished (OK / VIRUS_FOUND / ERROR) instead of a new one being created, and notifications fire.

**File actions ride the same queue.** `AgentCommand.type` is `SCAN` (null on old rows) or `QUARANTINE` / `RESTORE` / `RESTORE_ALLOW`, created from the alert page (`WebUiController.alertFileQuarantine` / `alertFileRestore`). Their `jobId` is the *alert's* job, so three things must hold or an alert gets corrupted: `claimPending` only hands a file action to an endpoint whose agent declared `file-actions` in `X-Agent-Capabilities` (an old agent reads every command as a scan — text format `"<id> <base64> <TYPE>"`, scans stay two-field); `/api/scan/report` rejects a file action's `commandId`, and file actions report to `POST /api/agent/commands/{id}/result` instead; and `expireStale` must never `finishError` an alert's job for an expired file action. Restoring needs the exact original → quarantine pair per file, which is `ScanJob.quarantineMapJson`, filled from the agents' `quarantined` report field (or derived from `remediationPath` only when every finding was moved, one path each) and updated by each action. The agent re-checks every request (quarantine only a file ClamAV still detects; restore only out of a quarantine directory, never over an existing file), and "false positive" means the file's SHA-256 goes into `claimav-allow.sfp` in ClamAV's database directory — verified with a real `clamscan`: a matching `sha256:size:name` line turns `FOUND` into `OK`. Windows no longer uses `clamscan --move`, which kept only the file name and made a restore impossible.

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
- **Machines:** the usual setup is three roles, not three fixed hosts — a **dev** machine that only has Docker (no local Java/Maven, so rebuild with `docker compose up --build -d`), a **deployment** host that tracks `main` via `git pull` + compose, and a **test clamd** box that is powered off between sessions, so never assume an endpoint is reachable. Addresses and hostnames belong in your own notes, not in this file: it is published with the repository.
- **Runtime files are gitignored** so they stop blocking `git pull`: `data/*.db` (H2 DB — holds endpoints/users/jobs) and `conf/clamav-web-client.properties` (app settings; recreated with defaults on first run). They do **not** travel with git — copy them by hand if you need the same config/state on another machine.
- **Git workflow:** commit **directly on `main`** and push; do not create feature branches for this repo unless asked.
