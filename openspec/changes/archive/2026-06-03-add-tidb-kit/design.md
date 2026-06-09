## Context

easy-db-lab kits are fully declarative — a `kit.yaml` file drives install, start, stop, uninstall, metrics, and SQL capability registration with no per-kit Kotlin code required. The existing ClickHouse kit (Altinity operator) establishes the pattern for operator-managed databases: install the operator via Helm, then apply a CRD instance to create the cluster.

TiDB follows the same pattern: PingCAP's TiDB Operator is installed via Helm, then a `TidbCluster` CRD is applied. All four TiDB components (PD, TiDB, TiKV, TiFlash) are declared within a single CRD manifest.

## Goals / Non-Goals

**Goals:**
- Declarative TiDB HTAP kit with no new Kotlin code
- TiFlash (columnar) included as a first-class component, not optional
- MySQL-compatible `sql` capability via existing kit capability system
- Prometheus metrics scraping from the TiDB native metrics endpoint
- Pre-flight validation that enforces the mixed-cluster requirement

**Non-Goals:**
- TiDB Dashboard UI exposure (PD dashboard on port 2379 — out of scope for initial kit)
- TiCDC (change data capture) — out of scope
- TiDB Lightning (bulk import) — out of scope
- Backup/restore — out of scope for initial version

## Decisions

### 1. TiDB Operator via Helm (not TiUP playground)

TiUP playground is a development convenience tool, not Kubernetes-native. TiDB Operator is the only production-grade Kubernetes deployment method. It manages component lifecycle, rolling upgrades, and scaling via CRDs — consistent with how ClickHouse uses the Altinity operator.

### 2. TiKV and TiFlash share a `--replicas` arg

Both components are stateful and run on db nodes. Separating their replica counts adds complexity with no practical benefit for lab use. A single `--replicas` arg (defaulting to `DB_NODE_COUNT`) drives both.

### 3. PD replicas hardcoded to 1

PD requires an odd number (1 or 3) for Raft consensus. For lab use, 1 is sufficient. Exposing this as an arg would invite misconfiguration (even numbers break consensus). Users who need HA can fork the manifest.

### 4. TiDB SQL replicas derived from `APP_NODE_COUNT`

TiDB SQL is stateless and should saturate available app nodes. Hardcoding this to `${APP_NODE_COUNT}` in the manifest is correct for lab use and avoids an unnecessary arg.

### 5. Storage hardcoded to 10Ti

Local PVs in easy-db-lab use the node's physical disk — the `storageSize` field in the PV/PVC spec is metadata only, not a provisioned allocation. Hardcoding `10Ti` means users never need to think about sizing. This is identical to the ClickHouse kit's default.

### 6. MySQL Connector/J as the JDBC driver

TiDB is wire-compatible with MySQL 5.7+. `com.mysql:mysql-connector-j` is the official MySQL driver and works with TiDB without modification. The `sql` capability's `driver-class: com.mysql.cj.jdbc.Driver` ensures it is loaded before the JDBC connection.

### 7. Pre-flight check as a shell step

No built-in node-count enforcement mechanism exists in the kit runner. A shell step at the top of `start:` that checks `DB_NODE_COUNT` and `APP_NODE_COUNT` fails fast with a clear error message before any K8s resources are applied.

## Risks / Trade-offs

- **TiDB Operator version drift** → Pin the Helm chart version in `kit.yaml`; update when testing against new TiDB releases.
- **TiFlash resource requirements** → TiFlash is CPU and memory intensive. Lab instances need sufficient resources (recommend ≥16GB RAM per db node). No guardrail in the kit — users are responsible for instance sizing.
- **MySQL driver version compatibility** → TiDB tracks MySQL 5.7/8.0 protocol. Pin `mysql-connector-j` to a known-good version and test against the default TiDB version.
- **NodePort port collision** → Port 4000 (MySQL) must not conflict with other running kits. The `collision-check: true` flag handles this via the existing kit collision detection system.

## Open Questions

- Should the TiDB Dashboard (PD web UI on port 2379) be exposed as an endpoint in a future iteration?
