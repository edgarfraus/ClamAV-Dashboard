# Scanning

## Scan types

| Type | Started by | How it runs |
|---|---|---|
| `UPLOAD` | Scan › Upload | The console stores the file, then streams it to `clamd` (INSTREAM). |
| `PATH` | Scan › Path, or the API | `clamd` opens the path itself — or, on an agent endpoint, becomes an `AGENT` job. |
| `AGENT` | Scan or Full disk, on an agent endpoint | Queued for the agent, which scans locally and reports back. |
| `WATCH` | A watched directory noticing a new file | Same path as `PATH`. |
| `EXTERNAL` | `POST /api/scan/report` | Created already finished. A cron scan on some machine. |
| `REALTIME` | `POST /api/scan/report` with `source: realtime` | Created already finished. An on-access detection. |

> [!NOTE]
> **Upload scans lose the filename.** INSTREAM sends bytes, so `clamd` logs `instream(...): OK` or
> `FOUND` with no name attached. The console knows the name; `clamd`'s own log does not. Use a path
> scan when the filename has to appear in `clamd`'s logs.

## The life of a job

```
QUEUED ──► RUNNING ──► FINISHED
                         └── verdict: OK | VIRUS_FOUND | ERROR | SKIPPED
```

A job is written to the database first and only handed to the worker pool **after the transaction
commits** — otherwise the worker can start on a job that is not yet persisted.

On restart, any job that is not `FINISHED` is reset to `QUEUED` and re-queued. `AGENT` jobs are
deliberately skipped: re-queueing one locally would run it over TCP, which is exactly what the
agent exists to avoid.

Scans run on a fixed thread pool sized by `app.concurrentScans`, built once at startup — so
**changing that setting needs a restart**. The threads are deliberately non-daemon, because a
container shutting down can otherwise kill a scan in flight.

## Full-disk scans

**Scan › Full disk scan** scans the targets configured per endpoint (see
[endpoints.md](endpoints.md#full-disk-scan-targets)).

On an agent endpoint the console sends **one** command carrying every target, not one job per
directory — `clamdscan` takes several paths in a single run, and one job is also what the user
expects to see.

`/proc`, `/sys`, `/dev`, `/run` and a bare `/` or drive root are always skipped, even if listed:
`clamd` has no way to exclude subpaths once a scan has begun, so pointing it at one of these can
hang the scan.

> [!TIP]
> A broad scan will always meet files it cannot read. That alone does not fail the scan — but a
> scan that reports **`Known viruses: 0`** does, because it means the machine has no signature
> database and would report everything clean without looking at anything.

## Scheduled scans

**Admin › Schedules** runs a path scan on a cron schedule against one endpoint or a whole group.

Spring cron has **six** fields — `second minute hour day-of-month month day-of-week`:

| Expression | Meaning |
|---|---|
| `0 0 2 * * *` | every day at 02:00 |
| `0 0 2 * * MON` | every Monday at 02:00 |
| `0 0 */6 * * *` | every 6 hours |
| `0 */30 * * * *` | every 30 minutes |

**Run now** triggers one immediately without touching the schedule.

## Watch directories

**Admin › Watch dirs**, and the master switch is in Settings. Each enabled directory is walked
periodically (depth 5, at most 1000 files per pass) and anything new or changed — identified by
path, modification time and size — is queued for a scan.

It is polling, not `inotify`: a file created and deleted between two passes is never seen. The
start/stop buttons only reset the in-process timer; they do not change the persisted setting.

## Exclusions

**Admin › Exclusions** keeps paths out of full-disk scans, globally or per endpoint. An exclusion
with no endpoint applies to **every** endpoint.

There are two layers and you usually need both:

- **This console** decides which paths it *sends* to `clamd`.
- **`clamd` itself** decides which subdirectories it enters once it is scanning recursively. That
  needs `ExcludePath` in `clamd.conf`. The Exclusions page generates the snippet for you.

> [!WARNING]
> **`clamdscan` does not support `--exclude-dir`.** It prints `WARNING: Ignoring unsupported
> option` and scans anyway — so on any machine where `clamd` is running, a command-line exclusion
> list is decorative. `ExcludePath` in `clamd.conf` is the real equivalent.

## Quarantine

Enable it in Settings with a directory. On a detection the file is moved there and the job records
where it went.

The quarantine must sit on the **same filesystem** as the files it holds — a same-filesystem move
is a pure rename, which is never intercepted by on-access prevention. That in turn usually puts it
*inside* a tree that later gets scanned, and that is where it gets interesting:

> [!CAUTION]
> **A quarantine inside a scanned tree re-detects itself forever.** Every scan finds the
> quarantined files again, quarantines them again — `clamscan --move` appends `.001`, then
> `.001.001`, which is where names like `eicar_com.zip.001.001.001…` come from — and alerts again,
> for a threat already dealt with.
>
> This is handled in three places, and it needs all three: the agents drop findings whose path is
> inside a quarantine directory, the console does the same for the whole fleet regardless of agent
> version, and `--exclude-dir` keeps `clamscan` out of it in the first place.
>
> One consequence worth knowing: filtering findings changes what the exit code means. The scanner
> still exits 1 and still counts those files in its summary, so a scan whose every hit was filtered
> has to be reported `OK` explicitly — otherwise it falls through to "scan failed" and notifies
> anyway.

## Alerts

Every `VIRUS_FOUND` job becomes an alert, grouped per endpoint and open until someone
acknowledges it. **Alerts › Ack all open** clears them in bulk; the audit log records who did.

Notifications (Telegram, webhook) fire for `VIRUS_FOUND` and `ERROR`, including results reported
by agents and appliances. Clean scans are silent on purpose.

## Realtime protection

Linux only, via `clamonacc`. See [agents.md](agents.md#realtime-protection-linux).

Detections are forwarded by following `clamonacc`'s log, which looks roundabout until you know
why: `clamd`'s `VirusEvent` **does not fire for on-access scans** — it has been deliberately
disabled in ClamAV since 0.100 over fork/deadlock concerns — and it does not fire for
`clamdscan --fdpass` either. The only place the real path of the infected file appears is
`clamonacc`'s own output.

**Detection vs prevention** is set per group at **Admin › Groups**. Prevention blocks access to
the file; detection only reports. Each agent applies the change on its next poll, so the Groups
page shows which endpoints have picked it up and which are still pending.
