## ADDED Requirements

### Requirement: Pod logs are stored on the data mount

When `/mnt/db1` is a mount point, the runtime K3s start scripts (`services/start-k3s-server.sh` and `services/start-k3s-agent.sh`) SHALL make `/var/log/pods` a symlink to `/mnt/db1/container-logs` before K3s starts. They SHALL do this only when `/var/log/pods` is not already a symlink. The OTel Collector SHALL mount `/mnt/db1/container-logs`, so it can follow the symlink and the `file_log/containers` receiver keeps collecting every pod's logs.

#### Scenario: Pod logs on the data mount
- **WHEN** a node with `/mnt/db1` mounted starts K3s
- **THEN** `/var/log/pods` is a symlink to `/mnt/db1/container-logs`
- **AND** pod log files are written under `/mnt/db1/container-logs`

#### Scenario: Pod logs still reach Loki
- **WHEN** a pod writes to stdout on a node where `/var/log/pods` is on the data mount
- **THEN** the line appears in Loki with its Kubernetes metadata

#### Scenario: Start script run again
- **WHEN** the start script runs on a node where `/var/log/pods` is already a symlink
- **THEN** it leaves the symlink as it is
