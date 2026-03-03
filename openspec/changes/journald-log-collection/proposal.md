## Why

Commands run via `exec run` produce logs with no timestamps. The `filelog/tools` receiver sends raw text to VictoriaLogs, which assigns ingestion-time timestamps. This makes it impossible to correlate tool output (e.g., `inotifywait` file events) with Cassandra or system logs during the same time window.

Beyond tool-runner, there's a broader gap: systemd journal entries from K3s services and other system daemons aren't collected at all (the journald receiver was removed because the OTel container lacked `journalctl`).

## What Changes

- **New DaemonSet** — a separate OTel collector container dedicated to journald collection, isolated from the existing collector so failures don't affect current log/metric collection
- The journald collector uses chroot mode (`root_path: /host`, `journalctl_path: /usr/bin/journalctl`) with host `/` mounted read-only at `/host`
- Forwards logs via OTLP to the existing OTel collector on localhost (which already exports to VictoriaLogs)
- Modify `ExecRun.kt` to remove `StandardOutput=file:` properties so tool output goes to journald (systemd default behavior) instead of direct file writes
- Journald automatically timestamps every entry — no wrapper needed
- Eventually remove `filelog/tools` receiver from the main collector once journald is confirmed working

## Capabilities

### New Capabilities
- `journald-log-collection`: Dedicated OTel collector DaemonSet for journald log collection, forwarding to the main collector via OTLP

### Modified Capabilities
- `observability`: Tool-runner and systemd service logs gain proper timestamps for cross-service correlation

## Impact

- **New file**: `configuration/otel/JournaldOtelManifestBuilder.kt` — Fabric8 builder for the journald collector DaemonSet + ConfigMap
- **New file**: `configuration/otel/otel-journald-config.yaml` — OTel config with journald receiver + OTLP exporter
- **Modified file**: `ExecRun.kt` — removing `StandardOutput=file:` properties so output goes to journald
- **Modified file**: `GrafanaUpdateConfig.kt` or equivalent deploy command — deploying the new DaemonSet
- **No changes** to the existing OTel collector config or DaemonSet — it already receives OTLP on port 4317
- **Risk**: Journald receiver is alpha stability — isolated in its own container so failure doesn't affect existing collection
- **Risk**: Journal volume could be large on busy nodes. `start_at: end` (default) avoids replaying history
