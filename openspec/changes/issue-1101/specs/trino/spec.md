## MODIFIED Requirements

### Requirement: Pyroscope Profiling

Trino coordinator and worker deployments SHALL be patched after Helm install to attach the Pyroscope Java profiling agent, delivered by an init container that copies the agent jar into a `java-agents` `emptyDir`. No hostPath SHALL be mounted.

#### Scenario: Pyroscope agent injected

- **WHEN** Trino starts successfully
- **THEN** both coordinator and worker pods have the Pyroscope agent injected via `JAVA_TOOL_OPTIONS` from `/edl/agents`, copied by an init container into an `emptyDir`
- **AND** no pod mounts `/usr/local/pyroscope`

## ADDED Requirements

### Requirement: Trino coordinator behind a NodePort Service

The trino kit SHALL expose its coordinator through a kit-owned NodePort Service on `Constants.Kit.Trino.NODE_PORT` (30881) selecting the coordinator pod, with no `hostPort`. Catalog updates SHALL NOT delete the coordinator pod to free a host port.

#### Scenario: Coordinator reachable on any node
- **WHEN** Trino starts on a cluster with two app nodes
- **THEN** `trino sql` reaches the coordinator through port 30881 regardless of which node it dials
- **AND** no Trino pod declares a `hostPort`
