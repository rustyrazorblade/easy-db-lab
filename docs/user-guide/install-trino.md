# Install Trino

The `install trino` command scaffolds a Trino deployment using the Trino Helm chart. It generates scripts and a `values.yaml` in a local `trino/` directory. Once installed, use `easy-db-lab trino start` and `easy-db-lab trino stop` to manage it.

Trino is stateless — it does not use persistent volumes. Queries run in-memory on app (`type=app`) nodes.

## Prerequisites

- Cluster is up (`easy-db-lab up`)
- App nodes are provisioned (at least one `ServerType.Stress` node)
- `kubectl` and `helm` are available on the control node
- Environment is sourced: `source env.sh`

## Quick Start

```bash
easy-db-lab kit install trino
easy-db-lab trino start
```

## Flags

| Flag | Default | Description |
|---|---|---|
| `--version` | `474` | Trino release version to deploy |
| `--workers` | app node count | Number of Trino worker pods |

## What Gets Generated

```
trino/
├── README.md                    # Usage instructions for this cluster
├── values.yaml                  # Helm values for the Trino chart
├── catalogs/
│   ├── cassandra.properties     # Cassandra connector config
│   ├── clickhouse.properties    # ClickHouse JDBC connector config
│   └── cqlite.properties        # Offline SSTable read path (see note below)
└── bin/
    ├── start.sh                 # Deploy sequence
    ├── stop.sh                  # Teardown sequence
    ├── uninstall.sh             # Remove Helm release
    └── update-catalogs.sh       # Re-renders Trino catalog config from running kits
```

## Managing Trino

After installation, use the CLI to start and stop:

```bash
# Deploy Trino
easy-db-lab trino start

# Stop Trino (scale to zero, no data loss)
easy-db-lab trino stop
```

## Automatic Catalog Management

Trino has built-in support for two catalogs that wire up automatically:

- **Cassandra** — uses the Cassandra connector, pointing at the cluster's Cassandra nodes
- **ClickHouse** — uses the ClickHouse JDBC connector, pointing at the cluster's ClickHouse nodes

When either of those kits starts or stops, Trino detects the change via a `post-workload-start`
/ `post-workload-stop` hook and re-renders its catalog configuration automatically. No restart
of Trino is needed.

```bash
# Start Cassandra — Trino wires up the cassandra catalog automatically
easy-db-lab cassandra start

# Start ClickHouse — Trino wires up the clickhouse catalog automatically
easy-db-lab kit install clickhouse
easy-db-lab clickhouse start
```

## The `cqlite` catalog (offline SSTable reads)

The trino kit also ships a `cqlite` catalog (`catalogs/cqlite.properties`) that reads
Cassandra SSTables **offline** — read-only, flushed-SSTables-only, and eventually stale
(never a live consistent view) — through the third-party `cqlite_flight` connector and the
[cqlite-flight](cqlite-flight.md) Arrow Flight data plane (one pod per db node). It is a
catalog property file of the trino kit, exactly like `cassandra` and `clickhouse` — not a
standalone kit and not a separate `kit install` command. When present and wired, it gives
`SELECT * FROM cqlite.<keyspace>.<table>` addressing, registered additively alongside the
`cassandra` catalog.

```admonish warning title="Plugin delivery deferred (pmcfadin/cqlite#2869)"
Unlike the `cassandra`/`clickhouse` connectors, `cqlite_flight` is **not** baked into the
`trinodb/trino` image, so the `cqlite` catalog only resolves once the connector plugin jar is
mounted into `/usr/lib/trino/plugin/cqlite_flight/` on the coordinator and workers. That
plugin is a self-contained fat jar to be published in the cqlite GitHub Releases, tracked in
`pmcfadin/cqlite#2869`. Until it lands, the catalog file ships as a staged template and the
plugin mount is intentionally not wired — registering the catalog without the plugin present
would crash the coordinator at boot with `No factory for connector 'cqlite_flight'`.
```

## Node Placement

Trino workers are scheduled on `type=app` nodes using `nodeSelector: type: app`.

## Connecting

After `easy-db-lab trino start` completes, the Trino coordinator is accessible within the cluster. Connect using the SOCKS5 proxy or a port-forward:

```bash
kubectl port-forward svc/trino 8080:8080
```

Then connect with any Trino-compatible client at `jdbc:trino://localhost:8080`.

You can also use the built-in SQL command:

```bash
easy-db-lab trino sql "SELECT count(*) FROM cassandra.mykeyspace.mytable"
```

## Adding Trino Catalogs for Custom Kits

If you install a custom kit via `--from` and want Trino to connect to it, drop a
`trino-catalog.properties` file in the kit's installed directory:

```
my-kit/
├── bin/
│   ├── start.sh
│   └── stop.sh
└── trino-catalog.properties    # Trino picks this up automatically
```

The file follows the standard [Trino connector properties](https://trino.io/docs/current/connector.html) format:

```properties
connector.name=postgresql
connection-url=jdbc:postgresql://localhost:5432/mydb
connection-user=trino
connection-password=
```

`update-catalogs.sh` scans all sibling kit directories for this file. When found, the catalog
is added to Trino's configuration under a name matching the kit directory name. No restart of
Trino is required — the script runs `helm upgrade` with the merged catalog values.

## Presto vs Trino

Trino is the open-source fork of Presto (previously known as PrestoSQL). Both are supported as independent kits. Choose Trino for the active open-source community and Presto for compatibility with Meta's Presto ecosystem.
