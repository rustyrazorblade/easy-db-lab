# cqlite-trino

The `cqlite-trino` kit is an overlay for the [Trino](install-trino.md) kit. It loads the
CQLite Trino connector plugin into the already-running Trino coordinator and worker pods
and registers a catalog named `cqlite`, so you can run
`SELECT * FROM cqlite.<keyspace>.<table>` against your database's SSTables — read through
the [cqlite-flight](cqlite-flight.md) Arrow Flight data plane, with no live database read
path involved.

```admonish note title="Directory name vs catalog name"
The install subcommand is `kit install cqlite-trino` (from the resource directory name),
but the installed kit and the Trino catalog it registers are both named `cqlite` (from
the kit's `name:`), giving the clean `cqlite.<keyspace>.<table>` addressing.
```

```admonish warning title="Offline, read-only, flushed-SSTables-only view"
Queries against the `cqlite` catalog read **flushed SSTable files**, not the live
database:

- **Read-only / offline.** No live read/write path is involved; writes not yet flushed
  from memtables are invisible.
- **Flushed-only.** Run `nodetool flush` on the target keyspace first if you need recent
  writes to appear in query results.
- **Eventually stale.** Compaction and new flushes change the on-disk file set over time,
  so a query reflects whatever SSTables existed when it ran — not a single consistent
  cluster snapshot. In `snapshot` read mode each query reads a consistent per-query
  Sidecar snapshot of the file set; in `live` mode it reads the current data dir and
  races compaction. Neither mode is a live, consistent view of the database.
```

## Prerequisites

- Cluster is up (`easy-db-lab up`)
- The [Trino](install-trino.md) kit is installed and running — `cqlite-trino` fails fast
  with an error naming the missing dependency if Trino is not up
- The [cqlite-flight](cqlite-flight.md) kit is running on the db nodes (the data plane the
  connector reads from)
- A reachable Cassandra Sidecar URI (`--sidecar-uri`)

## Quick Start

```bash
easy-db-lab kit install trino
easy-db-lab trino start

easy-db-lab kit install cqlite-flight
easy-db-lab cqlite-flight start

# Register the cqlite catalog into the running Trino
easy-db-lab kit install cqlite-trino --sidecar-uri http://$(easy-db-lab ip db0 --private):9043
easy-db-lab cqlite start

# cqlite now appears alongside the existing cassandra catalog
easy-db-lab trino sql "SHOW CATALOGS"
easy-db-lab trino sql "SELECT * FROM cqlite.my_keyspace.my_table LIMIT 10"
```

The catalog is registered **additively** — the existing `cassandra` catalog remains
present and unchanged.

## Flags

| Flag | Default | Description |
|---|---|---|
| `--connector-version` | `0.13.0` | cqlite-trino connector version from Maven Central (`in.mcfad:cqlite-trino:<version>`) |
| `--flight-port` | `8815` | Arrow Flight gRPC port cqlite-flight listens on (must match the cqlite-flight kit's `--flight-port`) |
| `--sidecar-uri` | (required) | Cassandra Sidecar base URI, e.g. `http://<db-node-private-ip>:9043`. Get an IP with `easy-db-lab ip db0 --private` |
| `--local-datacenter` | (unset) | Optional preferred datacenter for split placement |
| `--read-mode` | `snapshot` | How the scan resolves the SSTable file set: `snapshot` (consistent per-query Sidecar snapshot) or `live` (current data dir, races compaction) |
| `--trino-image-tag` | `481` | Trino image tag the running trino kit must be pinned to for SPI compatibility; used to fail fast on a mismatch |

## Lifecycle

```bash
easy-db-lab cqlite start       # load the plugin and register the catalog
easy-db-lab cqlite status      # show catalog registration state
easy-db-lab cqlite stop        # no-op — the plugin is wired into the Trino Helm values
easy-db-lab cqlite uninstall   # remove all kit resources and deregister the catalog
```

Stop/uninstall remove the `cqlite` catalog and return the catalog list and Trino pods to
their prior condition; the `cassandra` catalog is left in place.

## Related kits

- [cqlite-flight](cqlite-flight.md) — the Arrow Flight data plane this connector reads
  from. Must be running on the db nodes.
- [trino-loadtest](trino-loadtest.md) — drives concurrent read load against the `cqlite`
  catalog.
