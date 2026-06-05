## Context

The OTel Collector DaemonSet already runs on every node with `hostNetwork`, mounts `/var/log` as a read-only hostPath, and has a ServiceAccount with `get/watch/list` on pods and nodes. The `k8sattributes` processor already uses that ServiceAccount to extract node labels (`node_role`). The `filelog/system` receiver collects `/var/log/**/*.log`, which silently includes `/var/log/containers/*.log` — raw CRI-formatted log lines with no Kubernetes metadata attached.

K3s writes all pod stdout/stderr as CRI-formatted log files at:
- `/var/log/containers/<pod>_<namespace>_<container>-<id>.log` — symlinks
- `/var/log/pods/<namespace>_<pod>_<uid>/<container>/<seq>.log` — actual files

The OTel Collector is the only required change. No new process, RBAC, or Kubernetes object is needed.

## Goals / Non-Goals

**Goals:**
- Collect stdout/stderr from all K8s pods with pod name, namespace, container name, and `app.kubernetes.io/instance` label attached as log attributes
- Fix the duplication bug where `/var/log/containers/` is already captured by `filelog/system`
- Require zero per-kit configuration — any new K8s-native kit is covered automatically

**Non-Goals:**
- Migrating Cassandra log collection (file-based, stays in `filelog/cassandra`; ClickHouse logs via container stdout and is covered by `filelog/containers`)
- Log parsing or structured extraction beyond CRI format and K8s metadata
- Changes to the Fluent Bit journald DaemonSet
- New Kotlin classes, RBAC resources, or ports

## Decisions

### Decision: Extend OTel `filelog` receiver, not a new Fluent Bit DaemonSet

The original design proposed a Fluent Bit DaemonSet for container log collection. Fluent Bit was chosen for journald collection because the OTel Collector container image lacks `journalctl`. That limitation does not apply to file-based container logs — the `filelog` receiver handles them natively, and `k8sattributes` already provides pod label enrichment.

Adding Fluent Bit would require a new DaemonSet, new RBAC, a new port, and a new Kotlin builder class for no capability gain. The OTel approach is a config-only change to an already-running process.

### Decision: Separate `logs/containers` pipeline, not merged into `logs/local`

`logs/local` routes file-based system/tool/Cassandra/ClickHouse logs. Adding `filelog/containers` to it would apply the same processors — but `k8sattributes` pod association only makes sense for container logs, not host-file logs. Keeping them in separate pipelines avoids processor side-effects and makes each pipeline's intent clear.

### Decision: `pod_association` via resource attributes, not connection IP

`k8sattributes` defaults to associating telemetry with pods by source IP. For file-based log collection there is no connection IP — the OTel collector reads files directly from the host filesystem. Resource attributes (`k8s.pod.name` + `k8s.namespace.name`) extracted from the container log file path are the correct association mechanism.

### Decision: Parse file path with `regex` operator to extract K8s attributes

K3s writes container log file names in the format:
```
/var/log/containers/<pod-name>_<namespace>_<container-name>-<container-id>.log
```
A `regex` operator on `attributes["log.file.path"]` extracts pod name, namespace, and container name as resource attributes. These are then used by `k8sattributes` to look up pod labels via the K8s API.

The OTel Collector contrib image also ships a `container` log format parser for CRI format (`timestamp stream flags message`). Using it as a `recombine`/`container` operator handles multiline logs and strips the CRI framing, leaving only the log body.

## Risks / Trade-offs

- **Self-collection loop**: The OTel Collector DaemonSet itself runs as a pod, so its own logs appear in `/var/log/containers/`. Excluding the `otel-collector` container from `filelog/containers` prevents a feedback loop. Add an exclude pattern: `/var/log/containers/*otel-collector*.log`.
- **Fluent Bit journald DaemonSet logs**: Similarly, exclude `/var/log/containers/*fluent-bit*.log` to avoid collecting our own observability infrastructure logs.
- **CRI multiline logs**: Containerd splits log lines longer than 16KB across multiple CRI records with `P` (partial) flag. The `container` operator in OTel handles recombination. Without it, long stack traces appear as fragments.
- **`k8sattributes` latency**: Pod metadata lookup is async and cached. Logs from very short-lived pods may not get label enrichment if the pod is deleted before the cache populates. Acceptable for lab use.

## Migration Plan

1. Update `otel-collector-config.yaml`: add excludes to `filelog/system`, add `filelog/containers` receiver, extend `k8sattributes`, add `logs/containers` pipeline.
2. Run `grafana update-config` (or `easy-db-lab up`) to apply the new ConfigMap and trigger a DaemonSet rollout restart.
3. No rollback complexity — the change is additive. Reverting means removing the new receiver and pipeline from the config.

## Open Questions

None. All decisions are resolved.
