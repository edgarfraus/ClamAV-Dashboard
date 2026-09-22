# Security

This console can read files anywhere you allow it to, and it holds the keys that let machines
report into it. Read this before exposing it beyond a trusted network.

## The defaults you must change

Out of the box, and on purpose, this is a *development* configuration:

| | State on a fresh install | What to do |
|---|---|---|
| Admin account | **`admin` / `admin`**, created automatically | Change it at **Admin › Users** before anything else. |
| H2 database console | **Enabled** at `/h2`, admin-only | Disable it for anything production-like (see below). |
| Transport | Plain **HTTP** | Terminate TLS at a reverse proxy — see [installation.md](installation.md). |
| CSRF on `/api/**` | **Disabled**, so scripts and agents can post | Leave it; it is why `/api` is HTTP Basic and the browser forms are not. |
| Agent keys | Stored **in plaintext** in the database | Understand why, below. Protect `./data`. |
| Telegram bot token | Stored **in plaintext** in `conf/` | `chmod 600 conf/` and keep it out of shared backups. |

### Turning off the H2 console

It is a full SQL console over your application database. Remove or comment these lines in
`src/main/resources/application.properties` and rebuild:

```properties
spring.h2.console.enabled=true
spring.h2.console.path=/h2
```

It is already restricted to the admin role, so this is defence in depth rather than an open door
— but an admin session hijacked on a page that also offers arbitrary SQL is a much worse day.

## Roles

Three roles, **cumulative**: creating a user with a role grants every role below it, so an admin
is also an operator and a viewer.

| Role | Can |
|---|---|
| `VIEWER` | Dashboard, server status, alerts (read), `/api/health`, `/api/stats/**` |
| `OPERATOR` | Everything above, plus launching scans, jobs, acknowledging alerts, all of `/api/**` |
| `ADMIN` | Everything above, plus `/admin/**` — endpoints, users, settings, groups, schedules, exclusions, audit — and the H2 console |

There is a fourth, `AGENT`, which no human ever holds. It comes from an enrollment key rather than
a password and is allowed **only** on `/agent/**` and `POST /api/scan/report`.

Passwords are hashed with BCrypt. Both form login (for the UI) and HTTP Basic (for `/api`) are
enabled.

## Agent keys

Each endpoint can hold one enrollment key, generated at **Admin › Endpoints › the robot button**.
It is accepted as an `X-Agent-Key` header, as `Authorization: Bearer`, or as a `?key=` query
parameter.

**Why a key instead of an operator account:** a key maps to `ROLE_AGENT`, which can fetch that
endpoint's commands and post that endpoint's scan results, and nothing else. A stolen key cannot
read other hosts' jobs, cannot launch scans, and cannot see your settings. An operator account —
which is what you would otherwise put on every machine — could do all three.

**Why plaintext:** the console has to be able to regenerate an installer for an existing endpoint
at any time, which means it must be able to show you the key again. The trade-off is deliberate;
it is reachable only under `/admin/**`, and **Rotate** invalidates the old one immediately.

> [!NOTE]
> The `?key=` form exists so that `curl … | sudo bash` works for the installer download. Query
> strings land in proxy and browser logs. It is fine for fetching an installer over a network you
> control; prefer the header everywhere else.

## Path safety

Every path-based scan and every watch directory is checked against `app.allowedScanRoots` before
anything happens. This is the main thing standing between an operator and "read me any file on
the host".

Two things to understand about widening it:

- Adding `/` on a direct-`clamd` endpoint means `clamd` opens the files itself. To read the whole
  filesystem it has to run as root (and on SELinux systems, with `antivirus_can_scan_system=on`).
  You are granting a network service root read access to everything.
- With an **agent**, the scan is local and none of that applies — which is the main security
  reason to prefer agents for broad scans.

`/proc`, `/sys`, `/dev`, `/run` and a bare `/` or drive root are always skipped in a full-disk
scan, even if you add them as targets, because `clamd` cannot exclude subpaths mid-scan and
pointing it at one of those can hang the scan.

## Network exposure

- **Direct clamd endpoints**: `clamd`'s TCP port has **no authentication of any kind**. Anyone who
  can reach it can ask it to scan files and read its configuration. Bind it to the console's
  address and firewall it — `install-clamd-remote.sh --console-ip <ip>` does this for you.
- **Agent endpoints**: nothing listens. The machine calls out to the console over HTTPS, so the
  scanned machine needs no inbound rule at all. This is the safer topology and the one the project
  is moving towards.

## Reporting a vulnerability

Please open a [security advisory](https://github.com/edgarfraus/ClamAV-Dashboard/security/advisories/new)
rather than a public issue, and give us a chance to fix it before it is described publicly.

## Honest limitations

Worth knowing before you rely on this:

- There is **no multi-tenancy**. Every operator sees every endpoint and every job.
- There is **no rate limiting** on login. Put it behind something that has it if it faces a
  network you do not trust.
- Sessions are the Spring Boot defaults; there is no forced re-authentication for admin actions.
- The audit log records admin mutations, not reads.
- Agent keys do not expire on their own. Rotating is manual.
