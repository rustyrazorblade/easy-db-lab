## ADDED Requirements

### Requirement: The reconciler ships through the node-local profiles relay

The reconciler SHALL upload completed JFR chunks to the node-local profiles relay at `http://127.0.0.1:<ALLOY_RELAY_PORT>`, which forwards them to the cluster's Pyroscope or the telemetry redirect's profiles endpoint. The CLI SHALL NOT write a control node IP or a cluster DNS name into a node's profiling configuration. The shipping, labelling, tenant, at-most-once and failure-handling behaviour of the reconciler SHALL be unchanged.

#### Scenario: Shipped chunks go to the relay
- **GIVEN** a node whose profiling configuration names the relay URL
- **WHEN** a JFR chunk completes
- **THEN** the reconciler posts it to `http://127.0.0.1:<ALLOY_RELAY_PORT>/ingest` in JFR format with the node's hostname, the cluster name and the tenant header

#### Scenario: Relay unreachable is a shipping failure, not data loss
- **GIVEN** the relay is not listening
- **WHEN** the reconciler tries to ship a completed chunk
- **THEN** the chunk stays on disk unshipped and is retried, exactly as when Pyroscope is unreachable today
