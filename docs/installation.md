# Installation

## Requirements

- **Docker** with the Compose plugin. Nothing else — the application is compiled by Maven
  *inside* the build container, so the host needs no JDK.
- Roughly 2 GB of RAM for the `clamd` container. ClamAV loads its whole signature database into
  memory; this is the single biggest resource cost of the stack, and it is ClamAV's, not ours.

To build outside Docker you need **JDK 17+** and Maven; see [architecture.md](architecture.md).

## First run

```bash
git clone https://github.com/edgarfraus/ClamAV-Dashboard.git
cd ClamAV-Dashboard
docker compose up --build -d
```

Then open <http://localhost:8080> and sign in with **`admin` / `admin`**. You are taken straight
to **Change password**, and nothing else (the API included) works for that account until you
choose a new one; see [security](security.md#the-forced-password-change).

The first start does three things on its own: it creates the database schema, creates that default
admin user, and seeds one `clamd` endpoint pointing at the bundled `clamav-server` container.

The first `clamd` start is slower than you expect — it downloads the signature database before it
accepts connections. Until it does, the endpoint shows as offline. Watch it with:

```bash
docker compose logs -f clamav-server
```

### Giving the console its own LAN address

By default the console is reached through a published port on the host. To give it an address of
its own on your network instead, create the network once:

```bash
docker network create -d macvlan \
  --subnet=192.168.1.0/24 --gateway=192.168.1.1 \
  -o parent=eth0 my-lan
```

then name it in a `.env` file and add the override:

```bash
echo 'LAN_NETWORK=my-lan' > .env
docker compose -f docker-compose.yml -f docker-compose.lan.yml up -d
```

`.env` is gitignored on purpose. The network name used to be written into the compose file itself,
which meant every host carried a local edit to a tracked file and every `git pull` collided with
it.

> [!NOTE]
> A macvlan container is **not reachable from its own host** by default. If a reverse proxy runs on
> the same machine, either put the proxy on that network too or keep the published port.

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
docker compose up --build -d
```

The schema updates itself (`ddl-auto=update`, plus `SchemaFixup` for the constraint changes
Hibernate will not make on its own). Your data and settings are in the bind mounts and are not
touched.

After a UI upgrade, **force-reload the browser** (Ctrl+F5 / ⇧⌘R): the stylesheet keeps its name
across versions, so a cached copy produces a half-styled page.

## Uninstalling

```bash
docker compose down -v    # -v also drops the signature database volume
```

`./data` and `./conf` are left behind on purpose. Delete them yourself if you mean it.
