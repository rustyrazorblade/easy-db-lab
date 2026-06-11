## MODIFIED Requirements

### Requirement: Version Selection Flag
The ClickHouse kit MUST accept a `--version` flag (not `--clickhouse-version`) at install time to select the ClickHouse server image version.

#### Scenario: Custom version with --version
- **WHEN** the user runs `kit install clickhouse --version 25.4`
- **THEN** the deployed ClickHouse uses image version 25.4

#### Scenario: Default version
- **WHEN** the user runs `kit install clickhouse` without `--version`
- **THEN** the kit deploys the default ClickHouse version (`latest`)
