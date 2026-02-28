## Why

The System Overview Grafana dashboard has hostname and service filters, but only Spark nodes and the control node appear — Cassandra (db) nodes are missing. This is because the OTel Collector on cluster nodes doesn't add a `node_role` resource attribute to locally-collected metrics. Spark nodes get `node_role: spark` via their own OTel Collector's `resource/role` processor, but the main cluster OTel Collector has no equivalent, so db and app node metrics lack the label entirely.

## What Changes

- Add the `k8sattributes` processor to the cluster OTel Collector config, extracting the K8s node label `type` (already set to `db`/`app` during K3s agent join) as the `node_role` resource attribute
- Add RBAC resources (ServiceAccount, ClusterRole, ClusterRoleBinding) to `OtelManifestBuilder` so the OTel Collector pods can query the K8s API for node metadata
- Label the control node with `type=control` during cluster setup (db and app nodes already get this via K3s agent config, but the control node is the K3s server and lacks it)
- Add `k8sattributes` processor to both `metrics/local` and `logs/local` pipelines
- Update `docs/reference/opentelemetry.md` with node_role labeling documentation

## Capabilities

### New Capabilities

- `otel-node-role`: OTel Collector derives `node_role` from K8s node labels for all cluster nodes, enabling Grafana dashboard filtering by node type

### Modified Capabilities

- `observability`: Grafana dashboard hostname/service filters include all node types (db, app, control), not just Spark

## Impact

- `configuration/otel/OtelManifestBuilder.kt` — new RBAC resources, serviceAccountName on DaemonSet
- `configuration/otel/otel-collector-config.yaml` — new k8sattributes processor in local pipelines
- `commands/Up.kt` — label control node with `type=control`
- `docs/reference/opentelemetry.md` — document node_role attribute
- No changes to EMR/Spark OTel config (already has `node_role: spark`)
