# ClickHouse (delta)

Updates the ClickHouse spec to reflect how the `s3_backup` disk is now configured.

## MODIFIED Requirements

### Requirement: REQ-CH-004: S3 Backup Disk Declared in Installation Template

The system SHALL declare an `s3_backup` disk of type `s3_plain` directly in `clickhouseinstallation.yaml.template`, using `use_environment_credentials` for IAM role authentication and `ACCOUNT_BUCKET` / `REGION` template variables for the S3 endpoint. No separate ConfigMap or Kotlin service call is required at start time.

#### Scenario: s3_backup disk present after clickhouse start
- **WHEN** the user runs `easy-db-lab clickhouse start`
- **THEN** the rendered `ClickHouseInstallation` resource includes an `s3_backup` disk in `storage.xml` pointing to `https://<account-bucket>.s3.<region>.amazonaws.com/clickhouse-backups/`
- **AND** `use_environment_credentials` is set to `1`

#### Scenario: No credentials in rendered template
- **WHEN** the ClickHouse installation template is rendered with cluster variables
- **THEN** no AWS access key, secret key, or session token appears anywhere in the rendered YAML or XML

#### Scenario: ACCOUNT_BUCKET distinct from per-cluster data bucket
- **WHEN** the s3_backup disk endpoint is constructed
- **THEN** it uses `state.s3Bucket` (account-level bucket), not `state.dataBucket` (per-cluster bucket)
