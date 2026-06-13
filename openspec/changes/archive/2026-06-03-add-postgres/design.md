## Context

easy-db-lab has two database kits (Cassandra, ClickHouse) and one app kit (Presto). Both database kits use a K8s operator pattern: install an operator via Helm, then manage a custom resource (CR) for the actual cluster. PostgreSQL follows this same pattern using CloudNativePG (CNPG).

The SQL capability (introduced in the `kit-capabilities` change) lets users run `<kit> sql "<query>"` against any kit that declares a `jdbc` endpoint and `sql` capability. ClickHouse already uses this. PostgreSQL adds a second implementation with zero changes to the capability framework.

Presto catalog injection works via a hook: when any workload starts or stops, Presto's `update-catalogs.sh` scans `RUNNING_KITS` and injects a `.properties` file per running kit. Adding postgres requires only a new `presto/catalogs/postgres.properties.template` file.

## Goals / Non-Goals

**Goals:**
- Deploy PostgreSQL via CNPG on K8s db nodes
- Support configurable instance count (default 1, enables primary + read replicas)
- Stop preserves data (PVs survive); uninstall cleans up
- `postgres sql` command via JDBC
- Presto sees `postgres` catalog when both kits are running
- Consistent flag naming: `--version`, `--instances`, `--size`

**Non-Goals:**
- Backup/restore (deferred)
- Custom database/user creation beyond defaults
- TLS/SSL configuration
- Connection pooling (PgBouncer)

## Decisions

### D1: CloudNativePG over alternatives

**Decision**: Use CloudNativePG (CNPG) operator.

**Alternatives considered**:
- *Bitnami Helm chart*: Simple but opinionated; no operator pattern, harder to manage lifecycle.
- *Zalando Postgres Operator*: Battle-tested but heavier; CRD surface is larger than needed.
- *Bare StatefulSet*: Maximum control but requires hand-rolling all operator logic (failover, replication setup).

**Rationale**: CNPG is the closest analogue to Altinity for ClickHouse — an operator that manages a `Cluster` CRD. It exports Prometheus metrics natively on port 9187, supports configurable instances, and has a clean Helm install path. Active CNCF project with frequent releases.

### D2: NodePort assignment

**Decision**: `30432` for PostgreSQL (mirrors 5432 with 3-prefix), `30987` for metrics (mirrors 9187 with 3-prefix).

**Rationale**: In-use NodePorts are 30123, 30900, 30936. The 3-prefix pattern mirrors service ports and is easy to remember.

### D3: Credentials

**Decision**: user=`postgres`, password=`postgres`, db=`postgres`.

**Rationale**: Test clusters only. Simple, memorable. CNPG creates a `postgres` superuser by default; we inject the password via a K8s Secret referenced by the Cluster CR.

### D4: CNPG service naming

**Decision**: Presto catalog uses `postgres-rw.default.svc.cluster.local:5432`.

**Rationale**: CNPG creates `<cluster>-rw` (primary read-write) and `<cluster>-ro` (replicas) services automatically. The Cluster CR name will be `postgres`, so the primary service is `postgres-rw`. Presto should always write to the primary.

### D5: PostgreSQL JDBC driver

**Decision**: Add `org.postgresql:postgresql` to `gradle/libs.versions.toml` and `build.gradle.kts`.

**Rationale**: Same pattern as ClickHouse (`com.clickhouse:clickhouse-jdbc`) and Presto (`com.facebook.presto:presto-jdbc`). The PostgreSQL driver auto-registers via `ServiceLoader`, so `driver-class` in kit.yaml can be left empty.

### D6: ClickHouse flag rename

**Decision**: Rename `--clickhouse-version` to `--version` in `clickhouse/kit.yaml`.

**Rationale**: Consistency — all kits should use `--version` for their version arg. This is a cosmetic change with no behavior impact. Already applied to kit.yaml and KitInfoTest.kt.

## Risks / Trade-offs

- **CNPG Helm chart availability**: The CNPG Helm repo (`cloudnative-pg.github.io/charts`) must be reachable from the control node at install time. [Risk: offline/air-gapped clusters] → Mitigation: same risk exists for all Helm-based kits; not new.

- **PV reattachment on start**: CNPG reattaches existing PVs by matching the `Cluster` CR name. If the CR name changes between stop/start, data will not be found. [Risk: user renames cluster] → Mitigation: the CR name is hardcoded to `postgres` in the template, not user-configurable.

- **Instance count and PVs**: Each instance gets its own PV. If `--instances` decreases between stop/start, orphaned PVs are left behind. [Risk: wasted storage] → Mitigation: `platform-pvs-delete` on uninstall cleans all PVs; acceptable for test environments.

## Open Questions

- None — all design decisions resolved during explore phase.
