## ADDED Requirements

### Requirement: S3 backup disk configured at startup
The system SHALL configure an `s3_backup` disk of type `s3_plain` in the ClickHouse config at `clickhouse start` time, pointing to the account bucket's `clickhouse-backups/` prefix, using IAM role credentials.

#### Scenario: Backup disk endpoint injected
- **WHEN** the user runs `clickhouse start`
- **THEN** the system injects `CLICKHOUSE_BACKUP_S3_ENDPOINT` into the ClickHouse ConfigMap, pointing to `https://<account-bucket>.s3.<region>.amazonaws.com/clickhouse-backups/`

#### Scenario: Backup disk allows BACKUP SQL command
- **WHEN** a ClickHouse pod has the `s3_backup` disk configured
- **THEN** `BACKUP DATABASE default TO Disk('s3_backup', 'name/')` executes without explicit credentials, using the EC2 IAM role
