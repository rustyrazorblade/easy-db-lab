## REMOVED Requirements

### Requirement: Vector log collection agent
**Reason**: Vector is replaced by OTel for all log collection. OTel already runs on every node and handles ClickHouse logs. Consolidating on a single agent simplifies the stack.
**Migration**: System logs, Cassandra logs, and journald collection are migrated to OTel receivers.

### Requirement: SQS-based EMR log ingestion
**Reason**: The S3 -> SQS -> Vector pipeline was unreliable and blocked provisioning.
**Migration**: No replacement. EMR logs remain in S3.

## MODIFIED Requirements

### Requirement: Log collection pipeline
The system SHALL collect logs from all cluster nodes using the OpenTelemetry Collector DaemonSet and store them in VictoriaLogs.

The OTel Collector SHALL collect the following log sources:
- ClickHouse server logs from `/mnt/db1/clickhouse/logs/*.log` (existing)
- ClickHouse Keeper logs from `/mnt/db1/clickhouse/keeper/logs/*.log` (existing)
- System logs from `/var/log/**/*.log`, `/var/log/messages`, `/var/log/syslog` (new)
- Cassandra logs from `/mnt/db1/cassandra/logs/*.log` (new)
- Systemd journal entries for `cassandra.service`, `docker.service`, `k3s.service`, `sshd.service` (new)

Each log source SHALL have a `source` attribute identifying its origin (`system`, `cassandra`, `systemd`). ClickHouse logs SHALL retain their existing `database: clickhouse` attribute.

#### Scenario: System logs collected by OTel
- **WHEN** a cluster node writes to `/var/log/syslog`
- **THEN** OTel Collector ingests the log entry and forwards it to VictoriaLogs with `source: system`

#### Scenario: Cassandra logs collected by OTel
- **WHEN** Cassandra writes to `/mnt/db1/cassandra/logs/system.log`
- **THEN** OTel Collector ingests the log entry and forwards it to VictoriaLogs with `source: cassandra`

#### Scenario: Journald entries collected by OTel
- **WHEN** the `cassandra.service` systemd unit emits a journal entry
- **THEN** OTel Collector ingests the entry and forwards it to VictoriaLogs with `source: systemd`
