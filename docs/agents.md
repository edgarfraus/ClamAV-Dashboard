# Agents

An agent is a small set of shell (or PowerShell) scripts on a machine that talk to a local
`clamd` and report to the console. **The machine calls the console; the console never calls the
machine.**

That inversion is the point. It means no inbound firewall rule, no `clamd` exposed to the network,
and scans that run with the machine's own permissions instead of a remote daemon's.

## What you get, per OS

| | Linux | macOS | Windows | NAS / appliance |
|---|---|---|---|---|
| Console-dispatched scans | yes | yes | yes | yes (cron) |
| Scheduled local scan | yes (systemd timer) | yes (LaunchDaemon) | yes (Scheduled Task) | yes (cron) |
| **Realtime (on-access)** | **yes** | **no** | **no** | **no** |
| Automatic signature updates | `clamav-freshclam` | installed job, every 2 h | installed nightly task | your own cron |
| Automatic engine updates | manual | manual | weekly task | manual |

Realtime protection needs `fanotify`, which is a Linux kernel facility. There is no macOS or
Windows equivalent in ClamAV — this is a limit of ClamAV, not something missing here.

## Enrolling a machine

1. **Admin › Endpoints** → create an endpoint with a name and **no host**.
2. Click the **robot** button → **Generate key**.
3. Download the installer and run it on the machine:

   ```bash
   sudo bash install-agent.sh          # Linux / macOS
   ```
   ```powershell
   .\install-agent.ps1                 # Windows, as Administrator
   ```

The `.sh` installer is **self-contained**: the console substitutes its URL, the key and every
agent script into one file. Download it, run it, done. A bootstrap that fetches more pieces breaks
exactly when the machine cannot reach the console mid-install, which is the moment you need it to
work.

There is also a one-liner, shown under *Alternative* in the same dialog:

```bash
curl -fsSL 'https://console.example/agent/install.sh?key=KEY' | sudo bash
```

> [!NOTE]
> Both installers **ask what to install** — realtime, console-dispatched scans, scheduled scan —
> when they have a terminal, and install everything when they do not. A pipe has the pipe on
> stdin, so there is nothing to read answers from. Use `--all`, `--realtime`, `--central`,
> `--scheduled` (or `-All`, `-Central`, `-Scheduled` on Windows) to skip the questions.

## What gets installed

- `clamav-agent-poll.sh` — asks the console every 5 minutes for work, runs it, reports back. This
  request is also the heartbeat: it carries the key and the local ClamAV version, which is what
  makes the endpoint show as alive and keeps its signature version current. No separate ping.
- `clamav-onacc-report.sh` — Linux only. Follows the `clamonacc` log and forwards each detection
  as it happens.
- `clamav-telegram-alert.sh` — a scheduled batch scan that reports its result.
- A scheduled `freshclam` (nightly on Windows, every 2 hours on macOS), **regardless of which components you chose**, because every one of them
  is worthless against a frozen signature database.

The installer's closing summary reports **measured** state — it reads `systemctl is-active` for
each unit — so a service that failed to start is shown as not running rather than as a promise.

## Console-dispatched scans

When you press **Scan** in the UI for an agent-managed endpoint, the console does not open a TCP
connection. It queues a command; the agent picks it up on its next poll and scans locally. This is
what makes scanning work for a machine whose `clamd` could never read the target, and it removes
the root/SELinux requirement entirely.

The job appears in **Jobs** immediately, as type `AGENT` and status `QUEUED`, before the agent has
seen it. Fetching the command claims it (`RUNNING`); the agent's report finishes it.

A command that is claimed but never answered is closed after **6 hours**, and one never claimed
after **24 hours**, with a readable reason — so an agent that is switched off does not leave jobs
stuck in `RUNNING` forever.

## Realtime protection (Linux)

On-access scanning needs `clamonacc` running as root with `fanotify`, and it comes with two
sharp edges the installer handles for you:

- **It refuses to start without an exclusion directive.** With none of `OnAccessExcludeUID`,
  `OnAccessExcludeUname` or `OnAccessExcludeRootUID` set, it exits complaining that at least one
  must be specified. The installer sets `OnAccessExcludeUname` to `clamd`'s own user — which works
  because `clamonacc` passes file descriptors to `clamd`, so `clamd` does **not** need to be root
  to read a root-owned file. Only when `clamd` really must run as root does it fall back to
  excluding root, and it says out loud what that costs: realtime becomes blind to every
  root-owned process.
- **`OnAccessIncludePath` must never be `/`.** That loops. Name real directories.

> [!CAUTION]
> **On-access cannot work inside an unprivileged container.** `fanotify_init()` needs
> `CAP_SYS_ADMIN` in the *initial* user namespace, which an unprivileged LXC or Docker container
> never has, however root-like the process looks. Verified: the same image fails with
> `fanotify_init failed: Operation not permitted` without `--cap-add SYS_ADMIN` and gets past it
> with. The installer detects the container up front and says so, and it does **not** install a
> unit that cannot start — `Restart=always` on a deterministically failing service just floods the
> journal. Console-dispatched and scheduled scans are unaffected and work fine in a container.

## Configuration on the machine

`/etc/clamav/console-report.conf`:

| Setting | For |
|---|---|
| `CLAMSCAN_BIN`, `CLAMDSCAN_BIN` | Absolute paths when ClamAV is not on `PATH` — on an appliance it never is. |
| `DASHBOARD_CA_BUNDLE` | A private CA bundle, when the console is behind a proxy using one. |
| `DASHBOARD_INSECURE=1` | Skip certificate verification. This exists to **confirm a diagnosis**, not to be left on. |

## Testing an agent

```bash
clamav-onacc-report.sh --test
```

Installed at `/usr/local/bin/` whichever components you chose. It prints curl's exit code, the
HTTP status and curl's own message, then names the cause: DNS, connection refused, timeout, TLS,
rejected key, a 404 through the proxy, or a 5xx from the proxy.

This exists because the opposite made these installs impossible to debug: `curl -s` plus
`2>/dev/null` turns every connectivity problem into an indistinguishable "failed".

## Rotating and revoking

**Rotate** generates a new key and invalidates the old one immediately; agents installed with the
old key stop being accepted and must be reinstalled. **Revoke** removes the key without issuing a
new one.
