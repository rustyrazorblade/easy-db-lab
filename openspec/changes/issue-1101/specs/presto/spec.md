## ADDED Requirements

### Requirement: Presto coordinator behind a NodePort Service

The presto kit SHALL expose its coordinator through a kit-owned NodePort Service on `Constants.Kit.Presto.NODE_PORT` (30880) selecting the coordinator pod, with no `hostPort`. `presto sql` SHALL reach `/v1/statement` through that Service's endpoint via the service-access seam (#1090), not the app node's port 8080. The Pyroscope agent SHALL be delivered by an init container into an `emptyDir`, with no hostPath.

#### Scenario: Coordinator reachable on any node
- **WHEN** Presto starts on a cluster with two app nodes
- **THEN** `presto sql` reaches the coordinator through port 30880 regardless of which node it dials
- **AND** no Presto pod declares a `hostPort` or mounts `/usr/local/pyroscope`
