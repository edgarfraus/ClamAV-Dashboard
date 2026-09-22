# Contributing

Thanks for considering it. This is a small project; nothing here is heavy process.

## Before you start something big

Open an issue first and describe what you are after. It is much easier to agree on an approach
before the code exists than to ask for a rewrite afterwards.

## Getting set up

```bash
git clone https://github.com/edgarfraus/ClaimAV-Dashboard.git
cd ClaimAV-Dashboard
docker compose up --build -d
```

That needs only Docker — Maven runs inside the build container. With a local JDK 17+ you can also
use `mvn spring-boot:run`.

[docs/architecture.md](docs/architecture.md) explains the layout and the handful of traps that are
not obvious from reading the code.

## Pull requests

- **One subject per PR.** A UI change and a schema change in the same branch are hard to review
  and harder to revert.
- **Match the surrounding code.** Same naming, same comment density, same idioms.
- **Explain the why in the commit message.** What changed is in the diff; why it changed is not.
  If you fixed something subtle, describe how it presented — the symptom is what the next person
  will search for.
- **Say how you verified it.** "Tested against a database with N rows" or "reproduced the failure,
  applied, no longer reproduces" tells a reviewer far more than "works".
- **User-visible text is English**, in the UI, in log messages and in comments.

## Things that need extra care

- **Schema changes.** `ddl-auto=update` never alters an existing constraint. If you relax a column
  or add an enum value, add a `SchemaFixup` step and test against a copy of a *real* database — an
  empty one will pass regardless.
- **Path handling.** `PathPolicy.isUnderAllowedRoots()` is the main guard against scanning
  arbitrary host paths. Preserve it.
- **Agent scripts.** They run as root on other people's machines. Never hide `curl`'s error, and
  never install a service unit that cannot start.
- **Dependencies.** Anything bundled into `static/` must be added to [NOTICE](NOTICE) with its
  licence, and must be licence-compatible with LGPL-2.1.

## Reporting bugs

Please include the version or commit, how the console is deployed, the OS of the machine involved,
what you expected, what happened, and the relevant log lines:

```bash
docker compose logs --tail 200 clamav-web-client
```

For anything security-sensitive, use a
[security advisory](https://github.com/edgarfraus/ClaimAV-Dashboard/security/advisories/new)
rather than a public issue.

## Licence

By contributing you agree that your contribution is licensed under the **LGPL-2.1**, like the rest
of the project.
