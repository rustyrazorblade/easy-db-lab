## Why

Part of #1032 (Kubernetes-forward epic). Follow-up to #1091 (backends as Services), decision D. Issue: #1135.

**Status:** approved by the owner at Seam 1 on 2026-11-16, with option (a) on every design decision (D1–D6, D1b; see `design.md`).

#1091 moved Mimir, Loki, Tempo, Pyroscope and Grafana onto the pod network behind ClusterIP Services. On AWS the stack still publishes their client ports on the control node (`Placement.publishOnNode`), because some callers still dial the control node's IP. While that flag exists, the AWS manifests differ from every other environment and the control node stays special.

The issue puts the CLI's readers out of scope because they "already go through the service-access seam (#1090)". On AWS that is not true. The Socks and Direct seams dial `controlPrivateIp:<servicePort>` (`services/access/NodeServiceAccess.kt`, `ClusterServices.kt` sets `nodePort = servicePort`), and that port is the host port this change removes. Removing the host ports alone would break `logs query`, `grafana annotate`, `status`, the server's status cache and the `down` tail flush. `down` would then refuse to tear down. The fix belongs in this patch.

## What Changes

- **In-cluster producers use Service DNS.** One `BackendEndpoints` object builds each backend's in-cluster URL (`<service>.<namespace>.svc:<port>`). The Alloy eBPF agent, the Cassandra sidecar Pyroscope agent, the stress jobs' Pyroscope agent, kit Pyroscope agents (`TemplateVariables.pyroscopeUrl`) and the core collector exporters use it, or the redirect endpoint under telemetry redirect.
- **`pyroscopeIngestBaseUrl` takes no IP.** Its signature becomes `pyroscopeIngestBaseUrl(redirect)`. Every caller that passed the control node IP is moved. The control-node lookup in `StressJobService.buildJob` goes (#1101 D6).
- **Host processes send profiles through a node-local Alloy relay.** The profiling reconciler is a systemd process on db nodes and cannot resolve cluster DNS. The Alloy DaemonSet (already `hostNetwork` on every node) gains a `pyroscope.receive_http` listener on `127.0.0.1:<ALLOY_RELAY_PORT>` that forwards to the Pyroscope Service, or to the redirect target. The reconciler's `pyroscopeUrl` always points at the relay. (D2)
- **Off-pod-network callers use fixed NodePorts.** On environments that own their nodes, the Grafana, Mimir, Loki, Tempo and Pyroscope Services become `type: NodePort` on fixed ports from `Constants`. The service-access seam's Socks and Direct paths dial `controlPrivateIp:<nodePort>`. Browsers, redirect senders, EMR and `bin/export-workload-metrics` use the same ports. (D1, D1b)
- **Telemetry redirect derives the NodePorts** from `--redirect-telemetry <host>`; per-signal overrides are unchanged. (D3)
- **EMR Spark Pyroscope, `bin/export-workload-metrics` and the dashboard Pyroscope UI link** move to the Pyroscope or Mimir NodePort. EMR is outside K3s, so it is the one named producer that still uses a node address. (D4, D6)
- **Removed:** `Placement.publishOnNode` and `placePorts`; every backend and Grafana `withHostPort`; `ClusterConfigData.CONTROL_NODE_IP_KEY` and `publishedNodeIp`; the `controlNodeIp` parameter of `SidecarManifestBuilder` and `SidecarService.deploy`; `ProfilingHostCommand.controlNodePrivateIp()`; the `CONTROL_NODE_IP` read in `PyroscopeManifestBuilder`.
- **Docs and specs:** `docs/reference/ports.md`, monitoring, network-connectivity, profiling, kits and platform-substrate guides, `help/observability.md`, `configuration/CLAUDE.md`, `dashboards/CLAUDE.md`; the observability, service-access, telemetry-redirect, profiling, spark-emr and networking specs.

## Scope

**In:** every node-IP caller of a backend or Grafana — the four the issue lists, plus the CLI seam, `down` flush, status, the profiling reconciler, EMR Spark Pyroscope, `bin/export-workload-metrics` and the dashboard Pyroscope link, because removing the host ports breaks each one.

**Out:**
- Stress `hostNetwork`, hostPath agents, the stress node selector and the stress target — owned by #1101 (kit host independence). #1135 lands first; #1101's `design.md` sentence about stress reaching `controlIP:4040` becomes obsolete.
- YACE, S3Manager, Registry and the host collector's 4317/4318 host ports — not backends or Grafana.
- The EMR OTel collector's `controlIp:4317` — it targets the host collector, not a backend.
- Kit `CONTROL_HOST_PRIVATE` uses against kit NodePorts (ignite3, kafka).

## Impact

- Code: `configuration/Placement.kt`, the five backend and Grafana manifest builders, `PyroscopeManifestBuilder` (Alloy config), `SidecarManifestBuilder`, `SidecarService`, `StressJobService`, `TemplateVariables`, `ProfilingConfig`, `SetupInstance`, `ProfilingStart`, `ProfilingStop`, `ProfilingHostCommand`, `EMRProvisioningService`, `GrafanaDashboardTreeUploader`, `ClusterConfigData`, `ClusterServices`, `TelemetryRedirect`, `OtelManifestBuilder`, `GrafanaDatasourceSet`, `Constants`; `bin/export-workload-metrics`; `packer/cassandra/bin/edl-profiling-reconcile.test.sh` fixtures.
- User-visible: backend and Grafana ports seen from outside the cluster change (Grafana 3000 → 31300, and so on). Clusters are ephemeral; no migration.
- No `state.json` change.
