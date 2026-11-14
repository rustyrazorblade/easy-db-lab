## Why

Part of #1032 (Kubernetes-forward epic), phase A (portable core). Design: `docs/plans/2026-11-11-kubernetes-forward-architecture.md`, section "Observability stack".

Mimir, Loki, Tempo, Pyroscope and Grafana pin `node-role.kubernetes.io/control-plane: "true"` (a label only K3s sets) and run with `hostNetwork` (`MimirManifestBuilder.kt:175-180`, `LokiManifestBuilder.kt:145-150`, `TempoManifestBuilder.kt:148-152`, `PyroscopeManifestBuilder.kt:231-235`, `GrafanaManifestBuilder.kt:138-142`). Grafana's datasources point at `localhost:<port>` (`GrafanaDatasourceSet.kt:42-50`), the OTel collector exports to the backends at `localhost:<port>`, and the Grafana documents web server uses a host port (`GrafanaDocumentsSidecars.kt:114`). On a cluster easy-db-lab did not build — EKS, GKE, AKS — there is no schedulable control-plane node, so the whole backend tier stays Pending; and `hostNetwork` plus host ports are rejected under Pod Security "baseline"/"restricted".

## What Changes

- Each backend and Grafana runs as an ordinary workload on the pod network behind a ClusterIP Service. No `hostNetwork` on these five workloads.
- Infra-pod placement is a resolved value, not a hardcoded control-plane pin: `Placement(nodeSelector, tolerations, hostNetwork)`, resolved from the recorded environment/capabilities. AWS keeps today's control-node selector and toleration (as values); the default elsewhere is any node; an `init` user label is allowed. The same edit swaps kube-state-metrics' control-node selector to the resolved placement.
- Grafana datasources and every **in-cluster** caller use Service DNS (`<svc>.<namespace>.svc`), not `localhost` or a node IP. This includes the OTel collector's backend exporter endpoints — without this, once the backends are ClusterIP-only the hostNetwork collector's `localhost:9009` stops resolving and no data reaches the dashboards.
- Backend local state (Mimir TSDB head/WAL and store-gateway sync dir, Loki WAL and pre-flush chunks, Tempo WAL and pre-flush blocks, Pyroscope v2 metastore, Grafana sqlite) lives on a `hostPath` where the environment has `HOST_PATH` (AWS, today's `/mnt/db1/<backend>`), otherwise on a **retained** per-backend claim (owner decision A, 2026-11-13). `emptyDir` is ruled out: Pyroscope's metastore is the only record of its blocks, so losing it on reschedule would lose data (no-delete rule). The off-AWS claim's storage type comes from #1035 (`ObservabilityStorage`), which is stubbed/feature-gated until it lands; the AWS `hostPath` path is independent.
- The Grafana documents web server is served **same-origin** on Grafana's own port with a relative URL: the documents nginx fronts Grafana, sending `/reports/` to the signing proxy and every other path to Grafana (owner decision B, revised), so one `127.0.0.1` port-forward covers both Grafana and the documents view (decision K12). The host port is removed.
- Backends expose a `serviceAccountName` and an optional `envFrom` store-credentials Secret as plug points, so #1061 can deliver pod credentials without re-touching these builders. On AWS the Secret is absent and the instance role is used, unchanged.

## Capabilities

### ADDED Requirements
- `observability`: Observability backends and Grafana run on the pod network behind ClusterIP Services, with per-environment placement and retained storage off owned nodes.

### MODIFIED Requirements
- `observability`: kube-state-metrics placement comes from the environment, not a hardcoded control node.
- `test-documents`: Grafana reads test documents through a read-only web server served same-origin on Grafana's own port by an nginx front, not a host port.

## Impact

- On AWS: no behaviour change. Placement resolves to the control-node selector and toleration; storage stays on `/mnt/db1/<backend>`; `hostNetwork` is dropped but pod-network pods already reach the backends via the new Service DNS and reach the instance role (IMDS hop limit is already 2, `EC2InstanceService.kt:149-155`). Every dashboard shows data.
- On a cluster with no control-plane node: the backends and Grafana reach Ready with an empty placement and retained claims.
- CLI readers and the teardown flush (`ObservabilityHttp`, `TempoTailFlush`, `AnnotationMirror`, Mimir/Loki `/ingester/shutdown`) dial node IPs today and break when `hostNetwork` drops — they are repointed by #1090 (service access). Implementation of this change merges after #1090. Scale-to-0 and collector-stop already go through the K8s API and are unchanged.

## Out of scope

- The OTel collector's own placement/privilege split (#1092). Only its backend **exporter endpoints** change here.
- How the CLI reaches these Services (#1090). The Grafana ClusterIP Service definition is **added by #1090**; this change adds no port to it (the documents share Grafana's port 3000) and states that #1090 owns the Service.
- The `ObservabilityStorage` storage-type selection and the reclaim-`Delete` refusal (#1035).
- Store credential delivery to pods (#1061) — only the plug points are added here.
- Namespace parameterization (#1094); the current namespace constant is used.
- Probing `HOST_PATH`/`HOST_NETWORK` on a user cluster (#1096); until it lands, placement resolves `hostNetwork=false` off AWS.
