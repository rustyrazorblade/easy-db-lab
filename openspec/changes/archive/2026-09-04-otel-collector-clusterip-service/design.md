## Context

The OTel collector DaemonSet runs with `hostNetwork: true` on every K8s node. It binds the OTLP gRPC port (4317) and HTTP port (4318) directly on the node's host network. This works well for other host-networked workloads (e.g., Fluent Bit, which uses `127.0.0.1:4318`), but standard K8s pods (non-hostNetwork) have no way to address the collector at a stable location — they would need to know the specific node IP, which is dynamic and different per pod.

The TiDB Operator creates standard K8s pods and sets the OTel endpoint via a static string in the `TidbCluster` CRD. There is currently no way to use a dynamic node IP in that config.

## Goals / Non-Goals

**Goals:**
- Provide a stable in-cluster DNS name for the OTel OTLP gRPC endpoint
- Enable TiDB (and any future non-hostNetwork workload) to push traces without node-IP knowledge
- Keep the change additive — no existing behaviour changes

**Non-Goals:**
- Changing the DaemonSet's `hostNetwork` setting (host-networked scraping must continue to work)
- Exposing the OTel collector outside the cluster
- Load balancing or HA for the collector (DaemonSet already ensures one per node)

## Decisions

### Add a ClusterIP Service to `OtelManifestBuilder`

A Kubernetes ClusterIP Service selects the DaemonSet pods by label and exposes port 4317. Kube-proxy (or Cilium's eBPF replacement) handles routing from the virtual ClusterIP to whichever DaemonSet pod is scheduled — typically the one on the same node, but correctness is guaranteed either way since all collector pods share the same backend (VictoriaMetrics, Tempo).

**Alternatives considered:**
- *NodePort Service*: Exposes the port on every node's host IP, but requires knowing a node IP — same problem as before, just shifted.
- *Removing `hostNetwork`*: Would break Fluent Bit (`127.0.0.1:4318`) and all Prometheus scrape targets that use `localhost:<port>` (Cilium Hubble, Beyla, ebpf_exporter). Not viable.
- *Downward API injection*: The TiDB Operator supports `additionalEnvs` with `status.hostIP`, but TiDB's `opentelemetry.endpoint` config is a static string with no env var substitution support.

### Service name: `otel-collector` in namespace `default`

Matches the DaemonSet name and namespace. DNS resolves as `otel-collector.default.svc.cluster.local`. Short form `otel-collector` works within the same namespace.

### Only expose gRPC port (4317) in the Service

Port 4318 (HTTP) is used only by Fluent Bit on localhost — no in-cluster consumer needs it via Service. Keeping the Service minimal reduces surface area.

## Risks / Trade-offs

- *kube-proxy routing may not prefer the local DaemonSet pod*: In theory, a TiDB pod on node A could have its trace traffic routed to the OTel collector on node B. In practice this is fine — all OTel collectors forward to the same Tempo backend, and the added latency is negligible in a lab cluster.
- *Service is not needed by most existing workloads*: It adds a small amount of cluster state for a feature only TiDB currently uses. This is acceptable; the Service is inert unless addressed.
