<img width="80" height="80" alt="ClaimAV Dashboard" src="https://github.com/user-attachments/assets/8353cc36-5c0a-4ec1-a581-660ae9d4d03e" />

# ClaimAV Dashboard

A web console and **job tracker** for [ClamAV](https://www.clamav.net/). It runs scans across a
fleet of machines — by talking to `clamd` directly, or through a lightweight agent installed on
each machine — and keeps every scan as a job you can search, filter and audit.

Built with Spring Boot 3.3 and Java 17, deployed as a single container.

> [!IMPORTANT]
> **This console only tracks scans it started.** A scan run directly against `clamd` on some
> machine — `clamdscan` from a shell, a cron job that does not report back — will never appear
> here. That is a deliberate boundary, not a gap: the console is the record of what *it* asked for.

---

## Two ways to reach a machine

This is the one decision that shapes everything else, so it comes first.

| | **Direct clamd** | **Agent** |
|---|---|---|
| Who calls whom | console → machine, over TCP | machine → console, over HTTPS |
| Needs an open port on the machine | yes (`clamd` on 3310) | **no** |
| Needs `clamd` reachable from the console | yes | no — `clamd` stays on `127.0.0.1` |
| Can scan files `clamd` cannot read | no | yes, the agent scans locally |
| Works behind NAT / a firewall you do not control | no | yes |
| Set up by | `install-clamd-remote.sh` | a generated one-line installer |

The agent is the better answer for anything you do not fully control, and the only answer for a
machine behind NAT. Direct `clamd` is still required for **upload scans** (the console streams the
file to a `clamd` over INSTREAM) and for **watch directories**, so a console with only agent
endpoints usually keeps one reachable `clamd` around — typically the one in `docker-compose.yml`.

## What it does

- **Scan** — upload files, scan a path, or launch a full-disk scan on any endpoint.
- **Track** — every scan is a job: type, status, verdict, target, endpoint, who asked, timings,
  the signatures found, the error if it failed.
- **Alert** — virus detections are grouped per endpoint and stay open until someone acknowledges
  them, with Telegram and webhook notifications.
- **Automate** — cron-scheduled scans against an endpoint or a whole group; watch directories
  polled for new files.
- **Quarantine** — move infected files out of the way, without the quarantine re-detecting itself.
- **Control** — three cumulative roles (viewer / operator / admin), and an audit log of every
  admin action.

### Coverage by operating system

Realtime (on-access) protection is a Linux kernel feature; this is a ClamAV limit, not a missing
feature of this project.

| OS | clamd | Realtime (on-access) | Scheduled scan + report | Console-dispatched scan |
|---|---|---|---|---|
| Linux | yes | **yes** (`clamonacc`, fanotify) | yes | yes |
| macOS | via Homebrew | no — fanotify is Linux-only | yes (LaunchDaemon) | yes |
| Windows | manual install | no | yes (Scheduled Task) | yes |
| NAS / appliance | whatever it ships | no | yes (cron) | yes |

## Quick start

```bash
git clone https://github.com/edgarfraus/ClaimAV-Dashboard.git
cd ClaimAV-Dashboard
docker compose -f docker-compose-mac.yml up --build -d
```

This brings up two containers — the console and a `clamd` server it can talk to — and publishes
the console on port 8080. Open **http://localhost:8080** and sign in with **`admin` / `admin`**.

> [!WARNING]
> Change that password immediately under **Admin › Users**. The console also enables an H2
> database console at `/h2` and serves plain HTTP. Read [docs/security.md](docs/security.md)
> **before** putting this anywhere other people can reach.

**There are two compose files, and the one to start with is not the default one:**

| File | What it does |
|---|---|
| `docker-compose-mac.yml` | Standalone. Publishes `8080:8080`, no external network. **Start here** — despite the name, nothing in it is macOS-specific. |
| `docker-compose.yml` | Attaches the console to an **existing** Docker network named `lan`, to give it its own address on your LAN. It publishes no port, and `docker compose up` fails unless that network already exists. |

The first build compiles the application with Maven inside the container, so it takes a few
minutes and needs no JDK on the host.

## Documentation

| | |
|---|---|
| [Installation](docs/installation.md) | Docker, PostgreSQL, reverse proxy, upgrading |
| [Configuration](docs/configuration.md) | The two config stores and every setting |
| [Endpoints](docs/endpoints.md) | Adding machines, direct clamd vs agent, appliances |
| [Agents](docs/agents.md) | Enrollment keys, installers, what each OS supports |
| [Scanning](docs/scanning.md) | Scan types, job lifecycle, schedules, exclusions, quarantine |
| [REST API](docs/api.md) | Every endpoint, with working `curl` calls |
| [Security](docs/security.md) | Roles, the defaults you must change, hardening |
| [Troubleshooting](docs/troubleshooting.md) | Failures that look like something else |
| [Architecture](docs/architecture.md) | How it fits together, for contributors |

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

**LGPL-2.1**. See [LICENSE](LICENSE) for the full text.

This project is derived from [rguziy/clamav-web-client](https://github.com/rguziy/clamav-web-client)
by Ruslan Huzii and still contains code from it. Bundled third-party components — CoreUI,
Chart.js, Font Awesome, flag-icons — keep their own licences; all of it is listed in
[NOTICE](NOTICE).

ClamAV is a trademark of Cisco Systems, Inc. This is not an official ClamAV product and is not
endorsed by Cisco.
