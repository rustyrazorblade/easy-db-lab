## Context

Design by the `spec-flow:architect` agent. The owner approved the spec at Seam 1 on 2026-11-16 and chose option (a) on all seven decisions (D1–D6, D1b); the specs and tasks implement those choices. D2 carries a condition: the relay spike (task 1.1) runs first, and if it fails, D2 falls back to (b), forwarding `cluster.local` DNS to CoreDNS.

Two halves:
1. Pods that produce telemetry move to Service DNS. Every in-cluster producer can already resolve it: Alloy, the sidecar and stress jobs run `hostNetwork` with `ClusterFirstWithHostNet`; kit pods run `ClusterFirst`.
2. Callers outside the pod network lose the host ports and need a replacement: the CLI seam (Socks/Direct), the `down` flush, status, browsers, redirect senders, EMR, `bin/export-workload-metrics`, and the db-node profiling reconciler (a host systemd process).

## Caller inventory

| Caller | Runs where | Today | After |
|---|---|---|---|
| Alloy eBPF agent (`PyroscopeManifestBuilder`) | DaemonSet, hostNetwork + ClusterFirstWithHostNet | AWS: `CONTROL_NODE_IP:4040` | `BackendEndpoints.pyroscope()` or `redirect.profiles` |
| Cassandra sidecar Pyroscope agent (`SidecarManifestBuilder`) | DaemonSet, hostNetwork + ClusterFirstWithHostNet | `http://<controlIp>:4040` | Service DNS or redirect |
| Stress job Pyroscope agent (`StressJobService`) | Job, hostNetwork today; pod network after #1101 | `pyroscopeIngestBaseUrl(controlIp)` | Service DNS or redirect |
| Kit Pyroscope agents (`TemplateVariables.pyroscopeUrl`, trino/presto) | Pods, ClusterFirst | `http://<controlIp>:4040` | Service DNS or redirect |
| Profiling reconciler (`edl-profiling-reconcile`) | Host systemd process | `http://<controlIp>:4040` | `http://127.0.0.1:<ALLOY_RELAY_PORT>` |
| EMR Spark Pyroscope (`EMRProvisioningService`) | EMR nodes, outside K3s | `http://<controlIp>:4040` | `http://<controlIp>:<Pyroscope NodePort>` |
| Redirect senders (`TelemetryRedirect.fromBaseHost`) | Another cluster | `<host>:9009/3100/4320/4040` | `<host>:<NodePort>` per signal |
| CLI seam, `down` flush, status (`NodeServiceAccess`, `ClusterServices`, `StatusAddresses`, `StatusCache`) | Operator machine | `controlIp:<hostPort>` | `controlIp:<NodePort>` |
| Dashboard Pyroscope UI link (`GrafanaDashboardTreeUploader`) | Browser | `http://<controlIp>:4040` | `http://<controlIp>:<Pyroscope NodePort>` |
| `bin/export-workload-metrics` | Operator machine | `CONTROL_HOST_PRIVATE:9009` | `:<Mimir NodePort>` |
| Core collector exporters, Grafana datasources | Pods | Service DNS already | through `BackendEndpoints` |

## Decisions

### D1. How callers outside the pod network reach backends and Grafana — **decided (owner, 2026-11-16): (a)**
- **(a) Chosen: NodePort Services with fixed ports in `Constants`.** One mechanism for every off-pod caller; matches the kit NodePort rule; Hubble UI's NodePort 31234 already works on Cilium and Flannel. The seam's `ServiceTarget.nodePort` gets real values and the Socks, Direct, status and server paths need no other change.
- (b) PortForward exposure everywhere, AWS included. Publishes nothing, but every query and the `down` flush go over an API-server port-forward, browsers need a forward per Service, and redirect and EMR still need (a).
- (c) SOCKS to the Service ClusterIP. CLI only; breaks Tailscale/Direct because the Service CIDR is not advertised.

Proposed ports (`Constants.K8s`): Grafana 31300, Mimir 31009, Loki 31100, Tempo query 31200, Tempo OTLP gRPC 31320, Pyroscope 31040. None collide with a kit NodePort (30081–32404 in use) or Hubble 31234. gRPC, memberlist and Tempo 4321 are not published.

### D1b. Where the NodePorts apply — **decided (owner, 2026-11-16): (a)**
- **(a) Chosen: only where the environment owns its nodes** (Socks/Direct exposure). Fixed NodePorts on a foreign cluster can collide or be refused. One environment difference survives, at the Service type instead of container host ports.
- (b) Everywhere, so manifests are fully identical; fails when a foreign cluster already uses a port.

### D2. How host processes on cluster nodes reach Pyroscope — **decided (owner, 2026-11-16): (a)**
- **(a) Chosen: Alloy `pyroscope.receive_http` relay on `127.0.0.1:4041`.** Host DNS is untouched; mirrors the OTel agent's `localhost:4318` pattern; redirect choice moves into Alloy; no backend URL is built from a node IP. Risk: must be verified that `receive_http` accepts `format=jfr` on `/ingest` and keeps the `name=` series and labels the reconciler sends. Task 1.1 is that spike; owner decision: if it fails, fall back to (b).
- (b) Forward `cluster.local` from systemd-resolved to CoreDNS on every node. Every host process gets Service DNS, at the cost of an AMI/setup change and a host-DNS boot dependency on CoreDNS.
- (c) `http://<controlIp>:<Pyroscope NodePort>`. Smallest change; violates AC3.

### D3. Derived redirect ports — **decided (owner, 2026-11-16): (a)**
- **(a) Chosen: `fromBaseHost` derives the receiving stack's NodePorts**, so `--redirect-telemetry <ip>` alone still works against another easy-db-lab cluster.
- (b) Keep 9009/3100/4320/4040; every redirect to an easy-db-lab stack then needs four overrides.

### D4. Callers the issue did not list (EMR Spark Pyroscope, `bin/export-workload-metrics`, dashboard Pyroscope link) — **decided (owner, 2026-11-16): (a)**
- **(a) Chosen: include them.** Removing the host ports breaks each; the repo rule is to fix such bugs in the same patch. EMR keeps a node address plus NodePort, a named exception to AC3 for producers outside the cluster.
  - **Which node, and why the control node.** EMR's Spark agents send to the control node's private IP. EMR runs in the cluster's VPC but outside K3s, so it cannot resolve Service DNS and needs `<node IP>:<NodePort>`. A NodePort answers on every node, so any node would do; the control node is chosen because every easy-db-lab AWS cluster has exactly one, its address is already in state, and the EMR bootstrap already sends OTLP to that same address on 4317. That is not the single-node dependency this change removes elsewhere: that dependency was on the backends being *placed* on the control node (host ports there). Here the control node is only an entry point, and kube-proxy forwards the NodePort to the Pyroscope pod wherever it runs. An environment with no control node does not run EMR (EMR is AWS-only), and one whose Services are ClusterIP only fails EMR provisioning up front (`ServicePublishing.requireNodePorts`) instead of configuring an address that would time out.
- (b) Separate issue; Spark profiling and the export script are broken in between.

### D5. Service DNS form and consolidation — **decided (owner, 2026-11-16): (a)**
- **(a) Chosen: one `BackendEndpoints` in `.svc` form used by every in-cluster producer, including the collector exporters and Grafana datasources.** `StatusAddresses` keeps the FQDN for display. The collector rolls once.
- (b) Consolidate only the Pyroscope callers; smaller diff, mixed forms remain.

### D6. The Pyroscope UI link in the profiling dashboard — **decided (owner, 2026-11-16): (a)**
- **(a) Chosen: `http://<controlIp>:<Pyroscope NodePort>` on owned nodes, unchanged on the claim path.**
- (b) Proxy Pyroscope under `/pyroscope/` in the Grafana front so the link is relative; requires Pyroscope path-prefix support (unverified).

## Structure

- `configuration/BackendEndpoints` (new `object`, pure): `pyroscope()`, `mimirPush()`, `lokiOtlp()`, `tempoOtlpGrpc()`, built on one host helper.
- `profiling/ProfilingConfig`: `pyroscopeIngestBaseUrl(telemetryRedirect)` returns `redirect?.profiles ?: BackendEndpoints.pyroscope()`; new `nodeLocalProfilesRelay()` returns `http://127.0.0.1:${Constants.K8s.ALLOY_RELAY_PORT}`. With the IP parameter gone, the compiler finds every caller.
- `PyroscopeManifestBuilder`: `resolvePyroscopeWriteUrl` loses its hosting branch and `CONTROL_NODE_IP` read; `config.alloy` adds `pyroscope.receive_http "relay"` forwarding to the existing write component (which already sets the tenant header).
- `Placement`: remove `publishOnNode`, `placePorts`, and `publishOnNode = true`. Delete each builder's `withHostPort` (Mimir, Loki, Tempo ×3, Pyroscope, Grafana front).
- Service builders: under D1a/D1b-a, set `type: NodePort` and the fixed `nodePort` on client ports only when the environment owns its nodes.
- `ClusterServices.controlService(name, servicePort, nodePort)` takes the NodePort constant.
- `ClusterConfigData`: remove `CONTROL_NODE_IP_KEY`, `publishedNodeIp` and the "no control node" failure for this reason.
- `SidecarManifestBuilder` / `SidecarService.deploy`: drop `controlNodeIp`.
- `StressJobService.buildJob`: drop the control-node lookup, parameter and argument only. Region lookup, `hostNetwork`, hostPath agents, selector and target stay for #1101.

## Boundary with #1101

#1135 owns the stress Pyroscope address, the `buildJob` control-node lookup, `TemplateVariables.pyroscopeUrl` and removing `Placement.publishOnNode`. #1101 owns stress `hostNetwork`, hostPath/agents, the node selector and the stress target. After #1101 a stress pod on the pod network still resolves `pyroscope.default.svc`. #1101's `design.md` line about `controlIP:4040` while `publishOnNode` holds the host port must be updated to say stress already uses Service DNS.

## Risks

- **`down` safety.** Any seam path still dialing a removed host port makes `down` refuse teardown. Covered by seam tests and the AWS lab run.
- **Alloy relay JFR support** is unverified (D2 spike, task 1). If Alloy is down, the reconciler keeps its chunks (never deleted) until its byte bound and stops recording — today's behaviour when Pyroscope is unreachable.
- **NodePorts open on every node**, reachable only inside the VPC (the security group admits the VPC CIDR).
- **Port numbers change** for bookmarks, SOCKS browser setup and redirect targets. Docs updated; no migration needed.
- **Host-network DNS:** a CoreDNS restart briefly fails lookups for the sidecar, Alloy and stress; their agents retry. Already true off AWS.
- **Config-hash rolls:** Alloy, sidecar and collector roll once on the next deploy.
