## Why

K8s-native kits (Presto, TiDB) log to container stdout/stderr, which the current OTel `filelog/system` receiver captures without any Kubernetes metadata — no pod name, namespace, or kit label. There is also a latent duplication bug: `filelog/system` matches `/var/log/**/*.log`, which includes `/var/log/containers/*.log`, so adding any purpose-built container log receiver would double-collect the same files.

## What Changes

- **Fix duplication bug**: Add `/var/log/containers/**` and `/var/log/pods/**` to the `filelog/system` exclude list.
- **Add `filelog/containers` receiver**: Tails `/var/log/containers/*.log`, parses CRI log format (containerd/K3s), and extracts `k8s.pod.name`, `k8s.namespace.name`, `k8s.container.name` from the file path.
- **Extend `k8sattributes` processor**: Add `pod_association` via resource attributes and extract the `app.kubernetes.io/instance` pod label, enabling kit identification in VictoriaLogs queries.
- **Add `logs/containers` pipeline**: Routes the new receiver through `k8sattributes`, `resource/cluster`, and `batch` to `otlphttp/victorialogs`.
- **Update `docs/reference/observability.md`**: Document the container log collection path and available log labels.

- **Move container logs off boot disk**: In `start_k3s_server.sh` and `start_k3s_agent.sh`, create `/mnt/db1/container-logs` and symlink `/var/log/pods` to it before K3s starts. Container log files go to NVMe storage instead of the boot volume, which has run out of space in the past.

No new DaemonSet, RBAC, or Kotlin classes. Changes are confined to `otel-collector-config.yaml`, two packer scripts, and docs.

## Capabilities

### New Capabilities

- `k8s-container-log-collection`: OTel collector collects stdout/stderr from all K8s pods, enriched with pod name, namespace, container name, and `app.kubernetes.io/instance` label. Automatically covers any K8s-native kit without per-kit configuration.

### Modified Capabilities

- `observability`: Log collection requirements updated — container logs are now a first-class collection path alongside file-based logs (system, Cassandra, ClickHouse).

## Impact

- `src/main/resources/com/rustyrazorblade/easydblab/configuration/otel/otel-collector-config.yaml` — receiver, processor, and pipeline additions; `filelog/system` exclude list updated
- `packer/base/install/start_k3s_server.sh` — symlink `/var/log/pods` → `/mnt/db1/container-logs` before K3s starts
- `packer/base/install/start_k3s_agent.sh` — same for agent nodes
- `docs/reference/observability.md` — document container log collection
