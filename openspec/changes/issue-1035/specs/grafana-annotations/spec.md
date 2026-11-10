## ADDED Requirements

### Requirement: Annotation backups land in the data directory in filesystem storage
In `Volume` observability storage, `grafana backup` and the `down` annotation backup SHALL write the artifact to `D/grafana/annotations/<tenant>/<yyyyMMdd-HHmmss>_<name>-<clusterId>.json`, with the same naming and never-overwrite rule as in `S3` storage, and SHALL report the file path.  They SHALL make no S3 request.

#### Scenario: Backup writes under D
- **WHEN** a user runs `grafana backup` on a cluster in tenant `acme` with `Volume` storage at D
- **THEN** the annotations JSON is written under `D/grafana/annotations/acme/`, named with the timestamp and the cluster's name and id
- **AND** the reported location is that file path

#### Scenario: Backups in the same second do not overwrite
- **WHEN** two backups are taken in the same second with `Volume` storage
- **THEN** both artifacts are kept
