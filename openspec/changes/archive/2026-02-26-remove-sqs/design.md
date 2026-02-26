## Context

The `Up` command creates an SQS queue and configures S3 bucket notifications for EMR log ingestion via a Vector S3 Deployment. This pipeline is broken and blocks provisioning. Vector's node DaemonSet also duplicates ClickHouse log collection that OTel already handles better.

OTel already runs on every node as a DaemonSet and has a log pipeline to VictoriaLogs. Migrating Vector's unique sources (system logs, Cassandra logs, journald) to OTel consolidates log collection into a single agent.

## Goals / Non-Goals

**Goals:**
- Remove all SQS code, configuration, IAM permissions, and dependencies
- Remove Vector entirely (both S3 Deployment and node DaemonSet)
- Add system log, Cassandra log, and journald receivers to OTel
- All tests pass after changes

**Non-Goals:**
- Replacing EMR log ingestion with an alternative mechanism
- Changing OTel's existing ClickHouse log collection or metrics pipeline
- Modifying any other observability components (VictoriaMetrics, Tempo, Pyroscope, etc.)

## Decisions

### 1. Full SQS removal — no optional mode

Remove entirely. Making SQS optional would leave dead code and untested paths.

### 2. Remove Vector entirely — consolidate on OTel

OTel is already deployed on every node and has a log pipeline to VictoriaLogs. Adding three receivers is simpler than maintaining a separate Vector deployment. The OTel contrib distribution includes `filelog` and `journald` receivers.

### 3. ClusterState field removal — no migration needed

Remove `sqsQueueUrl` and `sqsQueueArn` fields. Jackson's lenient deserialization silently ignores unknown fields in existing `state.json` files.

### 4. OTel receiver design

New receivers mirror Vector's sources with proper OTel attributes:

- **`filelog/system`**: Collect `/var/log/**/*.log`, `/var/log/messages`, `/var/log/syslog`. Exclude `*.gz`, `*.old`. Add attribute `source: system`.
- **`filelog/cassandra`**: Collect `/mnt/db1/cassandra/logs/*.log`. Exclude `*.gz`. Add attribute `source: cassandra`.
- **`journald`**: Filter to units `cassandra.service`, `docker.service`, `k3s.service`, `sshd.service`. Current boot only. Add attribute `source: systemd`.

All three feed into the existing `logs` pipeline alongside the ClickHouse filelog receivers, exporting to VictoriaLogs via OTLP.

### 5. OTel DaemonSet volume mounts

Add hostPath mounts to `OtelManifestBuilder`:
- `/var/log` (read-only) for system logs
- `/mnt/db1/cassandra/logs` (read-only) for Cassandra logs
- `/run/log/journal` (read-only) for journald — OTel's journald receiver reads from the journal API, which needs access to the journal directory

### 6. Orphaned SQS queues

Existing clusters have `sqsQueueUrl` in their persisted state, but since we're removing `ClusterState.sqsQueueUrl`, `Down` won't see it. Orphaned queues can be cleaned up via AWS resource tags (`easy_cass_lab`, `ClusterId`). Empty SQS queues have no cost.

## Risks / Trade-offs

- [EMR logs not ingested into VictoriaLogs] → Acceptable. Logs remain in S3.
- [Orphaned SQS queues] → Low risk. Tagged, zero cost, manual cleanup if needed.
- [OTel journald receiver compatibility] → The OTel contrib `journald` receiver is stable. If the journal path differs on some AMIs, the hostPath mount may need adjustment.
