## Why

Building the Cassandra Sidecar from source during every Packer AMI build adds 5–10 minutes of compile time (`git clone` + `./gradlew shadowjar`). The Apache Cassandra project now publishes pre-built container images to `ghcr.io/apache/cassandra-sidecar:latest`, making the source build unnecessary.

Running the sidecar as a K8s DaemonSet (managed by k3s, already on every cluster node) instead of a bare systemd service gives us lifecycle management, rolling restarts, and consistent env-var injection — matching how all other observability collectors on the cluster are managed.

## What Changes

- Remove `packer/cassandra/install/install_sidecar.sh` — no more git clone or Gradle build
- Remove `packer/cassandra/services/cassandra-sidecar.service` — no more bare systemd management
- Add `SidecarManifestBuilder` — Fabric8-based DaemonSet targeting `type=db` nodes using `ghcr.io/apache/cassandra-sidecar:latest`
- Rewrite `SidecarService` — from `SystemDServiceManager` (per-host SSH systemctl) to K8s cluster-level operations (`deploy`, `rolloutRestart`, `remove`)
- Update `Start`, `Stop`, `Restart` commands — call new K8s-based service methods
- Remove `setupSidecarSystemdEnv` from `SetupInstance` — OTel/Pyroscope env vars are now injected via K8s DaemonSet spec using fieldRef and ConfigMap references
- Update `GrafanaUpdateConfig` — deploy sidecar DaemonSet alongside other observability stack components

## Capabilities

### New Capabilities

- `sidecar-k8s`: Cassandra Sidecar running as a K8s DaemonSet on db nodes, managed by k3s

### Modified Capabilities

- `sidecar-otel`: OTel and Pyroscope instrumentation env vars are now passed via K8s DaemonSet spec, not systemd EnvironmentFile

## Impact

- `packer/cassandra/install/install_sidecar.sh` — removed (or emptied)
- `packer/cassandra/services/cassandra-sidecar.service` — removed
- `packer/cassandra/cassandra.pkr.hcl` — remove sidecar build step; no new step needed (image pulled at DaemonSet deploy time)
- New `configuration/sidecar/SidecarManifestBuilder.kt` — Fabric8 DaemonSet builder
- `services/SidecarService.kt` — rewritten to K8s-based interface
- `commands/cassandra/Start.kt`, `Stop.kt`, `Restart.kt` — updated to call K8s service
- `commands/SetupInstance.kt` — `setupSidecarSystemdEnv` removed
- `commands/grafana/GrafanaUpdateConfig.kt` — deploys sidecar DaemonSet
- New AMI build NOT required — image is pulled at deploy time by k3s
