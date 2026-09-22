# Documentation

| Guide | Read it when |
|---|---|
| [Installation](installation.md) | Setting the console up, moving it, upgrading it, putting it behind a proxy |
| [Configuration](configuration.md) | Looking for a setting, or wondering which of the two config stores holds it |
| [Endpoints](endpoints.md) | Adding a machine — including a NAS or an appliance that cannot run an installer |
| [Agents](agents.md) | Installing the agent, and what each operating system can actually do |
| [Scanning](scanning.md) | Scan types, the job lifecycle, schedules, watching, exclusions, quarantine |
| [REST API](api.md) | Driving the console from a script |
| [Security](security.md) | **Before exposing this to anyone else** |
| [Troubleshooting](troubleshooting.md) | Something is wrong, or something looks suspiciously right |
| [Architecture](architecture.md) | Changing the code |

## If you are in a hurry

- **Just want it running:** [Installation → First run](installation.md#first-run).
- **Adding a machine:** decide [direct clamd or agent](endpoints.md#two-kinds-and-how-to-tell-them-apart)
  first — everything else follows from that.
- **Putting it on a network other people can reach:**
  [the defaults you must change](security.md#the-defaults-you-must-change).
- **A scan says clean and you do not believe it:**
  [start here](troubleshooting.md#it-says-clean-and-i-do-not-believe-it) — a machine with no
  signature database reports everything clean and nothing looks wrong.
