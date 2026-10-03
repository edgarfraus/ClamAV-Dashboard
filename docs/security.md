# Security

This console can read files anywhere you allow it to, and it holds the keys that let machines
report into it. Read this before exposing it beyond a trusted network.

## The defaults you must change

Out of the box, and on purpose, this is a *development* configuration:

| | State on a fresh install | What to do |
|---|---|---|
| Admin account | **`admin` / `admin`**, created automatically | Nothing: the first sign-in **forces a new password**, and until then the API refuses that account. |
| H2 database console | **Off.** `/h2` exists only when `H2_CONSOLE_ENABLED=true` | Leave it off; switch it on only for maintenance (see below). |
| Transport | Plain **HTTP** | Terminate TLS at a reverse proxy — see [installation.md](installation.md). |
| CSRF on `/api/**` | **Disabled**, so scripts and agents can post | Leave it; it is why `/api` is HTTP Basic and the browser forms are not. |
| Agent keys | Stored **in plaintext** in the database | Understand why, below. Protect `./data`. |
| Telegram bot token | Stored **in plaintext** in `conf/` | `chmod 600 conf/` and keep it out of shared backups. |
| Telegram buttons | **Off.** When on, only the listed Telegram users can act | List only people who should move files on the fleet; see [scanning.md](scanning.md#from-telegram). |

### The H2 console

A full SQL prompt on the application database — users, password hashes, agent keys, every job —
served by H2 itself at `/h2`. Nothing in the console needs it; it is there for the occasional
manual repair. It is **off by default**. To switch it on for a maintenance session, set the
variable in `docker-compose.yml` (the line is there, commented out) and restart:

```yaml
    environment:
      - H2_CONSOLE_ENABLED=true
```

Even when on it is restricted to the admin role, and an account still on its initial password
cannot reach it. Switch it off again afterwards: an admin session hijacked on a page that also
offers arbitrary SQL is a much worse day. It does not exist at all on PostgreSQL.

## Roles

Three roles, **cumulative**: creating a user with a role grants every role below it, so an admin
is also an operator and a viewer.

| Role | Can |
|---|---|
| `VIEWER` | Dashboard, server status, alerts (read), `/api/health`, `/api/stats/**` |
| `OPERATOR` | Everything above, plus launching scans, jobs, acknowledging alerts, all of `/api/**` |
| `ADMIN` | Everything above, plus `/admin/**` — endpoints, users, settings, groups, schedules, exclusions, audit — and the H2 console when it is switched on |

There is a fourth, `AGENT`, which no human ever holds. It comes from an enrollment key rather than
a password and is allowed **only** on `/agent/**` and `POST /api/scan/report`.

Passwords are hashed with BCrypt. Both form login (for the UI) and HTTP Basic (for `/api`) are
enabled.

### The forced password change

The `admin` account created on first run is marked **must change password**. So is any account
named `admin` that still has the password `admin` when the console starts, which covers consoles
installed before this existed. While the mark is set:

- every page redirects to **Change password** (`/account/password`), and the account can do
  nothing else but sign out;
- `/api/**`, `/actuator/**` and `/h2` answer `403` with
  `{"error": "Password change required: ..."}`, so a script still using `admin:admin` stops with a
  readable reason instead of quietly working on the default credentials;
- agents are not affected: they authenticate with their key, not as a user.

The new password must be at least 8 characters and differ from the current one, the username and
`admin`. After the change the session is closed and you sign in again with the new password. Any
user can change their own password later from the account menu.

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

> [!WARNING]
> Before 28 September 2026 that was not true: every job returned by `GET /api/jobs` embedded its
> endpoint **with its agent key**, so any `OPERATOR` could read the key of every agent in the
> fleet. The key is now excluded from all JSON output. If you gave `OPERATOR` accounts to anyone
> you would not show those keys to, **rotate the keys** after upgrading and reinstall the agents.

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
