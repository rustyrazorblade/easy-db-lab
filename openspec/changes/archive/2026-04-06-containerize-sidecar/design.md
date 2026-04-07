## Context

The Cassandra sidecar is currently built from source during packer AMI creation, installed as a native binary at `/usr/local/cassandra-sidecar`, and managed as a systemd service. This couples the sidecar version to the AMI, making it impossible to test a fork or upgrade without a full AMI rebuild. The Apache project publishes container images at `ghcr.io/apache/cassandra-sidecar`, providing a ready-made path to containerization.

The sidecar has one awkward constraint: its config file requires the node's private IP address explicitly — it cannot use `127.0.0.1`. This must be resolved per-node at runtime.

## Goals / Non-Goals

**Goals:**
- Replace packer-built sidecar with a K3s DaemonSet running a published container image
- Support specifying image repo and tag at `cassandra start` time
- Maintain full observability: OTel traces/metrics + Pyroscope Java profiling
- Keep the same sidecar config semantics (data dirs, staging dir, port 9043)
- Remove all packer sidecar build steps

**Non-Goals:**
- Building or publishing custom sidecar images (user provides the image reference)
- Changing the sidecar's behavior or configuration schema
- Containerizing any other systemd-managed service on DB nodes

## Decisions

### K8s Resource: DaemonSet with nodeSelector

**Decision**: Use a DaemonSet with `nodeSelector: { type: db }` and `hostNetwork: true`.

DaemonSet ensures exactly one sidecar pod per Cassandra node. `hostNetwork: true` is required because the sidecar connects to the local Cassandra instance on `localhost:9042` and JMX on `127.0.0.1:7199` — it must share the host's network namespace. This is the same pattern used by OtelManifestBuilder.

**Alternative considered**: One Deployment per node with per-node nodeSelector (`node-ordinal`). Rejected — more K8s objects to manage, no meaningful benefit over DaemonSet + nodeSelector.

### Per-Node IP: Init Container + Downward API

**Decision**: Use a busybox init container that reads `status.hostIP` from the Kubernetes Downward API and templates the sidecar config file into a shared emptyDir volume.

The sidecar config requires the node's private IP to register itself correctly. In a DaemonSet, all pods share one ConfigMap, but each needs a different IP. The Downward API provides `status.hostIP` (the EC2 private IP of the node the pod is scheduled on) as an environment variable. The init container runs `sed` to substitute `__HOST_IP__` in the config template and writes the result to `/etc/cassandra-sidecar/cassandra-sidecar.yaml` on the emptyDir.

**Alternative considered**: Per-node ConfigMaps (one per `node-ordinal`). Rejected — DaemonSets cannot select different ConfigMaps per node; would require switching to per-node Deployments, adding significant complexity.

**Alternative considered**: Using `localhost` / `127.0.0.1` in config. Rejected — the sidecar explicitly requires the private IP; using localhost breaks sidecar registration.

### Image Configuration: Single `--sidecar-image` Flag

**Decision**: Add `--sidecar-image` option to `cassandra start`, defaulting to `ghcr.io/apache/cassandra-sidecar:latest`.

A single image reference (repo + tag combined) matches standard container image syntax and is familiar. The builder accepts the image string and substitutes it into the DaemonSet spec at apply time.

**Alternative considered**: Separate `--sidecar-repo` and `--sidecar-tag` flags. Rejected — more verbose with no benefit; image references are a single string everywhere else in container tooling.

### Pyroscope: Host-Mounted Java Agent via JAVA_TOOL_OPTIONS

**Decision**: Mount `/usr/local/pyroscope` from the host as a read-only hostPath volume and inject the agent via `JAVA_TOOL_OPTIONS`.

The Pyroscope Java agent (`pyroscope.jar`) is already installed on all Cassandra nodes by packer. The JVM automatically picks up `JAVA_TOOL_OPTIONS` before starting, allowing agent injection into a pre-built container image without modification. `NODE_NAME` (from `spec.nodeName` Downward API) and `CLUSTER_NAME` (from cluster ConfigMap) provide the labels.

**Alternative considered**: Grafana Alloy eBPF profiling (already running on all nodes). Rejected by requirement — Java agent profiling (heap allocations, lock contention at JVM level) must be preserved.

**Alternative considered**: Custom image wrapping the official image with the agent included. Rejected — adds image publishing complexity and defeats the purpose of using the published image.

### SidecarService Interface: deploy(image) replaces start(host)

**Decision**: Replace the per-host `start(host): Result<Unit>` method with `deploy(image: String): Result<Unit>` that applies the DaemonSet once.

The current interface loops over hosts and calls `start(host)` for each. With K8s, the DaemonSet is applied once and K8s schedules it to all `type=db` nodes. The service no longer needs host-level granularity. `cassandra start` calls `sidecarService.deploy(image)` once after all Cassandra nodes are up.

### Config Template Location

**Decision**: Move config template to `configuration/sidecar/cassandra-sidecar.yaml` (classpath resource loaded via TemplateService), consistent with OtelManifestBuilder, BeylaManifestBuilder, etc.

The existing `commands/cassandra-sidecar.yaml` template had per-host IP substitution done in Kotlin. The new template uses `__HOST_IP__` as a placeholder substituted by the init container at pod startup, not by Kotlin code.

## Risks / Trade-offs

- **Init container image availability** → `busybox` must be pullable on all DB nodes. Risk is low; busybox is available in any container registry mirror and is tiny. If the cluster has no internet access from DB nodes, the registry mirror must include busybox.

- **JAVA_TOOL_OPTIONS injection** → Some sidecar versions may set their own `JAVA_TOOL_OPTIONS`, potentially conflicting. Mitigation: use `JAVA_TOOL_OPTIONS` (not `JDK_JAVA_OPTIONS`) which is additive and universally supported. Monitor sidecar startup logs for agent load confirmation.

- **hostPath pyroscope.jar must exist** → If the packer-built pyroscope path ever changes, the volume mount silently fails and the agent doesn't load. Mitigation: add a readiness check or startup log assertion in `SidecarService.deploy()`.

- **DaemonSet rollout on cassandra start** → Applying a DaemonSet does not guarantee all pods are Running before `cassandra start` returns. The existing `SidecarStartFailed` event handling (non-blocking) is preserved — sidecar start failure does not block Cassandra.

## Migration Plan

1. Deploy new AMI (packer sidecar build steps removed — AMI build is faster)
2. Run `cassandra start` — DaemonSet is applied, K3s schedules sidecar pods on all DB nodes
3. Existing `systemctl`-based sidecar binary at `/usr/local/cassandra-sidecar` remains on old AMIs but is no longer started

No rollback procedure required — the DaemonSet can be deleted via `kubectl delete daemonset cassandra-sidecar` if needed. Old AMIs retain the native binary for emergency fallback.

## Open Questions

- None — all design decisions resolved in exploration session.
