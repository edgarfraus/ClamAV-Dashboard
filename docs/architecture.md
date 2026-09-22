# Architecture

For people changing the code. If you only want to run it, you want
[installation.md](installation.md).

## Shape

A single Spring Boot 3.3 application (Java 17), one jar, one container.

```
browser ──HTTP──► WebUiController ─┐
scripts ──Basic─► ApiController  ──┼──► services ──► JPA ──► H2 / PostgreSQL
agents  ──key───► AgentApiController┘        │
                                             └──► ClamavClientProvider ──TCP──► clamd
```

```
info.trizub.clamav.webclient
├── api/       ApiController, AgentApiController, AgentInstallController
├── config/    SecurityConfig, AgentAuthenticationFilter, SchemaFixup, StartupInitializer
├── db/        PostgresLobMigration
├── model/     JPA entities + enums
├── repo/      Spring Data repositories
├── service/   the actual behaviour
├── util/      PathPolicy, ConnectionDiagnosis, ClamVersionInfo, HashUtils
└── web/       WebUiController, UiModelAdvice, GlobalExceptionHandler
```

## Building

```bash
mvn clean package            # jar in target/, runs tests
mvn spring-boot:run          # http://localhost:8080
mvn -Dtest=SomeClass test    # one test class
docker compose -f docker-compose-mac.yml up --build -d
```

The local build targets **JDK 17**; the Docker build compiles with JDK 21 and runs on a 21 JRE.
Either works.

> [!NOTE]
> **There are no unit tests yet** — only the `spring-boot-starter-test` dependency. `mvn test` is
> effectively a no-op today. Tests are very welcome.

## Things worth knowing before you change something

### Two configuration stores that do not overlap

The database holds entities; `conf/clamav-web-client.properties` holds admin settings, managed by
`SettingsService` through an atomic write, **not** JPA. Adding a tunable setting means four edits:
the key and default in `SettingsService.init()`, a typed getter there, the key in
`WebUiController.adminSettingsSave()`'s allow-list, and a field in `templates/pages/settings.html`.

### The scan pipeline

`ScanJobService` creates the job → `enqueueAfterCommit()` registers a transaction synchronisation
so the executor only sees it **after** the commit → `ScanExecutionService` runs it on a fixed pool
built once in `@PostConstruct`.

Two traps: `finishOk` / `finishFound` / `finishError` / `markRunning` exist in **both**
`ScanJobService` and `ScanExecutionService`, and the executor uses its own copies — edit the right
one. And `resumeQueued()` re-queues unfinished jobs at startup but **skips type `AGENT`**, because
running one locally would send it over TCP, which is the thing agents exist to avoid.

### Schema migrations

`spring.jpa.hibernate.ddl-auto=update` creates and adds, but **never touches existing
constraints**. Two failures follow from that, and both are invisible on a fresh database — they
only appear on a real one:

- a column that became nullable in the entity stays `NOT NULL`;
- Hibernate's generated `CHECK` for an `@Enumerated(STRING)` column still lists only the *old*
  enum values, so inserting a row with a newly added constant fails.

`SchemaFixup` migrates both on `ApplicationReadyEvent`. **Add a step there whenever you relax a
constraint or add an enum value**, and test against a copy of a real database, not an empty one.

On PostgreSQL, prefer `columnDefinition = "text"` over `@Lob String`: old schemas created those as
OID large objects, which break in autocommit mode. `PostgresLobMigration` repairs existing ones.

### Security filter chain

`AgentAuthenticationFilter` maps an enrollment key to `ROLE_AGENT` and, in the same write, records
the agent's last-seen time and reported ClamAV version — so there is no separate heartbeat request.

It overrides `shouldNotFilterErrorDispatch()` to `false`, and that is not optional:
`OncePerRequestFilter` defaults to `true`, so the forward to `/error` would run without the agent's
authentication and the client would get `401 WWW-Authenticate: Basic` instead of the real 400 or
500. Without it, every agent-side error is unreadable.

### Controllers and the exception handler

`GlobalExceptionHandler` is a `@ControllerAdvice(basePackages = "…webclient.web")` whose
`@ExceptionHandler(Exception.class)` returns `redirect:/dashboard`. That is fine for pages and
wrong for downloads, which is why **`AgentInstallController` lives in `api`, not `web`** — under
`web` it would answer every failure with a silent redirect instead of the file, which is exactly
what "the installer will not download" looks like.

### The UI

CoreUI 5 (vendored, MIT — a drop-in Bootstrap fork), Thymeleaf, no build step, no CDN, no webfont
link. Every page is the same four fragment references:

```html
<head th:replace="~{fragments/head :: head('Page title')}"></head>
<div th:replace="~{fragments/layout :: sidebar}"></div>
<div th:replace="~{fragments/layout :: header('Page title')}"></div>
<div th:replace="~{fragments/layout :: footer}"></div>
<th:block th:replace="~{fragments/layout :: scripts}"></th:block>
```

`static/css/style.css` is deliberately thin: the CoreUI admin template's own layout layer plus what
is ours — verdict badges, signature pills, the alert timeline. **Reach for CoreUI classes first.**

Two things that will bite you:

- **A CSS variable a template uses but no stylesheet defines invalidates the whole declaration.**
  That is how two modals ended up rendering with no background at all.
- **`btn-outline-light` is a white outline** meant for dark backgrounds, invisible on the light
  theme. `btn-outline-secondary` is the neutral button in both.

Also: no `&apos;` inside a Thymeleaf expression. The HTML parser decodes entities *before*
Thymeleaf parses the attribute, so it reaches SpringEL as a bare apostrophe and closes the string
early — a parse error that only appears at render time.

### Agent scripts

The scripts live at the repository root and are copied into the jar by a `maven-resources-plugin`
execution. One canonical copy, not two.

> [!IMPORTANT]
> **The Dockerfile must copy the root-level agent scripts into the build context.** A context
> without them still builds — an `<include>` that matches nothing is not an error — but produces a
> jar with no agent scripts, so the console can no longer generate an installer. `mvn package` on
> the host works, so the failure only shows up in Docker, which is how this project actually runs.
> `AgentInstallController.verifyPackagedScripts()` logs an explicit error at startup if any is
> missing.

### Audit

`AuditService.record` truncates its details. `audit_events.details` is `VARCHAR(1024)`, and a long
failure message overflowed it — that second failure then masked the first, surfacing a handled
error as an unrelated 500. **An audit write must never break the operation it records.**

## Direction

The project is moving towards a managed, lightweight fleet AV: an agent per machine, talking to a
local `clamd` over a UNIX socket (so file-descriptor passing works and `clamd` needs neither root
nor a network port), reporting to the console over HTTPS with per-agent tokens. The direct-TCP
model is the older one and is kept for upload scans and for machines without an agent.
