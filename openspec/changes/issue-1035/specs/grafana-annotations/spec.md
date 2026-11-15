## ADDED Requirements

### Requirement: Annotation backups land in the data directory on a filesystem store
On a local filesystem store, `grafana backup` and the `down` annotation backup SHALL write the artifact to `D/grafana/annotations/<tenant>/<yyyyMMdd-HHmmss>_<name>-<clusterId>.json`, under the store prefix when one is set. They SHALL use the same naming and never-overwrite rule as on S3, SHALL report the file path, and SHALL make no S3 request.

#### Scenario: Backup writes under D
- **WHEN** a user runs `grafana backup` on a cluster in tenant `acme` with a filesystem store at D
- **THEN** the annotations JSON is written under `D/grafana/annotations/acme/`, named with the timestamp and the cluster's name and id
- **AND** the reported location is that file path

#### Scenario: Backups in the same second do not overwrite
- **WHEN** two backups are taken in the same second on a filesystem store
- **THEN** both artifacts are kept
