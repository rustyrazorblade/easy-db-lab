## ADDED Requirements

### Requirement: Database data and logs never on the root volume

Database data files and logs SHALL never be on the root volume of a db node. `local-path` PVCs SHALL be under `/mnt/db1`, because the runtime K3s start scripts link `/var/lib/rancher/k3s` to `/mnt/db1/k3s`. Pod logs SHALL be under `/mnt/db1/container-logs` (see the k8s-container-log-collection capability). Configuration and binaries MAY stay on the root volume.

#### Scenario: TiDB data and pod logs on the data mount
- **WHEN** the TiDB kit runs on a cluster with this change
- **THEN** its PVC data files and its pods' logs resolve to paths under `/mnt/db1`

#### Scenario: SingleStore data and pod logs on the data mount
- **WHEN** the SingleStore kit runs
- **THEN** its data directory, tracelogs, and pods' logs resolve to paths under `/mnt/db1`
