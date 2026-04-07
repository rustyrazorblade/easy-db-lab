## Why

The Cassandra sidecar is currently compiled and installed during AMI builds via packer, tying each version of the sidecar to a specific AMI. This makes it impossible to change the sidecar version, test a fork, or upgrade without rebuilding the AMI.

## What Changes

- **BREAKING**: `SidecarService` interface changes from per-host `start(host)` to single `deploy(image)` call
- New `SidecarManifestBuilder` deploys the sidecar as a K3s DaemonSet on all `type=db` nodes
- Init container patterns the per-node config using the Kubernetes Downward API (`status.hostIP`)
- `cassandra start` gains a `--sidecar-image` flag (default: `ghcr.io/apache/cassandra-sidecar:latest`)
- Pyroscope Java agent injected via `JAVA_TOOL_OPTIONS` using host-mounted jar
- OTel instrumentation via environment variables over host network
- Sidecar config template moves to `configuration/sidecar/` as a ConfigMap template
- Per-host SSH config upload removed; replaced by K8s ConfigMap apply
- Packer sidecar build scripts and systemd service removed

## Capabilities

### New Capabilities

- `containerized-sidecar`: Running the Cassandra sidecar as a K3s DaemonSet on database nodes, with configurable image repo and tag, per-node config templating via init container, and full observability (OTel + Pyroscope)

### Modified Capabilities

- `cassandra`: The `cassandra start` command gains a `--sidecar-image` option controlling which container image is deployed

## Impact

- `src/main/kotlin/.../commands/cassandra/Start.kt` — add `--sidecar-image` option, change sidecar start call
- `src/main/kotlin/.../services/SidecarService.kt` — interface and implementation rewritten
- `src/main/kotlin/.../commands/SetupInstance.kt` — remove `setupSidecarSystemdEnv()`
- `src/main/kotlin/.../commands/cassandra/UpdateConfig.kt` — remove `uploadSidecarConfig()`
- `src/main/kotlin/.../Constants.kt` — remove sidecar path constants
- `src/main/resources/.../cassandra-sidecar.yaml` — moved/adapted as ConfigMap template
- `packer/cassandra/install/install_sidecar.sh` — deleted
- `packer/cassandra/cassandra.pkr.hcl` — remove sidecar build steps
- `packer/cassandra/services/cassandra-sidecar.service` — deleted
- New: `src/main/kotlin/.../configuration/sidecar/SidecarManifestBuilder.kt`
- New: `src/main/resources/.../configuration/sidecar/cassandra-sidecar.yaml`
