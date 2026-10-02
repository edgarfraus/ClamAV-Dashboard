# Security policy

ClamAV Dashboard runs with access to files on every machine it manages and holds the keys those
machines use to report in, so security reports are taken seriously.

## Reporting a vulnerability

**Please do not open a public issue.** Report it privately through GitHub instead:
[Security › Report a vulnerability](https://github.com/edgarfraus/ClamAV-Dashboard/security/advisories/new).

Include what you found, how to reproduce it, and what an attacker could do with it. You will get
an answer as soon as possible, and a fix will be published with credit to you unless you prefer
otherwise.

## Supported versions

Only the latest version on `main` receives fixes. Agents are part of the release: after updating
the console, reinstall them from **Admin › Endpoints** to pick up agent-side fixes.

## Scope

In scope: the console (web UI, REST API, authentication), the agents and installers in this
repository, and anything they do on a managed machine.

Out of scope: ClamAV itself (report those to the [ClamAV project](https://www.clamav.net/)), and
deployments that ignore [docs/security.md](docs/security.md) — for example a console exposed
without TLS.
