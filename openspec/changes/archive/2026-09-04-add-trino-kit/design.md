## Context

easy-db-lab has a Presto kit that deploys Presto via the `prestodb/presto` Helm chart onto app nodes. Trino is the open-source fork of Presto (forked in 2019 as PrestoSQL, renamed Trino in 2021). The two projects have diverged enough that sharing kit code would create maintenance risk — they use different Helm charts, different deployment names, and different JDBC driver classes.

The existing Presto kit provides the implementation blueprint. The catalog-hook system (`post-workload-start` / `post-workload-stop`) is already in place and functional; Trino just needs its own kit that participates in it.

## Goals / Non-Goals

**Goals:**
- Deliver a fully independent Trino kit mirroring the Presto kit's structure
- Wire catalog hooks for Cassandra and ClickHouse connectors
- Support `--version` flag for Trino release selection
- Provide `trino sql` command via the `sql` capability
- Rename `--clickhouse-version` to `--version` in the ClickHouse kit

**Non-Goals:**
- Shared base between Presto and Trino kits — deliberate decision to avoid divergence headaches
- Trino-specific Grafana dashboards (can be added later)
- Trino metrics-catalog.json beyond a placeholder (metrics shape TBD once deployed)

## Decisions

### 1. Fully independent kit (no shared code)

**Decision**: Two separate kits with no shared scripts or templates.

**Rationale**: Presto and Trino already diverge on Helm chart structure, deployment names, JDBC drivers, and catalog property conventions. A shared base would require conditional logic throughout every script. Independent kits are easier to reason about and modify independently.

**Alternatives considered**: A base `update-catalogs.sh` parameterised by engine name — rejected because the catalog property format differs between engines and the complexity would grow with every new connector.

### 2. Catalog connector file naming

**Decision**: Trino's hook script scans for `trino-catalog.properties` in sibling kit directories (analogous to Presto's scan for `presto-catalog.properties`).

**Rationale**: This is the existing extensibility mechanism for custom workloads installed via `--from`. Consistent naming makes the pattern discoverable. Built-in connector support (Cassandra, ClickHouse) lives in `kits/trino/catalogs/` and is added based on `RUNNING_KITS`.

### 3. Metrics port

**Decision**: Scrape port 8080 (Trino's built-in JMX metrics endpoint at `/v1/jmx/mbean`).

**Rationale**: Trino exposes Prometheus-compatible metrics at port 8080. No separate JMX exporter needed (unlike Presto which uses port 9090).

### 4. `--version` flag standardisation

**Decision**: Rename `--clickhouse-version` to `--version` in the ClickHouse kit, and use `--version` from the start in Trino.

**Rationale**: All versioned kits should use the same flag name. `--clickhouse-version` is redundant — the subcommand already establishes context.

**Impact**: Breaking change to ClickHouse install CLI. Acceptable — clusters are ephemeral, no existing installs to migrate.

### 5. Pyroscope profiling

**Decision**: Wire Pyroscope the same way as Presto — patch coordinator and worker deployments after Helm install to inject the Java agent as a `JAVA_TOOL_OPTIONS` env var with a host-path volume.

**Rationale**: Trino is a JVM workload; the same profiling approach applies. The Pyroscope agent JAR is baked into the AMI at `/usr/local/pyroscope/`.

## Risks / Trade-offs

- **Trino Helm chart API changes** → Mitigation: pin to a specific chart version in `values.yaml`; the `--version` flag controls the Trino app version independently.
- **Catalog property differences accumulate over time** → Acceptable: independent kits make this easy to address per-kit without cross-contamination.
- **ClickHouse `--version` rename breaks existing scripts** → Acceptable: ephemeral clusters; no long-lived deployments.

## Migration Plan

No migration needed. Clusters are ephemeral. Existing Presto kit is unchanged. ClickHouse flag rename takes effect on the next `kit install clickhouse` invocation.
