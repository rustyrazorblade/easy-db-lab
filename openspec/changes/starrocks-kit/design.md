## Context

easy-db-lab kits are purely declarative: a `kit.yaml` defines lifecycle steps (install / start / stop / uninstall), args, endpoints, metrics, and capabilities. No Kotlin source changes are needed for a new kit unless it requires a new capability type or step type. StarRocks introduces no new capability types — it uses existing `helm-repo`, `helm`, `manifest`, `platform-pvs`, `shell`, `delete`, and `helm-uninstall` step types, plus the existing `sql` capability.

The key architectural distinction of StarRocks is its split FE/BE topology: Frontend nodes handle query planning and metadata; Backend nodes handle storage and computation. StarRocks documentation requires these to run on separate machines. easy-db-lab's `app`/`db` node split maps naturally to this requirement.

## Goals / Non-Goals

**Goals:**
- Deliver a working StarRocks kit with install / start / stop / uninstall
- FE pods land on `app` nodes; BE pods land on `db` nodes
- BE and FE replica counts driven by `DB_NODE_COUNT` and `APP_NODE_COUNT` respectively
- SQL access via the `sql` capability (MySQL JDBC, user `root`)
- NodePort services on 9030 (MySQL) and 8030 (HTTP/UI)
- Metrics scraping from FE Prometheus endpoint
- Fail-fast shell guard if app node count is zero at start time

**Non-Goals:**
- Backup / restore (deferred to a follow-on)
- Grafana dashboard (deferred)
- Granular node sub-assignment within the `db`/`app` pools (tracked in #672)
- StarRocks CN (Compute Node) mode

## Decisions

### Use `kube-starrocks` Helm chart (operator-based)

**Decision**: Deploy via the `starrocks/kube-starrocks` Helm chart from `https://starrocks.github.io/starrocks-kubernetes-operator`. This chart installs the StarRocks Kubernetes Operator and optionally creates a `StarRocksCluster` CR in one shot.

**Alternatives considered**:
- Plain manifest YAML: more brittle, harder to upgrade, no operator lifecycle management.
- Operator only + manual CR: extra complexity with no benefit for lab use.

**Rationale**: Matches the ClickHouse pattern (Altinity operator via Helm + CR). The chart is the official deployment path and handles upgrades cleanly.

### FE → `app` nodes, BE → `db` nodes

**Decision**: Set `nodeSelector: {type: app}` on FE pods and `nodeSelector: {type: db}` on BE pods in the `StarRocksCluster` manifest.

**Alternatives considered**:
- Both on `db` nodes: simpler, but violates StarRocks' separation requirement and conflates storage/compute nodes with coordinator nodes.
- No affinity: pods float freely, acceptable for single-node dev but wrong at scale.

**Rationale**: StarRocks docs state FE and BE must be on separate machines. The `app`/`db` split is the natural easy-db-lab expression of this. FE is lighter (coordinator, metadata) — fits app nodes. BE is storage/compute-intensive — fits NVMe-backed db nodes.

### Use `DB_NODE_COUNT` / `APP_NODE_COUNT` directly, no replica kit args

**Decision**: The `StarRocksCluster` template uses `__DB_NODE_COUNT__` for BE replicas and `__APP_NODE_COUNT__` for FE replicas. No `--fe-replicas` or `--be-replicas` kit args are defined.

**Alternatives considered**:
- `FE_REPLICAS`/`BE_REPLICAS` args defaulting to node counts: adds flexibility to run fewer pods than nodes, but uses StarRocks-specific naming that won't generalize to other split-role databases.
- Generic `DB_REPLICAS`/`APP_REPLICAS` args: more flexible, but premature — no current need to run fewer replicas than nodes.

**Rationale**: Simpler. Node counts are already injected and always available. When #672 lands (granular node sub-assignment), this decision will be revisited.

### MySQL JDBC driver dependency

**Decision**: Add `mysql-connector-j` as a runtime dependency in `build.gradle.kts` so the `sql` capability can load `com.mysql.cj.jdbc.Driver`.

**Rationale**: The `sql` capability uses JDBC. StarRocks speaks MySQL protocol. The driver must be on the classpath. This is the same pattern as ClickHouse (uses its own driver) and Presto (uses the Presto JDBC driver).

## Risks / Trade-offs

- **App node count = 0 at start time** → Mitigated by shell guard that fails fast with a clear message before any manifests are applied.
- **StarRocks operator version drift** → The Helm chart pins the operator version. Users can override with `--version`. No mitigation needed for lab use.
- **MySQL JDBC driver version compatibility** → StarRocks is compatible with standard MySQL JDBC. Use the latest stable `mysql-connector-j`. Risk is low.
- **BE storage**: BE pods require persistent storage. The `platform-pvs` step creates local PVs on db nodes before the CR is applied, matching the ClickHouse pattern exactly.

## Open Questions

- What NodePort port numbers to assign? 9030 (MySQL) and 8030 (HTTP) are the StarRocks defaults — these can be used directly as NodePort values if not already claimed by another kit.
- Does the FE Prometheus metrics endpoint require authentication? Likely not by default, but should be confirmed against the Helm chart defaults during implementation.
