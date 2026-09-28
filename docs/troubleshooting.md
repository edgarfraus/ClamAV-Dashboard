# Troubleshooting

Most of what is collected here has one thing in common: **the failure looked like something else,
or like nothing at all.** They are grouped by the symptom you would actually notice.

---

## "It says clean, and I do not believe it"

### A machine with no signature database reports everything clean

This is the most dangerous failure in the whole system, because nothing anywhere looks wrong.

`clamscan` with an empty database prints `Known viruses: 0`, exits 2, and its summary still reads
`Infected files: 0` — which is exactly what the tolerance branch for unreadable files accepts as a
clean result. A Mac reported clean scans of `/Users` for days with an empty database directory.

**How to check**, on the machine:

```bash
clamscan --version
```

`ClamAV 1.5.4/28129/Tue Sep 22 ...` — the part after the `/` is the database version, so this one
is fine. A bare `ClamAV 1.5.4` with no slash means **there is no database**.

**In the console**, the endpoint's DB age shows `no DB ⚠` rather than a neutral dash. All three
agents now treat `Known viruses: 0` as an error rather than a clean result.

**Fix:** run `freshclam` on that machine and make sure something keeps doing so.

### Signatures are never updated on Windows or macOS

Only Linux updates itself: the distribution packages ship `clamav-freshclam` as a service. The
**Windows** packages ship `freshclam.exe` and no service; **Homebrew** ships the binaries and no
service. Unless something schedules it, the database is frozen at whatever the install downloaded
— one Windows machine sat 11 days behind while the console displayed its version as though all
were well.

Current installers check for new signatures **every hour and at every boot** (a Scheduled Task on
Windows, `com.claimav.freshclam` on macOS, `Checks 24` for the distro's freshclam daemon on Linux)
**regardless of which components you chose**. If you installed an agent before that, re-run the
installer.

On **macOS**, a job that is loaded is not a job that works. Run as root, `freshclam` drops to its
`DatabaseOwner` (`_clamav`, uid 82) before writing, while the Homebrew database directory belongs
to whoever ran `brew install`. Every run then fails with *"Can't create temporary directory …
must be writable for UID 82"* in `/var/log/clamav-freshclam.log`, and nothing else says so.
Installers before this fix left it that way; re-running the installer sets `DatabaseOwner` and
the directory's owner consistently. By hand:

```bash
sudo chown -R _clamav "$(brew --prefix)/var/lib/clamav"
sudo freshclam --config-file="$(brew --prefix)/etc/clamav/freshclam.conf"
```

> [!NOTE]
> **The "reload signatures" button cannot help here.** `clamd`'s `RELOAD` makes a *remote clamd*
> re-read a database `freshclam` has already refreshed. An agent machine has no `clamd` for the
> console to talk to, so the console skips it and says so.

### A full-disk scan on Windows finishes in seconds and finds nothing

`clamscan.exe` does **not** recurse into subdirectories by default, unlike `clamdscan`, which
hands directories to `clamd` and lets it walk them. If you are running a modified agent, make sure
`--recursive` is still there.

### macOS scans almost nothing under /Users

macOS TCC blocks access to protected user data — Desktop, Documents, Mail, Photos, most of
`~/Library` — even for a root process. Without **Full Disk Access** granted to the agent's
launcher, `clamscan` silently cannot read nearly anything, and the "some files were unreadable"
tolerance reports that as a clean pass.

Grant Full Disk Access to the launcher the installer created (`/usr/local/libexec/claimav/...`),
not to `/bin/bash` — granting it to the system shell hands disk access to every script anything on
the machine runs.

---

## "The machine shows offline"

### Connection refused vs timed out — they need opposite fixes

| What you see | What actually happened | What to do |
|---|---|---|
| **Connection refused**, immediately | The packet arrived and the machine answered "nothing is listening here". | **Not the firewall.** There is no `clamd` on that port. Start it, or check `TCPSocket`/`TCPAddr` in `clamd.conf`. |
| **No answer / timed out** | The packet was dropped; nothing answered at all. | This *is* a firewall, or the wrong address. |

A QNAP added by IP produced the first one while its web interface answered perfectly: QTS's
Antivirus app uses ClamAV internally and exposes no network `clamd`. See
[endpoints.md](endpoints.md#third-party-devices-nas-and-appliances) for how to enrol such a device
with an agent instead.

### An agent-managed endpoint shows "Agent silent"

It has not checked in for 15 minutes. In order of likelihood:

1. **The poll service is not running.** `systemctl status clamav-agent-poll` (Linux),
   `launchctl list | grep claimav` (macOS), Task Scheduler (Windows).
2. **It cannot reach the console.** Run the built-in diagnostic on the machine:
   ```bash
   clamav-onacc-report.sh --test
   ```
   It prints curl's exit code, the HTTP status and curl's message, then names the cause: DNS,
   connection refused, timeout, TLS, rejected key, 404 through the proxy, 5xx from the proxy.
3. **The key was rotated** and the agent still has the old one. Reinstall.
4. **Private CA** in front of the console: set `DASHBOARD_CA_BUNDLE` in
   `/etc/clamav/console-report.conf`.

The console can confirm it from the outside, without logging in to the machine:

```bash
curl -sS -u admin:admin https://HOST/api/endpoints/<id>/status
```

`lastSeen` is the last request the agent made. If it stops moving while the machine is on, the
problem is on the machine. See [the API reference](api.md#endpoint-status).

### A Windows agent goes offline right after installing

On Windows, the poll task exits silently (`exit 0`) when the console is unreachable, so it retries
on the next pass without flooding anything. That also means **Task Scheduler's "Last Run Result"
is the first thing to read**:

```powershell
Get-ScheduledTask -TaskName 'ClaimAV*' | ForEach-Object {
  $i = $_ | Get-ScheduledTaskInfo
  '{0,-26} last={1} result=0x{2:X}' -f $_.TaskName, $i.LastRunTime, $i.LastTaskResult }
```

`0x0` is fine, and `0x41303` means the task has not run yet. Anything else means the script
itself failed before reaching the console. Run it by hand to see why:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:ProgramData\ClaimAV\poll-agent.ps1"
```

Installers generated between 22 and 28 September 2026 wrote scripts that looked for their
configuration in `C:\ProgramData\ClamAV Dashboard\` while the installer put it in
`C:\ProgramData\ClaimAV\`. The poll died on its second line with
`Cannot find path 'C:\ProgramData\ClamAV Dashboard\agent.conf.json'`, and so did the scheduled scan
and the signature updater. **Re-run the installer from a current console** to fix it. You do not
need a new key or a new endpoint: the reinstall reuses the same directory and replaces the same
scheduled tasks.

> [!IMPORTANT]
> The Windows install directory is `C:\ProgramData\ClaimAV` and the task names start with
> `ClaimAV`, on purpose, even though the product is called ClamAV Dashboard. Renaming them would
> leave existing machines with two installs and two sets of tasks side by side.

### A macOS agent was installed and never ran once

Older installers pointed their LaunchDaemons at a **copy** of `/bin/bash`. A copy of an Apple
platform binary keeps Apple's code directory but loses the signature that validated it, so the
kernel kills it on exec (exit 137). The plists installed fine, `launchctl list` showed the job, and
the Mac sat offline for four days with nothing in any log — because `launchd` lists a job whose
program dies on exec exactly like a healthy one.

Re-run the current installer: it compiles a small launcher that *spawns* bash and waits, probes it
before writing any plist, and checks for a live PID rather than a listed job.

> [!IMPORTANT]
> The installer never rebuilds a launcher that already works. The binary's hash is what TCC keyed
> the admin's Full Disk Access grant to, so a pointless rebuild silently revokes that access with
> no prompt anywhere.

### Heartbeat stops during a long scan

Fixed in current agents: the scan is backgrounded during `--loop` so the poll keeps sending
heartbeats, and on Windows the scan runs detached. If you are on an older agent, a full-disk scan
longer than 15 minutes marks the endpoint offline while it is actively working.

---

## "It keeps alerting about the same file"

A quarantine directory that lives inside a scanned tree re-detects itself forever, producing names
like `eicar_com.zip.001.001.001`. See [scanning.md](scanning.md#quarantine). Current console and
agents filter it; if you still see it, an old agent is reporting.

---

## Realtime protection will not start

### Inside a container, it cannot

`fanotify_init()` needs `CAP_SYS_ADMIN` in the **initial** user namespace, which an unprivileged
LXC or Docker container never has, however root-like the process looks. Even `clamonacc --version`
trips over it. The remedies are a privileged LXC (`unprivileged=0`) or running the agent on the
host. Console-dispatched and scheduled scans work fine in a container.

### "at least one of ... must be specified"

`clamonacc` refuses to start without an exclusion directive — one of `OnAccessExcludeUID`,
`OnAccessExcludeUname`, `OnAccessExcludeRootUID`. The installer sets `OnAccessExcludeUname` to
`clamd`'s own user. If a previous run left `User root` in `clamd.conf`, stale exclusions can
survive and blind realtime scanning to every root-owned process; re-run the installer, which
removes them first.

### clamd will not start after an update

Two common causes:

- **A failed `freshclam` deleted the working database.** `freshclam` removes the old files before
  writing the new ones, so a proxy error or a `429 Too Many Requests` from ClamAV's CDN leaves the
  machine with no signatures — and then `clamd` does not start at all. The installer only runs
  `freshclam` when the database is missing and leaves updates to `clamav-freshclam`. **Never
  refresh a database that already works.**
- **Ownership.** Switching `clamd` back to its packaged user leaves `/var/lib/clamav` owned by
  whoever ran `freshclam` last, usually root, and the daemon cannot read it.

`systemd` guards the unit with `ConditionPathExistsGlob` on **both** `daily.{c[vl]d,inc}` **and**
`main.{c[vl]d,inc}`. A check that accepts either one declares success while systemd logs
`skipped, unmet condition check` — which reads like a crash but only means there are no signatures.

---

## The console itself

### Settings saved but nothing changed

**Concurrent scans** needs a restart: the thread pool is built once at startup.

**Unticked checkboxes** are not submitted by browsers at all. The Settings page warns about this.

### A path exists but the scan says it does not

For a direct `clamd` endpoint there are two containers involved. The file must be mounted into the
**`clamav-server`** container for `clamd` to read it, and into the **console** container for path
validation and watching. Mounting only one is the usual cause. With an agent, none of this applies.

### The UI looks half-styled after an upgrade

Force-reload (Ctrl+F5 / ⇧⌘R). The stylesheet keeps its filename across versions, so the browser
serves a cached copy against new markup.

### Where to look

```bash
docker compose logs -f clamav-web-client     # the console
docker compose logs -f clamav-server         # clamd itself
```

**Admin › Audit log** records every admin mutation with who, when and from which address.
