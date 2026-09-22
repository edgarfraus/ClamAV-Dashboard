# Installation

## Requirements

- **Docker** with the Compose plugin. Nothing else — the application is compiled by Maven
  *inside* the build container, so the host needs no JDK.
- Roughly 2 GB of RAM for the `clamd` container. ClamAV loads its whole signature database into
  memory; this is the single biggest resource cost of the stack, and it is ClamAV's, not ours.

To build outside Docker you need **JDK 17+** and Maven; see [architecture.md](architecture.md).

## First run

```bash
git clone https://github.com/edgarfraus/ClaimAV-Dashboard.git
cd ClaimAV-Dashboard
docker compose -f docker-compose-mac.yml up --build -d
```

Then open <http://localhost:8080> and sign in with **`admin` / `admin`**.

The first start does three things on its own: it creates the database schema, creates that default
admin user, and seeds one `clamd` endpoint pointing at the bundled `clamav-server` container.

The first `clamd` start is slower than you expect — it downloads the signature database before it
accepts connections. Until it does, the endpoint shows as offline. Watch it with:

```bash
docker compose logs -f clamav-server
```

### Which compose file

| File | Use it when |
|---|---|
| `docker-compose-mac.yml` | Always, unless you need the case below. Publishes `8080:8080` and needs nothing pre-existing. The name is historical — nothing in it is macOS-specific. |
| `docker-compose.yml` | You want the console to have **its own address on your LAN** (a macvlan network named `lan` that you created yourself). It publishes no port and `up` fails if that network does not exist. |

To create the `lan` network for the second case, adapt this to your interface and subnet:

```bash
docker network create -d macvlan \
  --subnet=192.168.1.0/24 --gateway=192.168.1.1 \
  -o parent=eth0 lan
```

## What lives where

Three paths are bind-mounted from the repository into the container, so they survive a rebuild:

| Host path | In container | Holds |
|---|---|---|
| `./data` | `/app/data` | the H2 database — endpoints, users, jobs, alerts, audit |
| `./conf` | `/app/conf` | `clamav-web-client.properties`, the admin settings |
| `./scandir` | `/scandir` (read-only) | files you want reachable for path scans |

**Both are gitignored**, which means they do not travel with `git pull`. Moving an installation to
another machine means copying `data/` and `conf/` by hand.

> [!CAUTION]
> `./data` is the whole application state. Back it up before an upgrade. Deleting it resets the
> console to a fresh install, including recreating `admin`/`admin`.

## Making paths scannable

A path scan can only reach files the scanner can see, and there are **two** containers involved:

- for a **direct clamd** endpoint, the file must be visible to the `clamav-server` container;
- for the console's own validation and for watch directories, it must be visible to the
  **console** container too.

Mount them into both. The common mistake is mounting only one and getting a scan that reports a
file as missing while you can see it perfectly well on the host.

Paths also have to be allowed: see **Allowed scan roots** in [configuration.md](configuration.md).
With an agent-managed endpoint none of this applies — the agent scans the machine's own
filesystem, and nothing needs mounting anywhere.

## PostgreSQL instead of H2

H2 is the default and is a real file-backed database, fine for a single console. Switch to
PostgreSQL by setting three environment variables — the driver is already bundled:

```yaml
environment:
  - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/claimav
  - SPRING_DATASOURCE_USERNAME=claimav
  - SPRING_DATASOURCE_PASSWORD=...
```

The schema is created automatically. If you are migrating a database created by an old version,
`PostgresLobMigration` converts the two columns that used to be OID large objects into `TEXT` on
startup; it logs what it did and carries on if it cannot.

## Behind a reverse proxy

Set the proxy to forward the standard headers and the application picks up the public URL:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host  $host;
}
```

`server.forward-headers-strategy=framework` is already set. This matters more than it looks:
**the console URL baked into a generated agent installer is the URL you are browsing**, so
without those headers every installer you download will tell its agent to report to
`http://localhost:8080`.

Terminating TLS at the proxy is the expected setup — the application speaks plain HTTP. If your
proxy uses a private CA, the agents need to trust it; see [agents.md](agents.md).

## Upgrading

```bash
git pull
docker compose -f docker-compose-mac.yml up --build -d
```

The schema updates itself (`ddl-auto=update`, plus `SchemaFixup` for the constraint changes
Hibernate will not make on its own). Your data and settings are in the bind mounts and are not
touched.

After a UI upgrade, **force-reload the browser** (Ctrl+F5 / ⇧⌘R): the stylesheet keeps its name
across versions, so a cached copy produces a half-styled page.

## Uninstalling

```bash
docker compose -f docker-compose-mac.yml down -v    # -v also drops the signature database volume
```

`./data` and `./conf` are left behind on purpose. Delete them yourself if you mean it.
