## MODIFIED Requirements

### Requirement: Tool-runner logs have accurate timestamps
Tool-runner logs collected by the OTel collector SHALL have timestamps reflecting when the output was produced, not when it was ingested.

#### Scenario: Tool logs queryable by time range in VictoriaLogs
- **WHEN** a user queries VictoriaLogs for tool-runner logs within a specific time window
- **THEN** the results SHALL include only entries that were actually produced during that window, enabling correlation with Cassandra and system logs from the same period
