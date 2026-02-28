## Context

The cluster OTel Collector runs as a K8s DaemonSet on all nodes (control, db, app). It collects host metrics and Prometheus scrapes via the `metrics/local` pipeline and exports to VictoriaMetrics with `resource_to_telemetry_conversion`, which flattens resource attributes into metric labels (e.g., `host.name` → `host_name`).

Spark/EMR nodes run their own OTel Collector with a `resource/role` processor that stamps `node_role: spark`. These metrics arrive at the control node via the `metrics/otlp` pipeline, which passes them through untouched.

Grafana dashboards filter by `node_role` and `host_name`. Since locally-collected metrics lack `node_role`, db and app nodes are invisible to the service/hostname filters.

K3s agent nodes already receive K8s node labels during join: `type=db` for Cassandra nodes, `type=app` for stress nodes. The control node (K3s server) has `node-role.kubernetes.io/control-plane` but no `type` label.

## Goals / Non-Goals

**Goals:**
- All cluster nodes (db, app, control) emit metrics and logs with a `node_role` resource attribute
- The attribute value matches the existing K8s node label convention (`db`, `app`, `control`)
- Grafana dashboard hostname/service filters show all node types
- No changes to the Spark/EMR OTel pipeline

**Non-Goals:**
- Changing Grafana dashboard queries (they already filter on `node_role` — they just need the data)
- Adding `node_role` to traces (traces use the `traces` pipeline which receives OTLP and doesn't need local enrichment)
- Modifying K3s agent label assignment for db/app nodes (already correct)

## Decisions

### Use k8sattributes processor over transform/role regex

**Decision:** Use the OTel `k8sattributes` processor to read K8s node labels rather than the `transform` processor with regex on `host.name`.

**Alternatives considered:**
- `transform` processor with `IsMatch(attributes["host.name"], "^db\\d+")` — derives role from hostname pattern. Simpler (no RBAC needed) but fragile: breaks if hostname convention changes and duplicates knowledge of the naming scheme.
- `resource/role` processor with `${env:NODE_ROLE}` — requires injecting a per-node env var into the DaemonSet, which isn't possible without separate DaemonSets or init containers.

**Rationale:** k8sattributes reads the authoritative source (K8s node labels set during cluster setup). The labels are already there — we just need to read them. The RBAC cost is minimal (ServiceAccount + ClusterRole with read-only pods/nodes access).

### Label control node with type=control during Up command

**Decision:** Add `type=control` to the control node via `k8sService.labelNode()` in the `Up` command's node labeling phase.

**Rationale:** The control node is the K3s server, not an agent, so it doesn't go through `K3sAgentService.getNodeLabels()`. The simplest place to add it is alongside the existing db node ordinal labeling in `Up.labelDbNodesWithOrdinals()`.

### Add RBAC via Fabric8 in OtelManifestBuilder

**Decision:** Build ServiceAccount, ClusterRole, and ClusterRoleBinding as Fabric8 objects in `OtelManifestBuilder`, following the same pattern as all other manifest builders.

**Rationale:** Consistent with existing patterns. RBAC resources are lightweight and the ClusterRole only grants read access to pods and nodes.

## Risks / Trade-offs

- **k8sattributes processor adds K8s API queries** → Minimal impact. The processor caches node metadata and the DaemonSet has one pod per node, so the query volume is proportional to node count (typically 3-10 nodes).
- **Control node labeling depends on Up command order** → The label is applied before `GrafanaUpdateConfig` runs, so OTel will have the label by the time dashboards are deployed. If the label is missing (e.g., on existing clusters), running `grafana update-config` won't fix it — the user would need to manually label or re-run `up`.
- **Existing clusters won't get the control node label automatically** → Acceptable. The `type=db` and `type=app` labels are already present from K3s agent join. Only the control node needs manual labeling on existing clusters.
