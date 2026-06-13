# Install PostgreSQL

The `postgres` kit deploys PostgreSQL on K8s db nodes via the [CloudNativePG](https://cloudnative-pg.io/) (CNPG) operator. Data is persisted on PersistentVolumes — stopping and restarting preserves your dataset.

## Prerequisites

- Cluster is up (`easy-db-lab up`) with at least one db node

## Quick Start

```bash
easy-db-lab kit install postgres
easy-db-lab postgres start
```

## Flags

| Flag | Default | Description |
|---|---|---|
| `--version` | `17` | PostgreSQL major version (e.g. `17`, `16`) |
| `--instances` | `1` | Number of PostgreSQL instances; values > 1 deploy a primary and read replicas |
| `--size` | `10Ti` | Storage size per db node (e.g. `100Gi`) |

## Managing PostgreSQL

```bash
# Start PostgreSQL
easy-db-lab postgres start

# Stop PostgreSQL (data is preserved)
easy-db-lab postgres stop

# Remove operator and delete all PersistentVolumes
easy-db-lab postgres uninstall
```

### start

1. Creates PersistentVolumes on db nodes via `platform-pvs`
2. Applies the CNPG `Cluster` custom resource
3. Waits for all pods to reach `Ready`
4. Applies the NodePort service for external access

### stop

Deletes the CNPG `Cluster` CR and the NodePort service. PersistentVolumes are retained — running `postgres start` again resumes from the existing dataset.

### uninstall

Deletes PersistentVolumes and uninstalls the CNPG operator Helm release.

## Connecting

The PostgreSQL primary is exposed as a NodePort on port `30432` of each db node.

```bash
# JDBC URL
jdbc:postgresql://<db-node-ip>:30432/postgres

# psql via SOCKS5 proxy or port-forward
kubectl port-forward svc/postgres-nodeport 5432:5432
psql -h localhost -U postgres postgres
```

The default user is `postgres` with password `postgres` (stored in the `postgres-credentials` K8s Secret).

## Running SQL

Use the built-in `sql` capability to execute queries directly:

```bash
easy-db-lab postgres sql "SELECT version()"
easy-db-lab postgres sql --file query.sql
```

## Extensions

PostgreSQL extensions that require custom container images are activated at **install time** via `--extension` on `kit install postgres`. Each extension installs as a separate named instance (e.g. `postgres-duckdb`) that starts and stops independently.

### Listing available extensions

```bash
easy-db-lab postgres extensions
```

Outputs a table of alias names, image templates, shared_preload_libraries, and CREATE EXTENSION statements.

### Built-in aliases

| Alias | Description |
|---|---|
| `duckdb` | DuckDB analytical query engine via pg_duckdb |
| `postgis` | Geospatial types and functions |
| `timescaledb` | Time-series storage and query optimization |

### Example

```bash
# Install with an extension
easy-db-lab kit install postgres --extension duckdb

# Start, stop, and use the named instance
easy-db-lab postgres-duckdb start
easy-db-lab postgres-duckdb sql "SELECT duckdb_version()"
easy-db-lab postgres-duckdb stop
```

### Versioning

Built-in alias images use `__PG_MAJOR__` as a placeholder for the PostgreSQL major version (e.g. `17`). This is substituted automatically from the `--version` flag set at install time.

## Presto Integration

When both `postgres` and `presto` are running, Presto automatically exposes a `postgres` catalog using the PostgreSQL JDBC connector pointed at the CNPG primary service. No manual configuration is needed.

```bash
easy-db-lab kit install postgres
easy-db-lab postgres start

easy-db-lab kit install presto
easy-db-lab presto start

# postgres catalog is available automatically
easy-db-lab presto sql "SHOW CATALOGS"
```
