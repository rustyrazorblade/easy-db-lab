# Design — issue-1091

## Context

Five observability workloads (Mimir, Loki, Tempo, Pyroscope, Grafana) are pinned to the K3s control node and run with `hostNetwork`. The Kubernetes-forward epic (#1032) requires one set of manifests that runs on any cluster. The owner settled the open decisions on 2026-11-13; this design records them and the approach that was already verified against the code.

Mimir, Loki, Tempo and Pyroscope **already ship ClusterIP Services** (`TempoManifestBuilder.kt:86-111` and the equivalents) and their Deployments already use `strategy: Recreate` with `rollingUpdate: null`, so the RWO-attach deadlock is already avoided. Grafana has **no** Service today (reached via host port); #1090 adds its ClusterIP Service.

## Owner decisions

### A — Off-AWS backend storage (settled)

Retained PVC per backend off owned nodes (StatefulSet `volumeClaimTemplates`, or Deployment + PVC with `Recreate`); `hostPath` stays on AWS.

- **Chosen** because Pyroscope's v2 metastore is the only record of its blocks; `emptyDir` would silently lose it (and any unflushed Mimir/Loki samples) on reschedule, violating the no-delete rule.
- **Rejected: `emptyDir` everywhere** — data loss on reschedule.
- **Caveat folded into the AC:** EBS `gp3-retain` is `WaitForFirstConsumer` + zonal, so a rescheduled pod lands on a node *in the volume's zone*, not literally any node. Reachability is via Service DNS and is independent of the node, so "Grafana and the collector still reach it" holds. The issue AC now reads "rescheduled to another node in the volume's zone".
- The off-AWS claim's StorageClass and the reclaim-`Delete` refusal are **#1035**'s `ObservabilityStorage`. Until it lands, the off-AWS branch is feature-gated behind a stub that resolves `HOST_PATH` present (AWS `hostPath`); the AWS path is fully delivered here.

| Workload | Local state | AWS (`HOST_PATH`) | Off-AWS |
|---|---|---|---|
| Mimir | TSDB head/WAL, 15m local blocks, store-gateway sync dir | hostPath `/mnt/db1/mimir` | retained PVC |
| Loki | WAL, pre-flush chunks, index cache | hostPath `/mnt/db1/loki` | retained PVC |
| Tempo | WAL, pre-flush blocks (`flushed` markers) | hostPath `/mnt/db1/tempo` | retained PVC |
| Pyroscope | v2 metastore (Raft) + segments | hostPath `/mnt/db1/pyroscope` | retained PVC |
| Grafana | sqlite db + provisioning tree | hostPath `/mnt/db1/grafana` | retained PVC |

### B — Grafana documents delivery (settled)

Serve the documents web server same-origin through the grafana Service on a second Service port with a relative URL, so one `127.0.0.1` port-forward (decision K12) covers both Grafana and documents. The host port (`GrafanaDocumentsSidecars.kt:114`) is removed.

- **Chosen** because a node-IP/host-port URL is unreachable off AWS, where the browser reaches Grafana only through the port-forward.
- **Rejected: a second forwarded port** — two forwards for one UI; the Text-panel iframe URL would still have to be environment-specific.
- **Rejected: keep the host port, gate documents off AWS by capability** — leaves the feature AWS-only, against "one set of manifests".
- The sigv4 proxy uses the default credential chain; its credentials are #1061's concern, not this change.
- **Open (found while implementing, 2026-11-13): B contradicts itself as written.** A second Service port is a different origin. A relative `/reports/...` URL resolves against the page's origin, which is Grafana's own port (3000 behind the forward), not the documents port (3080). A single `127.0.0.1` forward also carries one Service port, not two. To be same-origin, `/reports/` has to be served on Grafana's own port. One way is a reverse proxy in the Grafana pod that serves Grafana's Service port, sends `/reports/` to the documents nginx and everything else to Grafana. Another is the documents nginx fronting Grafana. The owner has to pick one before 5.1 and 5.2 can be built. They also wait on #1090's grafana Service, which is not on main yet (PR #1110, draft). Until then the documents web server keeps host port 3080 on AWS (decision D), and the iframe does not load off AWS.

### C — Grafana Service ownership and sequencing (settled)

#1090 (service access) **adds** the grafana ClusterIP Service. This change does **not** add a second one — it adds the documents port to #1090's Service and treats #1090 as the owner of the Service definition. Implementation merges after #1090, because the CLI readers and the teardown flush dial node IPs and break the moment `hostNetwork` drops.

### D — Node ports on an environment that owns its nodes (proposed; needs owner sign-off)

On AWS, `Placement.CONTROL_NODE` sets `publishOnNode = true`. The five workloads leave `hostNetwork`, but each container port keeps the `hostPort` it declares on the control node. That port is reached through the CNI's `portmap` chaining. The ports are Mimir 9009, Loki 3100, Tempo 3200/4320/4321, Pyroscope 4040, Grafana 3000 and documents 3080. Off AWS (`ANY_NODE`) every host port is dropped.

- **Why:** without this, AWS loses callers that reach the control node's private IP today. Some of them are not CLI readers, so landing #1090 does not fix them:
  - another cluster's collector, under `init --telemetry-redirect` (`TelemetryRedirect.fromBaseHost`, `<host>:9009/3100/4320/4040`);
  - the hostNetwork Alloy eBPF DaemonSet (`PyroscopeManifestBuilder.resolvePyroscopeWriteUrl`);
  - the db-node sidecar's Pyroscope agent (`SidecarManifestBuilder`, `CONTROL_NODE_IP:4040`) and stress jobs (`StressJobService`);
  - the documents iframe URL (`DashboardDefaults.documentsUrl`, `<privateIp>:3080`);
  - the CLI readers and the teardown flush, until #1090 repoints them.

  Dropping the host ports would break proposal AC1 ("on AWS: no behaviour change").
- **Rejected: drop `publishOnNode` and merge after #1090.** #1090 repoints only the CLI and flush callers. Redirected clusters and the node-side agents would still lose the backends.
- **Open for the owner:** whether this stays permanent on AWS (as the redirect callers need), or each node-IP caller moves to a Service or NodePort in a follow-up and this flag is removed.

## Placement value

```kotlin
data class Placement(
    val nodeSelector: Map<String, String>, // AWS: {"node-role.kubernetes.io/control-plane":"true"}; else {}
    val tolerations: List<Toleration>,      // AWS: the control-plane NoSchedule toleration; else []
    val hostNetwork: Boolean,               // false for these five workloads on every environment
)
```

Resolved alongside `NodeCapabilities.from(ClusterState)` (`NodeCapability.kt`). Until #1040 records the environment, it returns the AWS value. An `init` `--node-label` merges into `nodeSelector`. `ObservabilityStackService` passes one `Placement` into each builder; the builders stop hardcoding the selector, toleration and `hostNetwork(true)` and apply the `Placement` instead. kube-state-metrics (already pod-network ClusterIP) swaps only its control-node selector to the same `Placement`.

## In-cluster callers that change to Service DNS

- `GrafanaDatasourceSet.kt:42-50` — backend base URLs from `http://localhost:<port>` to `http://<svc>.<namespace>.svc:<port>`. The datasource-building pure function is unchanged (only the URLs it is called with).
- The OTel collector's backend exporter endpoints (`OtelManifestBuilder` `LOCAL_*` rendered into `otel-collector-config.yaml`) from `localhost:<port>` to Service DNS. This is a backend caller, so it is owned here even though the collector's placement split is #1092.

Service names = the workloads' existing app labels, on `<svc>.<namespace>.svc`: `mimir:9009` (`/prometheus`), `loki:3100`, `tempo:3200` (+ `otlp-grpc`/`otlp-http`), `pyroscope:4040`, `grafana:3000` (added by #1090), documents on a second grafana Service port. Collector Service `otel-collector` (agreed with #1092).

**CLI callers are #1090, not here:** `ObservabilityHttp` (Mimir/Loki/Tempo readers), `GrafanaClient`, `status`, the MCP status cache, and the teardown flush's HTTP shutdown calls (`TempoTailFlush`, `AnnotationMirror`, `/ingester/shutdown`). Scale-to-0 and collector-stop already go through the K8s API (`BackendWorkloads.scaleDown`, `TelemetrySenders.stop`) and are unchanged, so the `down` flush contract holds.

## Credentials plug point

Each backend Deployment/StatefulSet gets a `serviceAccountName` (default) and an optional `envFrom: [secretRef: <store-credentials>]`. Absent on AWS (instance role, unchanged); #1061 populates it.

## Restarts and teardown

`ConfigHashAnnotator` keeps rolling only workloads whose template/config changed; the Service and volume changes flow through it. `down`'s scale-to-0 + collector-stop are K8s-API calls, unchanged.

## Testing

K3s TestContainers integration test: delete the `node-role.kubernetes.io/control-plane` label on the test node, deploy with an empty `Placement`, and assert every backend and Grafana reaches Ready and the datasource URLs are Service DNS. Keep the existing apply/no-memory-limit tests. AWS-unchanged is a rendering test: with the AWS placement the selector, toleration and `hostPath` volumes are identical to today's.

## Dependencies

- **Hard:** #1090 (CLI/flush callers break when node-IP listeners vanish) — merge after it. #1035 (`ObservabilityStorage` for off-AWS claims) — AWS path independent; off-AWS stubbed until it lands.
- **Soft:** #1061 (credential Secret hook), #1092 (collector placement; endpoints owned here), #1094 (namespace in DNS), #1096 (`HOST_PATH`/`HOST_NETWORK` capabilities), #1089 (PR #1107, kubeconfig-only access).
