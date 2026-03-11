## Why

The existing ClickHouse Logs dashboard is hardcoded to a single source with fixed queries. During investigations, operators need to search across all log sources (Cassandra, ClickHouse, system, tool-runner), filter dynamically by server type, host, service, log level, and job name, and correlate log volume with incidents. There is no general-purpose log investigation dashboard — users must rely on the CLI `logs query` command, which lacks the visual context (volume histograms, time correlation) needed for efficient root cause analysis.

## What Changes

- Add a new "Log Investigation" Grafana dashboard with:
  - **Log volume histogram** — time-series panel showing log count over time, broken down by source, to spot spikes and anomalies
  - **Dynamic filter variables** — dropdown filters for:
    - Server type / node role (`db`, `app`, `control`)
    - Host (`db0`, `db1`, `app0`, `control0`, etc.)
    - Log source (`cassandra`, `clickhouse`, `system`, `tool-runner`)
    - Log level (for sources that provide it)
    - Free-text search
  - **Filtered log panel** — main log viewer that respects all filter variables
  - **Live tail support** — auto-refresh interval for real-time monitoring
- Register the dashboard in `GrafanaDashboard` enum so it deploys automatically
- Update user documentation for the new dashboard

## Capabilities

### New Capabilities
- `log-investigation-dashboard`: Grafana dashboard for cross-source log investigation with dynamic filtering, volume histogram, and live tail

### Modified Capabilities

(none)

## Impact

- **New files**: Dashboard JSON resource file, enum entry in `GrafanaDashboard.kt`
- **Documentation**: Update `docs/user-guide/victoria-logs.md` or add new dashboard docs page
- **No API changes**: This is a purely additive UI dashboard
- **Dependencies**: Uses existing VictoriaLogs datasource and `victoriametrics-logs-datasource` Grafana plugin (already installed)
