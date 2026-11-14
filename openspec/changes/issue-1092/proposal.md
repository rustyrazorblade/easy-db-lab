## Why

Part of #1032 (Kubernetes-forward epic), phase A (portable core). The OTel collector is one privileged DaemonSet with `hostNetwork`, hostPorts 4317/4318 and hostPaths `/`, `/var/log` and `/mnt/db1/...` (`OtelManifestBuilder.kt`). Producers dial the node directly: `HOST_IP:4317/4318` or `CONTROL_HOST_PRIVATE:4318`. Under Pod Security "baseline", GKE Autopilot or EKS Fargate the whole collector is rejected, and then no metrics, logs or traces arrive at all. #1037 (PR #1084) gates only journald, eBPF, the registry, CloudWatch and S3 — not the collector itself.

Owner decisions, 2026-11-13: split the collector into an unprivileged core and a privileged host add-on (D1–D6, Q1–Q2 on #1092).

## What Changes

- **Core collector `otel-collector`** runs on the pod network with no hostPort, no hostNetwork. It owns the OTLP receivers, all pod-discovery scrapes, `kubeletstats` (new, everywhere — D5), `k8s_attributes`, the spanmetrics/servicegraph connectors and every backend exporter. It is the only collector that talks to the backends.
- **Host add-on `otel-collector-host`** is a second DaemonSet (D3: its own builder and ConfigMap) with `hostNetwork` and privileged, deployed only where the environment has `PRIVILEGED`. It owns host metrics, host file logs (system, tools, Cassandra GC log on `/mnt/db1`), the Beyla/ebpf_exporter/Cilium localhost scrapes, and the node's `:4317/:4318` OTLP listener. It forwards everything to the core over OTLP through the Service (D1).
- **Service `otel-collector`** gains `internalTrafficPolicy: Local` and the OTLP HTTP port 4318, so each producer reaches its own node's core pod. The core tolerates every taint so no node is left without a core pod.
- **In-cluster producers re-point to Service DNS** (`otel-collector.<namespace>.svc`): the Cassandra sidecar, cassandra-pods/neo4j/ignite3/tidb kits, the stress JVM agent and the stress sidecar, via new template variables. Host processes that are not pods (Cassandra on the AMI, profiling reconcile, Fluent Bit, EMR) keep their node/control-node endpoint — the host add-on listens there, so there is no AMI/packer change (D2).
- **`edl_push_gauges`** (runs on the operator's laptop) pushes through the API server's pod proxy to the control node's core collector pod via the workspace kubectl wrapper (Q1; one pod, so its gauges keep one `host.name`), instead of `CONTROL_HOST_PRIVATE:4318`.
- **Capability gating.** `Stage.requires` becomes `Set<NodeCapability>` (D6). The host add-on requires `PRIVILEGED`; Beyla and ebpf_exporter require `PRIVILEGED`; journald requires `SYSTEMD_JOURNAL` + `PRIVILEGED` (without the add-on it has nowhere to send); the core's pod-log pipeline requires `HOST_PATH`. Each missing capability is reported with `StageSkipped`, by name. The Pyroscope eBPF agent is split out of `buildAllResources` so a rejected Alloy DaemonSet does not fail the rollout wait (D6).
- **`down`.** `K8sTelemetrySenders` deletes and waits for both DaemonSets, add-on first (so it drains into a still-running core), within the existing flush deadline.
- **Node identity.** `resource_detection[system]` (OS hostname = pod name on the pod network) is replaced by `host.name = ${env:K8S_NODE_NAME}` (downward API `spec.nodeName`) in the core and add-on; node-local pod-SD filters on `K8S_NODE_NAME`. Static NodePort kit jobs target `${env:K8S_NODE_IP}:<port>` instead of `localhost:<port>`.
- **Dashboards.** The stress JVM's `host.name` shifts from `control0` to its own node; the comparison dashboards' `host_name` filters are fixed via the `dashboard-editor` agent (Q2, a task).
- **Folded-in debt (owner-approved):** remove `@Suppress("LongMethod")` on `buildDaemonSet` by refactoring; share `renderScrapeJobs`.
- **Docs:** root `CLAUDE.md` observability section, `configuration/CLAUDE.md` OTel subpackage, `events/CLAUDE.md` for the `StageSkipped` set.

## Capabilities

### Modified Capabilities

- `observability`: the OTel collector is two DaemonSets — an unprivileged core behind the `otel-collector` Service (`internalTrafficPolicy: Local`) and a `PRIVILEGED`-gated host add-on that forwards to it. Host sources, host-network scrapes and the node OTLP listener move to the add-on; pod-log collection is gated on `HOST_PATH`. Each missing capability is reported with `StageSkipped`.

## Impact

- **On AWS:** the same metrics, logs and traces arrive, and the comparison dashboards still work (the stress JVM's node label changes, fixed in the dashboards). K3s nodes always have `PRIVILEGED` and `HOST_PATH`, so the add-on and pod logs are present. Cilium scrapes stay on localhost in the add-on, unchanged.
- **On a cluster without `PRIVILEGED`:** the core reaches Ready, pod and application telemetry flows, and the host add-on, Beyla, ebpf_exporter and journald are each reported skipped. Host metrics and host logs are absent (expected; #1088 explains the empty panels).
- **No AMI/packer change** (D2).

## Out of scope

- `k8s_events` (deferred — D4).
- The backend/Grafana Service repointing of the collector's exporter endpoints (#1091 owns the exporter `localhost:<port>` → backend Service DNS edits).
- The `PRIVILEGED`/`HOST_PATH` enum entries themselves (#1096 spec's the capability probe; whichever change lands first adds them).
- Static NodePort scrape-job per-node duplication (separate issue, noted only).
- Moving stress jobs off hostNetwork (#1101).
- Restricted security contexts for all unprivileged pods (#1095; this change sets only the core collector's context).
