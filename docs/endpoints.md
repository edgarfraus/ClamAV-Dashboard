# Endpoints

An **endpoint** is a machine this console can scan. They live at **Admin › Endpoints**.

## Two kinds, and how to tell them apart

| | Direct clamd | Agent-managed |
|---|---|---|
| Host / port | **required** — the console dials it | **left empty** — there is nothing to dial |
| Direction | console → machine | machine → console |
| Status comes from | a live TCP ping | when the agent last checked in (15-minute window) |
| Firewall on the machine | must allow the console to reach port 3310 | nothing inbound at all |

An endpoint becomes agent-managed the moment you generate an enrollment key for it. Once you do,
**clear the host field**: status is taken from the agent's check-ins, so a leftover host is never
used and only misleads whoever reads the page next.

## Adding a direct clamd endpoint

1. Install and expose `clamd` on the machine. `install-clamd-remote.sh` does this:
   ```bash
   sudo ./install-clamd-remote.sh --console-ip 192.168.1.50
   ```
   It installs ClamAV, binds `clamd` to a reachable address, and restricts the port to the console.
   Add `--scan-system` if you want it to be able to read the whole filesystem — that runs `clamd`
   as root and, on SELinux systems, sets `antivirus_can_scan_system=on`.

2. In **Admin › Endpoints**, fill in a name, the host and port (3310), and pick a platform.
3. The status badge turns green once the console can reach it.

**Platform** (`UNIX` / `WINDOWS` / `JVM_PLATFORM`) only tells the client library how to talk to
`clamd`. It is not where the agent's own OS comes from — that is reported by the agent itself and
shown underneath.

## Adding an agent-managed endpoint

1. **Admin › Endpoints** → create the endpoint with a name and **no host**.
2. Click the **robot** button → **Generate key**.
3. Download the installer for the machine's OS and run it there.

See [agents.md](agents.md) for what the installer does and what each OS supports.

## Groups

**Admin › Groups** puts endpoints into groups. A group is useful for two things: pointing one
scheduled scan at many machines, and switching realtime mode (detection vs prevention) for all of
them at once. An endpoint belongs to at most one group.

Realtime mode changes are applied by each agent on its next poll, not immediately; the Groups page
shows which endpoints have picked it up and which are still pending.

## Full-disk scan targets

The **bullseye** button per endpoint sets which paths a full-disk scan covers, one per line.
Leave it empty for the platform default — and the default is chosen from the OS **the agent
reported**, not from the Platform dropdown, because that is the one that is actually true.

| Reported OS | Default targets |
|---|---|
| Linux | `/etc`, `/home`, `/var`, `/usr`, `/opt`, `/tmp`, … |
| macOS | `/Users`, `/Applications`, `/Library` |
| Windows (agent) | the whole `C:\` |
| Windows (no agent) | `C:\Users`, `C:\Program Files`, … |

`/proc`, `/sys`, `/dev`, `/run` and a bare `/` or drive root are always skipped, even if you list
them, because `clamd` cannot exclude subpaths once a scan has started.

## Third-party devices: NAS and appliances

A QNAP, a Synology, anything that cannot run any of the three installers can still be a full
endpoint, because the console never needs to reach *in*. What the device needs is `sh`, `curl`
and a ClamAV binary.

> [!TIP]
> **Adding a NAS by IP almost always fails, and the failure tells you why.** A QNAP configured as
> a TCP endpoint reports `Connection refused` **immediately**, while its web interface answers
> fine. Refused means the packet arrived and the machine actively said *nothing is listening here*
> — so it is **not** the firewall: there is simply no network `clamd` on that port. QTS's Antivirus
> app uses ClamAV internally and exposes nothing. A firewall *drops* the packet instead, and you
> get a timeout. The console names which of the two it was.

The way in is the agent model with cron instead of a service manager:

1. Generate an agent key for the endpoint and leave the host empty.
2. Copy `clamav-agent-poll.sh` onto the device.
3. Tell it where ClamAV actually lives, because on an appliance it is never on `PATH`:
   ```sh
   # /etc/clamav/console-report.conf
   CLAMSCAN_BIN=/share/CACHEDEV1_DATA/.qpkg/ClamAV/bin/clamscan
   CLAMDSCAN_BIN=/share/CACHEDEV1_DATA/.qpkg/ClamAV/bin/clamdscan
   ```
4. Run it from cron every 5 minutes:
   ```
   */5 * * * * /path/to/clamav-agent-poll.sh --once
   ```

That single cron entry is both the heartbeat and the mechanism for console-dispatched scans: the
`GET /api/agent/commands` it makes carries the key and the ClamAV version, which is what marks the
device alive and updates its signature version.

> [!WARNING]
> **The batch reporter alone is not enough to keep a device visible.** `clamav-telegram-alert.sh`
> stays silent when a scan is clean, so on a healthy machine it never checks in, and the console
> shows the device as offline between detections. Pair it with the poll above, or use the poll on
> its own.

## When an endpoint shows as offline

The console tells the causes apart instead of reporting one generic communication error:

| Message | Means | Fix |
|---|---|---|
| **Connection refused** | The machine answered: nothing is listening on that port. | Start `clamd`, or check `TCPSocket`/`TCPAddr` in `clamd.conf`. Not a firewall problem. |
| **No answer / timed out** | Packets are being dropped. | This *is* a firewall, or the wrong address. |
| **Unknown host** | DNS did not resolve the name. | Use an address, or fix DNS. |
| **No route** | The console cannot reach that network. | Routing or VLAN. |
| **Agent silent** | Agent-managed, and it has not checked in for 15 minutes. | See [troubleshooting.md](troubleshooting.md). |

## Deleting an endpoint

Deleting is not a plain row delete — five things reference an endpoint, and each is handled
deliberately:

- **Scan jobs** keep their history and only lose the link. A job still queued or running is closed
  as `ERROR`: without an endpoint there is nothing left to dial.
- **Watch directories, scheduled scans and pending agent commands** are deleted. They are work
  aimed at a machine that no longer exists and would only generate failures.
- **Exclusions are deleted, never unlinked.** An exclusion with no endpoint means *applies to
  every endpoint*, so detaching one would silently widen it to the whole fleet.
