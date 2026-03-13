## 1. Remove Packer Sidecar Build

- [ ] 1.1 Delete or empty `packer/cassandra/install/install_sidecar.sh` — remove git clone and Gradle build
- [ ] 1.2 Delete `packer/cassandra/services/cassandra-sidecar.service` — no longer used
- [ ] 1.3 Update `packer/cassandra/cassandra.pkr.hcl` — remove the sidecar install step from the build sequence

## 2. SidecarManifestBuilder

- [ ] 2.1 Create `configuration/sidecar/SidecarManifestBuilder.kt` using Fabric8
  - DaemonSet name: `cassandra-sidecar`
  - Image: `ghcr.io/apache/cassandra-sidecar:latest`
  - `nodeSelector: {type: db}` + toleration `Exists`
  - `hostNetwork: true`, `dnsPolicy: ClusterFirstWithHostNet`
  - Volume mounts: `/etc/cassandra-sidecar` (ro), `/mnt/db1/cassandra`, `/usr/local/otel` (ro), `/usr/local/pyroscope` (ro)
  - Env vars: `HOSTNAME` (fieldRef nodeName), `CLUSTER_NAME` (configMapKeyRef), `PYROSCOPE_SERVER_ADDRESS` (configMapKeyRef), `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `JAVA_OPTS`

## 3. Rewrite SidecarService

- [ ] 3.1 Replace `interface SidecarService : SystemDServiceManager` with a new K8s-oriented interface:
  ```kotlin
  interface SidecarService {
      fun deploy(controlHost: Host)
      fun rolloutRestart(controlHost: Host)
      fun remove(controlHost: Host)
  }
  ```
- [ ] 3.2 Implement `DefaultSidecarService` using `K8sService` and `SidecarManifestBuilder`
- [ ] 3.3 Update `ServicesModule.kt` Koin registration

## 4. Update Commands

- [ ] 4.1 Update `commands/cassandra/Start.kt` — replace `sidecarService.start(host)` with `sidecarService.deploy(controlHost)`
- [ ] 4.2 Update `commands/cassandra/Stop.kt` — replace `sidecarService.stop(host)` with `sidecarService.remove(controlHost)`
- [ ] 4.3 Update `commands/cassandra/Restart.kt` — replace `sidecarService.restart(host)` with `sidecarService.rolloutRestart(controlHost)`

## 5. Clean Up SetupInstance

- [ ] 5.1 Remove `setupSidecarSystemdEnv` function from `SetupInstance.kt`
- [ ] 5.2 Remove its call site in `SetupInstance.execute()`

## 6. Update GrafanaUpdateConfig

- [ ] 6.1 Add `sidecarService.deploy(controlHost)` to `GrafanaUpdateConfig` execution
- [ ] 6.2 Add `sidecarService.rolloutRestart(controlHost)` to the rollout-restart list

## 7. Tests

- [ ] 7.1 Create `SidecarManifestBuilderTest` — verify DaemonSet fields (image, nodeSelector, volumes, env vars)
- [ ] 7.2 Add `SidecarManifestBuilder` to `K8sServiceIntegrationTest.collectAllResources()` and add an apply test
- [ ] 7.3 Rewrite `SidecarServiceTest` — test K8s-based deploy/remove/rolloutRestart behavior
- [ ] 7.4 Run `./gradlew check` to confirm all tests pass

## 8. Documentation

- [ ] 8.1 Update `configuration/CLAUDE.md` to add a `Sidecar Subpackage` section
- [ ] 8.2 Update `services/CLAUDE.md` to reflect the new SidecarService interface
- [ ] 8.3 Update `CLAUDE.md` observability section to reflect sidecar running as K8s DaemonSet
