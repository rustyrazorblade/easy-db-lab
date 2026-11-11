## MODIFIED Requirements

### Requirement: Back up Grafana annotations to an account-level S3 location

The system SHALL provide a `grafana backup` command that first mirrors every Grafana annotation inside Loki's accepted window to Loki, then captures the Grafana annotations (`GET /api/annotations`) as a JSON artifact and uploads it to the cluster tenant's annotations location in the account bucket, `grafana/annotations/<tenant>/`. The artifact SHALL be named `<yyyyMMdd-HHmmss>_<name>-<clusterId>.json`, so that artifacts from different clusters in one tenant never collide, and a backup SHALL never overwrite an earlier one taken in the same second. On success the command SHALL report the resulting S3 URI. The command SHALL carry `@RequiresProxy` and reach the API over the proxied HTTP client.

#### Scenario: Backup uploads annotations and reports the URI

- **WHEN** a user runs `grafana backup` against an UP cluster in tenant `acme` with an S3 bucket configured
- **THEN** every Grafana annotation inside Loki's accepted window is mirrored to Loki
- **AND** the Grafana annotations are captured as a JSON artifact uploaded under `grafana/annotations/acme/`, named with the timestamp and the cluster's name and id
- **AND** the resulting S3 URI is reported

#### Scenario: Backup fails fast with no S3 bucket

- **WHEN** a user runs `grafana backup` with no S3 bucket configured
- **THEN** the command fails fast with the same "run up first" message the other backups use
