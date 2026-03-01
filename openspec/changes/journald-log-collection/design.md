## Context

The existing OTel collector DaemonSet handles metrics, logs (filelog), and traces. The journald receiver was previously removed because the container lacked `journalctl`. Rather than adding journald support to the existing collector (risking all observability if it breaks), we deploy a separate lightweight OTel collector container dedicated to journald collection.

The existing collector already receives OTLP on `localhost:4317` (hostNetwork), so the journald collector just forwards there.

## Goals / Non-Goals

**Goals:**
- Deploy a separate OTel collector DaemonSet that reads systemd journal entries via chroot
- Forward journald logs to the existing OTel collector via OTLP on localhost
- Modify `exec run` to let systemd-run write to journald (default) instead of direct file output
- Isolate journald collection so failures don't affect existing log/metric/trace pipelines

**Non-Goals:**
- Modifying the existing OTel collector config or DaemonSet
- Removing `filelog/tools` receiver yet — keep it as fallback until journald is proven
- Collecting all journal entries — filter to relevant units only
- Cursor persistence / backfill of historical journal entries

## Decisions

### 1. Separate DaemonSet, not a sidecar or modification to existing collector

The journald receiver is alpha stability. Running it in the same container as the main collector means a crash or hang takes down all observability. A separate DaemonSet with its own process isolates the blast radius.

The journald collector is minimal: one receiver (journald), one exporter (OTLP to localhost:4317). No processors beyond batch and memory_limiter.

**Alternative considered:** Adding journald receiver to existing collector config — rejected due to risk to existing pipelines.

### 2. Chroot mode with host root mount

The `otel/opentelemetry-collector-contrib` image doesn't include `journalctl`. Instead of building a custom image, we mount the host's `/` as `/host` (read-only) and configure:

```yaml
receivers:
  journald:
    root_path: /host
    journalctl_path: /usr/bin/journalctl
```

This uses the host's own `journalctl` binary, guaranteeing compatibility with the host's journal format. The DaemonSet already runs privileged as root, so no security changes needed.

**Alternative considered:** Custom OTel image with journalctl bundled — rejected because it adds image maintenance burden and version mismatch risk across different AMIs.

### 3. Filter to exec-run units and key system services

Use the `grep` option to filter journal entries to relevant units:

```yaml
receivers:
  journald:
    grep: "edl-exec-"
    start_at: end
```

Start with just `exec run` tool units (`edl-exec-*` prefix). System service collection (K3s, containerd) can be added later by switching from `grep` to `units` list.

**Alternative considered:** Collecting all units — rejected to keep initial scope small and volume manageable.

### 4. Remove StandardOutput=file: from ExecRun

Currently `buildSystemdRunCommand` explicitly routes stdout/stderr to files:
```
--property=StandardOutput=file:/var/log/easydblab/tools/{name}.log
--property=StandardError=file:/var/log/easydblab/tools/{name}.log
```

Remove these properties. Systemd's default is to route output to the journal. The journald collector picks it up with proper timestamps. For foreground commands (`--wait`), the output is still captured by SSH and returned to the user.

The `filelog/tools` receiver stays in the main collector as a fallback — if someone still writes to that directory, it gets collected.

### 5. Fabric8 manifest builder following existing patterns

New `JournaldOtelManifestBuilder` in `configuration/otel/` following the same pattern as `OtelManifestBuilder`:
- `buildAllResources()` returns ConfigMap + DaemonSet
- No ServiceAccount/RBAC needed (doesn't access K8s API)
- Deployed via `GrafanaUpdateConfig` alongside other observability resources
- Config loaded via `TemplateService` from classpath resource

## Risks / Trade-offs

- **Alpha stability** → Mitigated by isolation in separate DaemonSet. Main collector unaffected if journald collector crashes.
- **Host root mount** → Read-only mount of `/` at `/host`. Container is already privileged, so this doesn't increase the security surface.
- **Journal volume on busy nodes** → `start_at: end` skips history. `grep: "edl-exec-"` limits to tool-runner entries only. Can expand scope later.
- **Foreground exec output** → After removing `StandardOutput=file:`, foreground commands still work because `systemd-run --wait` captures exit status and SSH captures the remote command output. But the log file at `/var/log/easydblab/tools/` won't be written. The foreground code path reads this file after execution — need to update that path to read from journalctl instead.
