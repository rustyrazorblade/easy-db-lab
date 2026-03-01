## Context

The cluster's observability stack collects logs from multiple sources (Cassandra, ClickHouse, system, tool-runner) via OTel Collector and stores them in VictoriaLogs. Currently, only a basic ClickHouse-specific log dashboard exists with hardcoded queries. The CLI `logs query` command provides text-based access but lacks visual investigation features like volume histograms and time correlation.

VictoriaLogs exposes logs via LogsQL query language with field:value filtering. The `victoriametrics-logs-datasource` Grafana plugin is already installed and configured with uid `victorialogs`.

Available log attributes: `source` (cassandra, clickhouse, system, tool-runner), `node_role` (db, app, control), `host` (hostname), `unit` (systemd unit), `component` (ClickHouse: server/keeper), `level` (where available), and free-text in `_msg`.

## Goals / Non-Goals

**Goals:**
- Single dashboard for investigating logs across all sources
- Dynamic filtering via Grafana template variables (no hardcoded queries)
- Log volume visualization to identify anomalies and spikes
- Live tail capability for real-time monitoring
- Seamless integration into existing dashboard deployment pipeline

**Non-Goals:**
- Alerting rules based on log patterns (separate concern)
- Log aggregation/analytics (counts by error type, top-N, etc.)
- Replacing the CLI `logs query` command
- Cross-linking to traces via trace IDs (future enhancement)

## Decisions

### 1. Use Grafana template variables for all filters

**Decision**: Implement filters as Grafana template variables (`custom` type for fixed values, `query` type where VictoriaLogs supports field enumeration) rather than hardcoded LogsQL expressions.

**Rationale**: Template variables appear as dropdowns at the top of the dashboard, are familiar to Grafana users, and compose naturally into LogsQL queries via variable interpolation. The alternative — ad-hoc filter widget — is less discoverable and doesn't support predefined choices.

**Variables**:
- `node_role` — custom multi-value: `All, db, app, control`
- `host` — custom text input (hosts vary per cluster)
- `source` — custom multi-value: `All, cassandra, clickhouse, system, tool-runner`
- `level` — custom multi-value: `All, Error, Warning, Info, Debug`
- `search` — text box for free-text search

### 2. Use LogsQL query composition in panel targets

**Decision**: Build LogsQL expressions using Grafana variable interpolation: `${source:lucene} ${node_role:lucene} ${level:lucene} ${search}`. Each variable contributes a filter clause only when set.

**Rationale**: LogsQL supports field:value syntax natively. Grafana's variable interpolation with the `:lucene` format option produces compatible filter expressions. When a variable is set to "All" or empty, it should be excluded from the query.

### 3. Dashboard layout: histogram on top, logs below

**Decision**: Two-row layout:
- Row 1: Log volume histogram (timeseries panel, full width, ~6 units tall)
- Row 2: Log viewer panel (logs panel, full width, ~18 units tall)

Both panels share the same template variables so filtering is synchronized.

**Rationale**: This is the standard investigation layout (Kibana, Loki, Datadog). The histogram provides context for where to look, the log panel shows the detail. Keeping it simple with two panels avoids overwhelming users and keeps the dashboard fast.

### 4. Auto-refresh for live tail

**Decision**: Set default refresh to `10s` (matching other dashboards) with the Grafana refresh picker enabled for users to adjust (5s, 10s, 30s, 1m, off).

**Rationale**: 10s is responsive enough for investigation without overwhelming VictoriaLogs with queries. Users can increase frequency during active incidents.

### 5. Dashboard JSON as a static resource file

**Decision**: Store dashboard as a static JSON file in `configuration/grafana/dashboards/log-investigation.json` with `__KEY__` template substitution where needed, following the existing pattern.

**Rationale**: All other dashboards follow this pattern. The GrafanaManifestBuilder dynamically processes all `GrafanaDashboard` enum entries, so adding a new dashboard is just an enum entry + JSON file.

## Risks / Trade-offs

- **VictoriaLogs variable interpolation** — LogsQL syntax may not perfectly align with Grafana variable formatting. → Mitigation: Test query composition manually in Grafana Explore before finalizing the JSON.
- **No log level for all sources** — Cassandra and system logs may not have a parsed `level` field. → Mitigation: Level filter applies best-effort; when level isn't available, logs still show (they just can't be filtered by level).
- **Tool-runner job name filtering** — Tool runner logs are identified by filename pattern, not a structured `job_name` field. Filtering by job name may require matching on the log file path or a text search. → Mitigation: Use text search or document the exec `--name` convention. If a structured field is needed, that's a separate OTel collector change.
