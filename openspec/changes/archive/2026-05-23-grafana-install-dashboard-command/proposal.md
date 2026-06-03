## Why

Workload-specific Grafana dashboards (e.g., ClickHouse) cannot be installed from `start.sh` today — there is no CLI command to push a dashboard JSON to Grafana. Additionally, Grafana's data volume is an `emptyDir`, so any dashboards stored in Grafana's SQLite database are lost every time the pod restarts or `grafana update-config` is run.

## What Changes

- **Grafana data volume**: Change from `emptyDir` to `hostPath(/mnt/db1/grafana)` so Grafana's SQLite database (dashboards, preferences, sessions) survives pod restarts.
- **New command `grafana install <path>`**: Uploads a dashboard JSON file to Grafana via the HTTP API (`POST /api/dashboards/db`). Uses OkHttp (already a dependency). No authentication required — anonymous access is already configured as Admin.
- **ClickHouse install template**: Add a `dashboards/` subdirectory containing `clickhouse.json` and `clickhouse-logs.json` (copied from the existing top-level `dashboards/` directory). Update `start.sh.template` to call `grafana install` for each dashboard file.

## Capabilities

### New Capabilities

- `grafana-install-dashboard`: New `grafana install` subcommand that accepts a path to a dashboard JSON and uploads it to the running Grafana instance via HTTP API.

### Modified Capabilities

- `install-command`: The ClickHouse install template gains a `dashboards/` subdirectory and the generated `start.sh` calls `grafana install` per dashboard file.

## Impact

- `GrafanaManifestBuilder` — one-line volume change
- New `GrafanaInstall` command class
- `Grafana.kt` — register new subcommand
- `install/clickhouse/` classpath resources — add `dashboards/` subdir
- `install/clickhouse/start.sh.template` — add dashboard install loop
- `CommandLineParser.kt` — no change needed (Grafana subcommands registered via annotation)
- OkHttp already in dependencies — no new deps
