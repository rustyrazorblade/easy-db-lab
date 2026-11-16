## 1. Fix duplication: exclude container paths from filelog/system

- [x] 1.1 Add `/var/log/containers/**` and `/var/log/pods/**` to the `exclude` list of the `filelog/system` receiver in `otel-collector-config.yaml`

## 2. Add filelog/containers receiver

- [x] 2.1 Add `filelog/containers` receiver to `otel-collector-config.yaml` that tails `/var/log/containers/*.log`
- [x] 2.2 Add exclude patterns for observability infrastructure: `/var/log/containers/*otel-collector*.log` and `/var/log/containers/*fluent-bit*.log`
- [x] 2.3 Add a `regex` operator on `attributes["log.file.path"]` to extract `k8s.pod.name`, `k8s.namespace.name`, and `k8s.container.name` from the container log file path
- [x] 2.4 Add a `container` operator (or `json_parser` + move operators) to parse CRI log format and handle multiline log recombination

## 3. Extend k8sattributes processor

- [x] 3.1 Add `pod_association` configuration to `k8sattributes` using `resource_attribute` sources for `k8s.pod.name` and `k8s.namespace.name`
- [x] 3.2 Add pod label extraction for `app.kubernetes.io/instance` to `k8sattributes`

## 4. Add logs/containers pipeline

- [x] 4.1 Add `logs/containers` pipeline to `otel-collector-config.yaml` with `receivers: [filelog/containers]`, `processors: [k8sattributes, resource/cluster, batch]`, `exporters: [otlphttp/victorialogs]`

## 5. Move container logs off boot disk

- [x] 5.1 In `packer/base/install/start_k3s_server.sh`, add symlink creation before K3s starts: `mkdir -p /mnt/db1/container-logs && ln -s /mnt/db1/container-logs /var/log/pods`
- [x] 5.2 In `packer/base/install/start_k3s_agent.sh`, add the same symlink creation before K3s agent starts

## 6. Update documentation

- [x] 6.1 Update `docs/reference/observability.md` to document the container log collection path, available log labels (`k8s.pod.name`, `k8s.namespace.name`, `k8s.container.name`, `app.kubernetes.io/instance`), and the deduplication behavior
