## MODIFIED Requirements

### Requirement: Cluster Teardown

The system MUST clean up all AWS resources on cluster teardown. Teardown MUST NOT set any S3 lifecycle, expiry, or retention rule, on the account bucket or on any data bucket, and MUST NOT delete any object that holds the owner's data. `down` MUST NOT accept a `--retention-days` option.

Before any infrastructure is torn down, the system MUST save everything the cluster still holds that is not yet in S3, in this order: mirror every Grafana annotation inside Loki's accepted window (not older than `Constants.Loki.MAX_ENTRY_AGE_HOURS`, not more than `Constants.Loki.MAX_ENTRY_AHEAD_HOURS` ahead) to Loki, reporting any annotation outside it with the `AnnotationsOutsideLokiWindow` warning and keeping it in Grafana and the S3 JSON backup; flush Loki and verify it; flush Mimir and verify it; back up the Grafana annotations.  The system MUST NOT take any VictoriaMetrics or VictoriaLogs snapshot.

- The Loki flush MUST stop Loki's ingester with a synchronous flush, then stop the Loki process so it builds and uploads its index, then verify on the control node that no index write-ahead data is left and that every locally built index file exists in S3.
- The Mimir flush MUST stop Mimir's ingester with a synchronous flush, then verify that the head was compacted into blocks (no failed compaction, and the newest block covers the head's newest sample) and that every block the shipper uploads exists in S3, then stop the Mimir process.
- Every step MUST have a timeout.  IF any step fails or times out, `down` MUST stop there: it MUST NOT tear down any infrastructure, MUST NOT start, scale up or restart Loki or Mimir, and MUST leave each backend as that step left it.  It MUST report which step failed and why, the state of each backend (running, ingester stopped, or scaled to 0), and that `down --force` tears down without the data not yet in S3, and it MUST exit non-zero.  The write-ahead data and local blocks stay on the control node's disk.
- `down` MUST NOT retry the flush.
- `down` MUST NEVER scale Loki or Mimir up or recreate a backend pod.  A teardown that fails after a successful flush MUST restore nothing and MUST report the teardown failure.
- A successful flush MUST be recorded in the cluster state with the time it completed and what it verified.  A `down` that finds this record MUST skip the flush and go on to the teardown.  `up` MUST clear the record.
- IF no successful flush is recorded and Loki or Mimir is scaled to 0 or not ready, `down` MUST stop before the flush, report that the tail cannot be flushed without starting that backend again, point at `down --force`, tear nothing down and exit non-zero.
- The `--force` flag MUST skip all of these steps and proceed with teardown regardless.
- A cluster that redirects its telemetry MUST skip all of these steps.
- Teardown is final.  Once `down` has flushed and started the infrastructure teardown, the cluster is done: no operation is supported on it afterwards (no `up`, no `grafana update-config`, no start, scale-up or restart of any backend).  Re-running `down` is the only supported step.  Behavior of any other operation on such a cluster is out of scope.

#### Scenario: Teardown removes AWS resources

- **GIVEN** a running cluster
- **WHEN** the user tears it down with confirmation
- **THEN** all AWS resources (EC2, NAT gateways, security groups, route tables, subnets, internet gateways, VPC) are terminated.

#### Scenario: Teardown sets no expiry rule

- **GIVEN** a running cluster with a data bucket
- **WHEN** `down` completes
- **THEN** no lifecycle or expiry rule exists on `clusters/<name>-<id>/`, on `observability/`, on `observabilitymetrics/`, or on the data bucket
- **AND** every object in both buckets is still present

#### Scenario: The retention option no longer exists

- **WHEN** a user runs `down --retention-days 1`
- **THEN** the CLI reports an unknown option and tears nothing down

#### Scenario: Teardown of all clusters

- **GIVEN** multiple clusters
- **WHEN** the user tears down all clusters
- **THEN** every tagged VPC and its resources are removed
- **AND** each per-cluster data bucket that is empty is deleted, and each data bucket that still holds objects is kept with no expiry rule

#### Scenario: Teardown requires confirmation

- **GIVEN** a teardown request without confirmation
- **WHEN** the command runs
- **THEN** the user is prompted for approval before proceeding.

#### Scenario: Declining the confirmation stops no backend

- **GIVEN** a running cluster
- **WHEN** the user declines the teardown prompt, or runs `down --dry-run`
- **THEN** no flush step runs, Loki and Mimir keep running, and nothing is torn down

#### Scenario: The tail of every signal reaches S3 before teardown

- **GIVEN** a running cluster whose Mimir and Loki hold data not yet in S3
- **WHEN** the user tears it down
- **THEN** every Grafana annotation inside Loki's accepted window is mirrored to Loki, Loki's chunks and index are in S3, and Mimir's head is in blocks that are in S3, before any infrastructure is torn down
- **AND** no VictoriaMetrics or VictoriaLogs snapshot is taken

#### Scenario: A failed flush step stops down

- **GIVEN** a teardown whose Mimir S3 check fails after Loki was already scaled to 0
- **WHEN** the command runs without `--force`
- **THEN** `down` stops and reports the Mimir S3 check as the failed step, with its cause
- **AND** no infrastructure is torn down, Loki stays at 0, Mimir is not restarted, and nothing is scaled up
- **AND** the report names each backend's state and says `down --force` tears down without the tail, and `down` exits non-zero

#### Scenario: A flush that cannot reach S3 times out and stops down

- **GIVEN** a teardown during which S3 refuses Loki's uploads
- **WHEN** the Loki shutdown does not finish within its timeout
- **THEN** `down` stops, reports the Loki shutdown as the failed step, and tears nothing down
- **AND** the Loki pod is not restarted

#### Scenario: A failed teardown after a successful flush restores nothing

- **GIVEN** a flush that succeeded and left Loki and Mimir at 0
- **WHEN** the infrastructure teardown then fails
- **THEN** the teardown failure is reported and `down` exits non-zero
- **AND** neither Loki nor Mimir is scaled up or restarted

#### Scenario: A re-run after a successful flush skips the flush

- **GIVEN** a cluster whose state records a successful flush, with Loki and Mimir at 0
- **WHEN** the user runs `down` again and confirms
- **THEN** the flush is skipped and reported as already done
- **AND** the teardown goes ahead

#### Scenario: A re-run after a failed flush with a stopped backend stops

- **GIVEN** a cluster with no recorded successful flush whose Loki is scaled to 0
- **WHEN** the user runs `down` without `--force` and confirms
- **THEN** `down` stops, reports that the tail cannot be flushed without starting Loki again, and points at `down --force`
- **AND** no infrastructure is torn down and nothing is scaled up

#### Scenario: --force skips the flush and tears down anyway

- **GIVEN** a teardown request with `--force`
- **WHEN** the command runs
- **THEN** the annotation mirror, both flushes and the annotations backup are skipped
- **AND** teardown proceeds regardless

#### Scenario: Teardown is final

- **GIVEN** a cluster on which `down` has completed a successful flush and started the infrastructure teardown
- **WHEN** the operator continues
- **THEN** re-running `down` is the only supported operation on that cluster
- **AND** no `up`, `grafana update-config`, or backend start, scale-up or restart is supported on it
