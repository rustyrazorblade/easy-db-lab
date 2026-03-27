## 1. Config Template

- [x] 1.1 Create `src/main/resources/com/rustyrazorblade/easydblab/configuration/sidecar/cassandra-sidecar.yaml` — adapt existing template to use `__HOST_IP__` placeholder instead of per-host injection
- [x] 1.2 Verify the config template covers all required paths: data dirs, commit log, hints, saved caches, import staging, log dir, port 9043

## 2. SidecarManifestBuilder

- [x] 2.1 Create `src/main/kotlin/com/rustyrazorblade/easydblab/configuration/sidecar/SidecarManifestBuilder.kt` with `buildAllResources(image: String): List<HasMetadata>`
- [x] 2.2 Build ConfigMap from the `cassandra-sidecar.yaml` template resource via TemplateService
- [x] 2.3 Build DaemonSet with `nodeSelector: type=db`, `hostNetwork: true`, `dnsPolicy: ClusterFirstWithHostNet`
- [x] 2.4 Add init container (busybox) that reads `status.hostIP` from Downward API and runs `sed` to substitute `__HOST_IP__` in the config template, writing result to emptyDir
- [x] 2.5 Configure main container: image param, `runAsUser: 999`, `runAsGroup: 999`
- [x] 2.6 Add volume mounts: emptyDir → `/etc/cassandra-sidecar`, hostPath `/mnt/db1/cassandra` → `/mnt/db1/cassandra`, hostPath `/usr/local/pyroscope` → `/usr/local/pyroscope` (readOnly)
- [x] 2.7 Add `JAVA_TOOL_OPTIONS` env var with Pyroscope agent config; source `NODE_NAME` from `spec.nodeName` Downward API and `CLUSTER_NAME` + control node IP from cluster ConfigMap
- [x] 2.8 Add `OTEL_EXPORTER_OTLP_ENDPOINT` and `OTEL_SERVICE_NAME` env vars

## 3. SidecarService

- [x] 3.1 Update `SidecarService` interface: replace `start(host: Host): Result<Unit>` with `deploy(image: String): Result<Unit>` and add `undeploy(): Result<Unit>`
- [x] 3.2 Rewrite `DefaultSidecarService` to use `SidecarManifestBuilder` and `K8sService.applyResource()` / delete resource for undeploy
- [x] 3.3 Register updated service in Koin modules

## 4. cassandra start Command

- [x] 4.1 Add `--sidecar-image` option to `Start.kt` with default `ghcr.io/apache/cassandra-sidecar:latest`
- [x] 4.2 Replace the per-host `sidecarService.start(host)` loop with a single `sidecarService.deploy(sidecarImage)` call after all Cassandra nodes are up
- [x] 4.3 Keep `SidecarStartFailed` event emission on failure (non-blocking)

## 5. Remove SSH-Based Config Upload

- [x] 5.1 Remove `uploadSidecarConfig()` method from `UpdateConfig.kt`
- [x] 5.2 Remove `setupSidecarSystemdEnv()` from `SetupInstance.kt`
- [x] 5.3 Remove `CASSANDRA_REMOTE_SIDECAR_DIR` and `CASSANDRA_REMOTE_SIDECAR_CONFIG` constants from `Constants.kt`
- [x] 5.4 Remove `CASSANDRA_SIDECAR_CONFIG` local path constant if no longer referenced

## 6. Packer Cleanup

- [x] 6.1 Delete `packer/cassandra/install/install_sidecar.sh`
- [x] 6.2 Delete `packer/cassandra/services/cassandra-sidecar.service`
- [x] 6.3 Remove sidecar build steps from `packer/cassandra/cassandra.pkr.hcl` (install script invocation + sidecar dir creation)

## 7. Documentation

- [x] 7.1 Update docs for `cassandra start` to document `--sidecar-image` option
- [x] 7.2 Update `CLAUDE.md` or relevant subdirectory CLAUDE.md if architecture notes reference the sidecar systemd pattern
