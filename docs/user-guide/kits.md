# Kits

A kit is a self-contained package of configuration and scripts that installs, starts, stops,
and optionally backs up a workload on your cluster. Each kit defines its full lifecycle in a
`kit.yaml` file using typed steps — no Kubernetes YAML wrangling required.

easy-db-lab ships with built-in kits (ClickHouse, Presto, Trino, TiDB, sysbench, and the
cqlite offline-query kits). You can also create your own kits for any workload you want to
benchmark or test.

### Offline SSTable query kits (cqlite)

Three built-in kits work together to query a database's SSTables offline — read-only,
flushed-SSTables-only, and eventually stale (never a live consistent view):

- [cqlite-flight](cqlite-flight.md) — Arrow Flight data plane, one pod per db node,
  reading local SSTables.
- [cqlite-trino](cqlite-trino.md) — registers a `cqlite` Trino catalog for
  `SELECT * FROM cqlite.<keyspace>.<table>` addressing.
- [trino-loadtest](trino-loadtest.md) — drives concurrent read load against that catalog.

## Discovering kits

List all available kits:

```bash
easy-db-lab kit list
```

Inspect a kit before installing it — see its args, endpoints, and available commands:

```bash
easy-db-lab kit info clickhouse
```

## Installing a kit

```bash
easy-db-lab kit install clickhouse --clickhouse-version 25.4 --size 100Gi
```

Args vary by kit. Run `kit info <name>` to see what a kit accepts, or pass `--help`:

```bash
easy-db-lab kit install clickhouse --help
```

After install, the kit's files are written into a subdirectory of the cluster workspace.
The kit's lifecycle commands are registered automatically.

## Bench kits — benchmarking a database

Bench kits are a special class of kit that run against an already-running database kit. They
require a `--target` flag pointing at the installed database kit you want to benchmark.

```bash
# Install sysbench targeting your running TiDB instance
easy-db-lab kit install sysbench --target tidb

# Run the prepare, start, and stop lifecycle as usual
easy-db-lab sysbench-tidb prepare
easy-db-lab sysbench-tidb start
easy-db-lab sysbench-tidb stop
```

The kit is installed into a directory named `<bench-kit>-<target>` (e.g. `sysbench-tidb`).
This lets you run the same bench kit against multiple databases simultaneously and compare results:

```bash
easy-db-lab kit install sysbench --target tidb
easy-db-lab kit install sysbench --target my-custom-db

# Both run at the same time — compare results in Grafana
easy-db-lab sysbench-tidb start
easy-db-lab sysbench-my-custom-db start
```

The target must expose a wire protocol endpoint the bench tool can speak — sysbench
supports MySQL and PostgreSQL. See [Sysbench](sysbench.md) for the full lifecycle,
flags, and metrics.

### TARGET_* environment variables

When a bench kit starts, easy-db-lab reads the target database's endpoint configuration and
injects it as environment variables into every phase script:

| Variable | Description |
|----------|-------------|
| `TARGET_JDBC_URL` | Full JDBC connection URL (e.g. `jdbc:clickhouse://10.0.1.5:8123/default`) |
| `TARGET_JDBC_USER` | Database username for JDBC connections |
| `TARGET_JDBC_DRIVER` | Fully-qualified JDBC driver class name |
| `TARGET_PG_HOST` | Host for PostgreSQL wire protocol connections |
| `TARGET_PG_PORT` | Port for PostgreSQL wire protocol connections |
| `TARGET_PG_USER` | Username for PostgreSQL wire protocol connections |
| `TARGET_PG_DATABASE` | Database name for PostgreSQL wire protocol connections |
| `TARGET_MYSQL_HOST` | Host for MySQL wire protocol connections |
| `TARGET_MYSQL_PORT` | Port for MySQL wire protocol connections |
| `TARGET_MYSQL_USER` | Username for MySQL wire protocol connections |
| `TARGET_MYSQL_DATABASE` | Database name for MySQL wire protocol connections |
| `TARGET_HTTP_URL` | Full URL for HTTP endpoint connections |

Which variables are populated depends on what endpoints the target kit declares. A kit that
supports both JDBC and PostgreSQL wire protocol will populate both sets.

## Running kit commands

Every installed kit gains a set of subcommands:

```bash
easy-db-lab clickhouse start       # deploy and start the workload
easy-db-lab clickhouse status      # show running state and connection endpoints
easy-db-lab clickhouse stop        # stop and remove the workload
easy-db-lab clickhouse backup --name my-backup   # back up data
easy-db-lab clickhouse restore --name my-backup  # restore from backup
easy-db-lab clickhouse uninstall   # stop and remove all kit resources
```

## Installing a custom kit

Place your kit directory under the profile kits folder and it will appear in `kit list` and be
installable by name like any built-in kit:

```
~/.easy-db-lab/profiles/default/kits/<kit-name>/
```

Custom kits in the profile directory take precedence over built-in kits with the same name.

```bash
mkdir -p ~/.easy-db-lab/profiles/default/kits/my-kit
cp -r /path/to/my-kit/* ~/.easy-db-lab/profiles/default/kits/my-kit/

# Now it appears in kit list and can be installed by name:
easy-db-lab kit install my-kit
```

## Using kits from external projects

If you keep kit definitions alongside a private project (a POC, internal tooling, etc.), you can
register that project's kits directory without copying files into your profile.

A typical project structure looks like this:

```
myapp/
├── src/
├── kits/
│   └── myapp-workload/
│       ├── kit.yaml
│       └── bin/
│           ├── start.sh
│           └── stop.sh
└── README.md
```

Clone your project and register the kits directory by name:

```bash
git clone https://github.com/myorg/myapp ~/myapp
easy-db-lab kit source add myapp ~/myapp/kits
```

The kits it contains now appear in `kit list` and can be installed by name:

```bash
easy-db-lab kit list
easy-db-lab kit install myapp-workload
```

Registered sources are persisted in `~/.easy-db-lab/profiles/<profile>/kit-sources.yaml` and
survive CLI restarts. When you `kit install` a kit from an external source, its files are copied
into the cluster workspace exactly like any other kit — the installed kit is self-contained.

### Managing registered sources

```bash
# List all registered sources (shows name and path, flags missing paths)
easy-db-lab kit source list
```

Output looks like:

```
Registered kit sources:
  myapp  /Users/jon/myapp/kits
```

If a registered path no longer exists on disk, `[missing]` appears next to it so you know
which sources need attention.

```bash
# Remove a source by name
easy-db-lab kit source remove myproject
```

**Updating a path (upsert behavior):** Sources are identified by name. If you move or reclone
a project to a different location, just re-add the source with the new path — no need to remove
the old registration first:

```bash
# If you move or reclone the project, just update the path — no need to remove first
easy-db-lab kit source add myapp ~/new-location/myapp/kits
# Updated kit source 'myapp': /new-location/myapp/kits
```

### Resolution priority

When multiple sources provide a kit with the same name, the first match wins:

1. Profile kits directory (`~/.easy-db-lab/profiles/<profile>/kits/`)
2. Registered additional sources (in registration order)
3. Built-in kits

For a full walkthrough of building and publishing your own kit, see the [Kit Development](../development/kits.md) guide.
