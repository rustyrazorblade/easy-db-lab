## Context

The OTel collector DaemonSet (`OtelManifestBuilder`) currently has a fully static config: a fixed set of Prometheus scrape jobs (ClickHouse `:9363`, MAAC `:9000`, Beyla `:9400`, ebpf-exporter `:9435`, YACE `:5001`) baked into the `otel-collector-config.yaml` classpath resource. There is no mechanism for K8s workloads installed via `install.yaml` to register themselves as scrape targets.

K3s currently uses Flannel as its CNI with `kube-proxy` for service routing. K8s workloads installed via the platform substrate need standard pod networking (not `hostNetwork`) so that multiple workloads can run simultaneously without port conflicts. Switching to Cilium provides eBPF-based networking (lower overhead, better benchmark baselines), replaces `kube-proxy` with eBPF service routing, and adds Hubble for network-level observability — a natural complement to the existing OTel application-level telemetry stack.

Client ports and metrics ports on K8s pods are exposed via Kubernetes `hostPort` mappings — `containerPort` maps to a `hostPort` on the EC2 instance's network interface, making them reachable from anywhere in the VPC (via Tailscale exit node on the control node). Port remapping is used where a workload's native port conflicts with a host process (e.g., ScyllaDB CQL `containerPort: 9042 → hostPort: 9142` to avoid conflict with Cassandra on the host). Apps running as K8s pods use ClusterIP Services on native ports internally; hostPort is for external access only.

The OTel DaemonSet keeps `hostNetwork: true` so it can scrape both host processes (Cassandra/MAAC at `localhost:9000`) and K8s workload metrics ports exposed via `hostPort` (also visible as `localhost:<port>` from the host network namespace).

**Lifecycle clarification:** `install` sets up directory structure and K8s prerequisites (PVs, namespaces, operators). `start` is when the database actually runs — metrics and log collection are configured as part of `start`. `stop` tears down the running database and removes observability registration.

## Goals / Non-Goals

**Goals:**
- Replace Flannel with Cilium as the K3s CNI; disable `kube-proxy`; enable Hubble.
- Allow `install.yaml` to declare how a workload's metrics reach the OTel collector via a `metrics` block.
- Automatically register workload metrics with the OTel collector after `start` completes, and deregister after `stop` completes — no explicit steps required in `install.yaml`.
- Keep K8s as the single source of truth for the metrics registry — no filesystem state, no `ClusterState` entries.
- Support multiple simultaneous workloads without port conflicts or manual OTel config editing.

**Non-Goals:**
- Cilium service mesh / mTLS between pods.
- Cilium LoadBalancer IPAM (hostPort is sufficient for this use case).
- Automatic dashboard provisioning per workload (handled by existing `dashboards` block).
- Metrics for workloads not using the `install.yaml` system (e.g., legacy `clickhouse start`).
- Supporting non-Prometheus metrics endpoints (StatsD, etc.).
- Any observability setup during the `install` phase — that phase is scaffolding only.

## Decisions

### Decision 1: Adopt Cilium as the K3s CNI

**Choice:** K3s is started with `--flannel-backend=none --disable-network-policy`. Cilium is installed via helm immediately after K3s bootstraps, before any workloads are deployed. `kube-proxy` is replaced by Cilium's eBPF service routing (`kubeProxyReplacement: true`). Hubble is enabled with Prometheus metrics export.

**Rationale:** Cilium's eBPF dataplane has significantly lower per-packet overhead than iptables/Flannel, giving cleaner benchmark baselines. `kube-proxy` replacement improves service routing latency. Hubble adds network-flow observability (throughput, latency, drops) that complements OTel's application-level telemetry — Hubble metrics flow into VictoriaMetrics via the existing Prometheus remote-write pipeline. Cilium is a well-supported combination with K3s; on ephemeral clusters the cost is a one-time change to the provisioning path.

**Alternative considered:** Keep Flannel + add MetalLB. Rejected — adds two components for less benefit than Cilium alone; MetalLB doesn't replace kube-proxy or add observability.

### Decision 2: K8s ConfigMap as the metrics registry

**Choice:** After `start` completes, the install command reads the `metrics` block from `install.yaml` and writes a ConfigMap named `easydblab-metrics-<workload>` (label `easydblab.com/workload-metrics=true`) containing `job-name`, `port`, and `path`. After `stop` completes, the install command deletes that ConfigMap. `OtelManifestBuilder` lists all such ConfigMaps via Fabric8 and injects a scrape job per entry alongside the static base jobs.

**Rationale:** K8s is already the source of truth for runtime state. A ConfigMap registry is K8s-native, queryable by label selector, idempotent, and requires no filesystem state. No new install step types are needed — the install command handles registry management automatically as a post-phase side effect of `start` and `stop`.

**Alternative considered:** OTel `file_sd_configs` with a per-workload JSON targets file. Rejected — requires filesystem state management and diverges from the K8s-as-source-of-truth principle.

### Decision 3: OTel sync is automatic after `start` and `stop` — not an explicit step

**Choice:** After `start` phase steps complete, the install command automatically: (1) writes the `easydblab-metrics-<workload>` ConfigMap if a `metrics` block is declared, then (2) calls `OtelManifestBuilder` to regenerate and apply the OTel collector ConfigMap. After `stop` phase steps complete, it deletes the registry ConfigMap and regenerates OTel config. Workload authors only declare the `metrics` block — no lifecycle steps required.

**Rationale:** Automatic behavior removes the footgun — workload authors cannot forget the sync or get the ordering wrong. The correct ordering (register after DB is running, deregister after DB is stopped) is enforced by the install command itself, not left to each workload's `install.yaml`.

**Alternative considered:** Explicit `type: sync-otel` step. Rejected — requires every workload author to know about it and include it correctly in both `start` and `stop`.

### Decision 4: OTel DaemonSet keeps `hostNetwork: true`

**Choice:** The OTel DaemonSet continues to use `hostNetwork: true`. K8s workloads expose metrics via `hostPort`, visible as `localhost:<port>` from the host network namespace.

**Rationale:** Cassandra/MAAC listens on `127.0.0.1:9000` (host loopback). A non-hostNetwork OTel pod cannot reach host loopback regardless of CNI. `hostNetwork` preserves Cassandra metrics collection unchanged while also giving OTel access to K8s workload metrics via `hostPort`.

### Decision 5: Three metrics modes

**Choice:** `scrape` (Prometheus endpoint via hostPort, OTel scrapes `localhost:<port>`), `java-agent` (OTel Java agent JAR at `/usr/local/otel/opentelemetry-javaagent.jar` mounted via hostPath into the pod, pushes OTLP to `localhost:4317`), `helm-native` (metrics configured via helm values; no registry entry or OTel sync needed).

**Rationale:** These three modes cover all realistic K8s database observability patterns. The Java agent JAR is already installed on all nodes via the base packer image. `helm-native` is a no-op from the install system — it documents intent without requiring any registry management.

## Risks / Trade-offs

**Cilium install timing** → Cilium must be fully ready before workload pods are scheduled. Mitigation: `Up.kt` installs Cilium via helm and waits for its DaemonSet to be ready before proceeding.

**OTel ConfigMap sync latency** → The kubelet propagates ConfigMap changes to mounted volumes within ~1–2 minutes. Brief window where the DaemonSet hasn't reloaded the new config after `start`. Acceptable for a lab tool.

**Brief scrape errors on stop** → The workload is stopped (by `stop` phase steps) before OTel reloads the updated config. OTel logs connection errors for ~1–2 minutes. Acceptable for a lab tool.

**hostPort port conflicts between same-type workloads** → Two instances of the same workload on the same node would conflict (e.g., two ClickHouse deployments both wanting `hostPort: 9363`). Running two instances of the same workload simultaneously is not a supported scenario.

**`helm-native` and Prometheus endpoints** → A `helm-native` workload that exposes a Prometheus endpoint won't be scraped unless the user switches it to `type: scrape`. The modes should be documented clearly in the generate-workload skill.

**Hubble metrics cardinality** → Hubble can emit high-cardinality flow metrics. Mitigation: configure Hubble to export only aggregated per-service metrics to VictoriaMetrics.

## Migration Plan

No migration required. Clusters are ephemeral. New clusters provision with Cilium from day one. The existing bespoke ClickHouse scrape job in `otel-collector-config.yaml` is removed and replaced by the dynamic registry when ClickHouse is started via `install clickhouse start`.

## Open Questions

- Which specific Hubble metrics to scrape and whether to add a Grafana network-flows dashboard.
- Should the install command warn (not error) if OTel sync runs but no `easydblab-metrics-*` ConfigMaps exist (valid on a fresh start)?
