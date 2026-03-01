## 1. Journald OTel Collector Config

- [x] 1.1 Create `otel-journald-config.yaml` resource file with journald receiver (chroot mode: `root_path: /host`, `journalctl_path: /usr/bin/journalctl`), grep filter for `edl-exec-`, batch processor, memory_limiter, and OTLP gRPC exporter to `localhost:4317`
- [x] 1.2 Create `JournaldOtelManifestBuilder.kt` Fabric8 builder with `buildAllResources()` returning ConfigMap + DaemonSet. DaemonSet runs on all nodes with hostNetwork, privileged, runAsUser 0, host `/` mounted read-only at `/host`

## 2. Deploy Integration

- [x] 2.1 Register `JournaldOtelManifestBuilder` in Koin (`ServicesModule.kt`)
- [x] 2.2 Inject into `GrafanaUpdateConfig` and add `applyFabric8Resources("OTel Journald", controlNode, journaldOtelManifestBuilder.buildAllResources())` call

## 3. ExecRun Changes

- [x] 3.1 Remove `--property=StandardOutput=file:` and `--property=StandardError=file:` from `buildSystemdRunCommand` so output goes to journald
- [x] 3.2 Update foreground code path to read output from journal (`journalctl --unit=edl-exec-{name} --no-pager`) instead of `cat $logFile`

## 4. Testing

- [x] 4.1 Add `JournaldOtelManifestBuilder` to `K8sServiceIntegrationTest.collectAllResources()` with apply test, image pull test, and no-resource-limits test
- [x] 4.2 Update `ExecRunTest` for the changed `buildSystemdRunCommand` output (no StandardOutput/StandardError properties) and the journalctl read path
- [ ] 4.3 Deploy to a running cluster and verify tool-runner journal entries appear in VictoriaLogs with proper timestamps

## 5. Documentation

- [x] 5.1 Update `docs/reference/commands.md` exec run section to note that tool output is captured via systemd journal
- [x] 5.2 Update CLAUDE.md observability section to document the journald OTel collector DaemonSet
