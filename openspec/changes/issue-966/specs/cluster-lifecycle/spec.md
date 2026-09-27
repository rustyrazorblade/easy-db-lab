## MODIFIED Requirements

### Requirement: Cluster Teardown

The system MUST clean up all AWS resources on cluster teardown. Teardown MUST NOT set any S3 lifecycle, expiry, or retention rule, on the account bucket or on any data bucket, and MUST NOT delete any object that holds the owner's data. `down` MUST NOT accept a `--retention-days` option.

Before any infrastructure is torn down, the system MUST save everything the cluster still holds that is not yet in S3.  It MUST NOT take any VictoriaMetrics or VictoriaLogs snapshot.  The save runs in two phases.

**Phase A, in order:**
1. Check that Loki runs.  IF the logs signal is not yet recorded and Loki is scaled to 0 or not ready, the logs signal fails with the cause "Loki was stopped by an earlier `down`", and the mirror and the Loki flush do not run.
2. Mirror every Grafana annotation inside Loki's accepted window (not older than `Constants.Loki.MAX_ENTRY_AGE_HOURS`, not more than `Constants.Loki.MAX_ENTRY_AHEAD_HOURS` ahead) to Loki, reporting any annotation outside it with the `AnnotationsOutsideLokiWindow` warning and keeping it in Grafana and the S3 JSON backup.  This runs only while the logs signal is not yet recorded.
3. Stop the telemetry senders: delete the OTel collector DaemonSet and wait until its pods are gone, so its last batches reach Loki, Mimir and Tempo while they still accept writes.  The stop MUST succeed when the collector is already gone.

**Phase B, in parallel.**  Every step MUST run to completion, and a failed step MUST NOT stop the others:
- **Logs:** the Loki flush.  It MUST stop Loki's ingester with a synchronous flush, then stop the Loki process so it builds and uploads its index, then verify on the control node that no index write-ahead data is left and that every locally built index file exists in S3.  IF the mirror failed, the Loki flush MUST NOT run, Loki MUST keep running, and the logs signal fails with the cause "not run, the annotation mirror failed".
- **Metrics:** the Mimir flush.  It MUST stop Mimir's ingester with a synchronous flush, then verify that the head was compacted into blocks (no failed compaction, and the newest block covers the head's newest sample) and that every block the cluster wrote exists in S3, then stop the Mimir process.
- **Traces:** the Tempo drain.  It MUST wait until Tempo holds no live traces for any tenant and its count of created traces has not changed across two readings at least 2 seconds apart.  Then it MUST poll the control node's disk until no Tempo write-ahead block holds a `meta.json` and every local complete block that holds a `meta.json` also holds its `flushed` marker.  Tempo MUST NOT be stopped or restarted.
- **Profiles:** no flush.  `down` MUST report that profiles need no flush, because Pyroscope writes each batch to S3 before it accepts it.
- **Annotations:** back up the Grafana annotations.

Rules for both phases:
- Every step MUST have a timeout.  The Tempo drain's timeout is 5 minutes.
- The logs signal and the metrics signal MUST each be recorded in the cluster state at the moment its flush succeeds, with the time it completed and what it verified.  One writer MUST make every state write, and each write MUST replace the state file atomically.  A `down` MUST skip a recorded signal.  The annotations backup, the collector stop, the Tempo drain and the profiles report MUST run on every `down`.  `up` MUST clear the record.
- IF any step fails or times out, `down` MUST stop after every Phase B step has finished: it MUST NOT tear down any infrastructure, MUST NOT start, scale up or restart any backend or the collector, and MUST leave each backend as its step left it.  It MUST report every failed signal with its step and cause, the state of each backend (running, ingester stopped, or scaled to 0), and that `down --force` tears down without the data not yet saved, and it MUST exit non-zero.  The write-ahead data and local blocks stay on the control node's disk.
- `down` MUST NOT retry a step within one run.
- `down` MUST NEVER scale Loki or Mimir up or recreate a backend pod.  A teardown that fails after a successful save MUST restore nothing and MUST report the teardown failure.
- The `--force` flag MUST skip both phases.  It MUST list the signals that will not be saved with the teardown preview, before the confirmation prompt.  The list is every signal not yet recorded among logs and metrics, plus traces and annotations; profiles are never listed.
- A cluster that redirects its telemetry MUST skip both phases.
- Teardown is final.  Once `down` has saved the tail and started the infrastructure teardown, the cluster is done: no operation is supported on it afterwards (no `up`, no `grafana update-config`, no start, scale-up or restart of any backend).  Re-running `down` is the only supported step.  Behavior of any other operation on such a cluster is out of scope.

#### Scenario: Teardown removes AWS resources

- **GIVEN** a running cluster
- **WHEN** the user tears it down with confirmation
- **THEN** all AWS resources (EC2, NAT gateways, security groups, route tables, subnets, internet gateways, VPC) are terminated.

#### Scenario: Teardown sets no expiry rule

- **GIVEN** a running cluster with a data bucket
- **WHEN** `down` completes
- **THEN** no lifecycle or expiry rule exists on `clusters/<name>-<id>/`, on `mimir/`, `loki/`, `tempo/`, `pyroscope/` or `grafana/`, or on the data bucket
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

#### Scenario: Declining the confirmation stops nothing

- **GIVEN** a running cluster
- **WHEN** the user declines the teardown prompt, or runs `down --dry-run`
- **THEN** no save step runs, the collector, Loki and Mimir keep running, and nothing is torn down

#### Scenario: The tail of every signal reaches S3 before teardown

- **GIVEN** a running cluster whose Mimir, Loki and Tempo hold data not yet in S3
- **WHEN** the user tears it down
- **THEN** the annotations are mirrored to Loki and the collector is stopped before any flush starts
- **AND** Loki's chunks and index, Mimir's blocks, and every span Tempo received are in S3, and the annotations backup is written, before any infrastructure is torn down
- **AND** no VictoriaMetrics or VictoriaLogs snapshot is taken

#### Scenario: The Phase B steps run in parallel

- **GIVEN** a teardown whose Loki flush, Mimir flush and Tempo drain each take time
- **WHEN** the command runs
- **THEN** the steps run at the same time, and the save takes about as long as its slowest step

#### Scenario: One failed signal does not stop the others

- **GIVEN** a teardown whose Tempo drain times out
- **WHEN** the command runs without `--force`
- **THEN** the Loki flush, the Mimir flush and the annotations backup still finish, and the logs and metrics signals are recorded
- **AND** `down` reports traces as the failed signal with its step and cause, tears nothing down, scales nothing up, names `down --force`, and exits non-zero

#### Scenario: Every failed signal is reported

- **GIVEN** a teardown in which two Phase B steps fail
- **WHEN** the command runs without `--force`
- **THEN** the report names both failed signals, each with its step and cause

#### Scenario: A failed mirror fails only the logs signal

- **GIVEN** a teardown whose annotation mirror fails
- **WHEN** the command runs without `--force`
- **THEN** the Loki flush does not run and Loki keeps running
- **AND** the Mimir flush, the Tempo drain and the annotations backup still run
- **AND** `down` reports the logs signal as failed because the mirror failed, and tears nothing down

#### Scenario: The Tempo drain waits for traces still in memory

- **GIVEN** Tempo holds traces in memory that are not yet in a block
- **WHEN** the Tempo drain runs
- **THEN** it passes only after those traces are in a block that Tempo has uploaded to S3

#### Scenario: Profiles need no flush

- **WHEN** `down` saves the tail
- **THEN** it reports that profiles need no flush because Pyroscope writes each batch to S3 before it accepts it
- **AND** Pyroscope is not stopped

#### Scenario: A failed teardown after a successful save restores nothing

- **GIVEN** a save that succeeded and left Loki and Mimir at 0
- **WHEN** the infrastructure teardown then fails
- **THEN** the teardown failure is reported and `down` exits non-zero
- **AND** neither Loki nor Mimir is scaled up or restarted

#### Scenario: A re-run skips the recorded signals

- **GIVEN** a cluster whose state records the logs and metrics signals, and whose Tempo drain failed
- **WHEN** the user runs `down` again and confirms
- **THEN** the mirror and the Loki and Mimir flushes are skipped and reported as already saved
- **AND** the collector stop, the Tempo drain, the profiles report and the annotations backup run again

#### Scenario: A signal is recorded when its flush finishes

- **GIVEN** a teardown whose Loki and Mimir flushes succeed while the Tempo drain still runs
- **WHEN** the process is interrupted before the Tempo drain ends
- **THEN** the cluster state already records the logs and metrics signals
- **AND** the next `down` skips them

#### Scenario: A re-run after a stopped Loki reports the real cause

- **GIVEN** a cluster whose logs signal is not recorded and whose Loki is scaled to 0
- **WHEN** the user runs `down` without `--force` and confirms
- **THEN** the logs signal fails because Loki was stopped by an earlier `down`, and the mirror does not run
- **AND** no infrastructure is torn down, nothing is scaled up, and the report points at `down --force`

#### Scenario: The collector stop succeeds when the collector is gone

- **GIVEN** a cluster whose collector DaemonSet was already deleted by an earlier `down`
- **WHEN** `down` runs again
- **THEN** the collector stop succeeds and the save continues

#### Scenario: --force lists what it will not save before the prompt

- **GIVEN** a cluster whose state records the logs signal only
- **WHEN** the user runs `down --force`
- **THEN** the preview lists metrics, traces and annotations as not saved, before the confirmation prompt
- **AND** profiles are not listed
- **AND** after confirmation, both phases are skipped and teardown proceeds

#### Scenario: Teardown is final

- **GIVEN** a cluster on which `down` has completed a successful save and started the infrastructure teardown
- **WHEN** the operator continues
- **THEN** re-running `down` is the only supported operation on that cluster
- **AND** no `up`, `grafana update-config`, or backend start, scale-up or restart is supported on it
