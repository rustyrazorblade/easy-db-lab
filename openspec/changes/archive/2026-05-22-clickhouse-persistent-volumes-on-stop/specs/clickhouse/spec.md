## MODIFIED Requirements

### Requirement: Lifecycle Management
The system MUST support installing, starting, stopping, and uninstalling ClickHouse deployments. Stopping MUST preserve data; data is only deleted on uninstall. Starting after a stop MUST resume the existing dataset without any additional user steps.

#### Scenario: Stop removes K8s resources but not data
- **WHEN** the user runs `clickhouse stop`
- **THEN** the ClickHouseInstallation and NodePort service are deleted, but PVs and on-disk data remain

#### Scenario: Start after stop resumes data
- **WHEN** data is written to ClickHouse, `clickhouse stop` is run, and then `clickhouse start` is run again
- **THEN** the previously written data is accessible without any restore step

#### Scenario: Fresh start creates storage
- **WHEN** `clickhouse start` is run after a fresh install with no prior starts
- **THEN** PVs are created and the ClickHouseInstallation is deployed successfully

#### Scenario: Status shows deployment state
- **WHEN** the user checks status of a running ClickHouse cluster
- **THEN** the deployment state is displayed
