## ADDED Requirements

### Requirement: Dashboard registration
The system SHALL register a "Log Investigation" dashboard in the `GrafanaDashboard` enum so it is automatically deployed with all other dashboards via `GrafanaManifestBuilder`.

#### Scenario: Dashboard deploys with cluster
- **WHEN** the observability stack is deployed via `grafana update-config`
- **THEN** the Log Investigation dashboard SHALL be available in Grafana

#### Scenario: Dashboard appears in Grafana UI
- **WHEN** a user opens Grafana and browses dashboards
- **THEN** the Log Investigation dashboard SHALL appear with tags `logs` and `investigation`

### Requirement: Node role filter
The dashboard SHALL provide a `node_role` template variable that allows filtering logs by server type. Options MUST include `db`, `app`, and `control`, plus an "All" option that shows all roles.

#### Scenario: Filter by database nodes
- **WHEN** user selects `db` from the node_role dropdown
- **THEN** only logs from nodes with `node_role:db` SHALL be displayed

#### Scenario: Show all roles
- **WHEN** user selects "All" or leaves node_role unset
- **THEN** logs from all node roles SHALL be displayed

### Requirement: Host filter
The dashboard SHALL provide a `host` text input variable that allows filtering logs by specific hostname (e.g., `db0`, `app0`, `control0`).

#### Scenario: Filter by specific host
- **WHEN** user enters `db0` in the host filter
- **THEN** only logs with `host:db0` SHALL be displayed

#### Scenario: Empty host filter
- **WHEN** user leaves the host filter empty
- **THEN** logs from all hosts SHALL be displayed

### Requirement: Source filter
The dashboard SHALL provide a `source` template variable that allows filtering by log source. Options MUST include `cassandra`, `clickhouse`, `system`, and `tool-runner`, plus an "All" option.

#### Scenario: Filter by tool-runner logs
- **WHEN** user selects `tool-runner` from the source dropdown
- **THEN** only logs with `source:tool-runner` SHALL be displayed

#### Scenario: Multiple source selection
- **WHEN** user selects both `cassandra` and `system`
- **THEN** logs from both sources SHALL be displayed

### Requirement: Log level filter
The dashboard SHALL provide a `level` template variable for filtering by log severity. Options MUST include common levels (`Error`, `Warning`, `Info`, `Debug`) plus "All".

#### Scenario: Filter errors only
- **WHEN** user selects `Error` from the level dropdown
- **THEN** only logs containing `level:Error` SHALL be displayed

#### Scenario: Level not available in source
- **WHEN** user selects a level filter but the log source does not emit a `level` field
- **THEN** those logs SHALL NOT be excluded (best-effort filtering)

### Requirement: Text search
The dashboard SHALL provide a text input variable for free-text search across log message content.

#### Scenario: Search for keyword
- **WHEN** user enters `timeout` in the search box
- **THEN** only logs whose message contains "timeout" SHALL be displayed

#### Scenario: Empty search
- **WHEN** user leaves the search box empty
- **THEN** no text filter SHALL be applied

### Requirement: Log volume histogram
The dashboard SHALL include a time-series panel showing log volume (count over time) that responds to all active filters.

#### Scenario: Volume spike visibility
- **WHEN** a burst of error logs occurs in a 1-minute window
- **THEN** the histogram SHALL show a visible spike at that time

#### Scenario: Filters apply to histogram
- **WHEN** user filters by `source:cassandra`
- **THEN** the histogram SHALL only count Cassandra logs

### Requirement: Log viewer panel
The dashboard SHALL include a logs panel that displays matching log entries with timestamps, source labels, and message content. The panel MUST respect all active filter variables.

#### Scenario: Log entries displayed
- **WHEN** filters are applied and matching logs exist
- **THEN** the log panel SHALL display entries sorted by time (newest first) with timestamps and labels visible

#### Scenario: Log details expandable
- **WHEN** user clicks on a log entry
- **THEN** the panel SHALL show expanded log details including all available fields

### Requirement: Live tail support
The dashboard SHALL support auto-refresh with a default interval of 10 seconds. Users MUST be able to adjust or disable the refresh interval.

#### Scenario: Auto-refresh active
- **WHEN** the dashboard is open with default settings
- **THEN** it SHALL refresh every 10 seconds showing new log entries

#### Scenario: User adjusts refresh
- **WHEN** user changes the refresh interval to 5 seconds
- **THEN** the dashboard SHALL refresh at the new interval
