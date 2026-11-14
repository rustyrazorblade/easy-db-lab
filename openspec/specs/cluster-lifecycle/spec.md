# Cluster Lifecycle

## Purpose

Manages the full lifecycle of lab environments: initialization, provisioning, teardown, and status.
## Requirements
### Requirement: Cluster Initialization

The system MUST allow users to initialize a cluster configuration specifying a name and node counts.

The workspace's `bin/` directory belongs to easy-db-lab, which writes its tool wrappers there. `init` MUST fail with a clear message, and write nothing, when the directory already has a `bin/` (a file or a directory). The message says that the directory already has a `bin/`, that easy-db-lab needs `bin/` for its tool wrappers, and to use a new, empty directory as the workspace (for example under `clusters/`). This also keeps a source checkout or a project directory from being used as a workspace. With `--clean`, the check runs after the cleanup.

#### Scenario: Initialize a new cluster

- **GIVEN** a configured AWS profile
- **WHEN** a user initializes a cluster with a name and node count
- **THEN** cluster configuration is created and persisted locally.

#### Scenario: Re-initialize with different parameters

- **GIVEN** an initialized cluster
- **WHEN** the user re-initializes with different parameters
- **THEN** the configuration is updated.

#### Scenario: init refuses a directory that already has bin/

- **GIVEN** a directory that already has a `bin/` file or directory
- **WHEN** the user runs `init`
- **THEN** `init` fails with a message that names `bin/` and asks for a new, empty directory
- **AND** no file is written

#### Scenario: init refuses the source checkout

- **WHEN** the user runs `init` at the root of a source checkout
- **THEN** `init` fails because the checkout has a `bin/`, and writes nothing

#### Scenario: init --clean in a workspace whose bin/ holds only wrappers

- **GIVEN** a workspace whose `bin/` holds only the tool wrappers and their marker
- **WHEN** the user runs `init --clean`
- **THEN** the cleanup removes the wrappers, the marker, and the empty `bin/`, and `init` succeeds

`init --clean` MUST ask AWS whether the workspace's cluster has live infrastructure before it deletes anything, because it deletes `state.json`, the only local record of the cluster's VPC. It finds the cluster the way `down` does: from the VPC ID in the cluster state, through the same discovery as `down --dry-run` (the whole VPC, or only the cluster's `ClusterId`-tagged resources when the VPC came from `init --vpc`). A VPC that still holds any resource counts as live. When the lookup itself fails, `init --clean` MUST fail and delete nothing.

#### Scenario: init --clean refuses while the cluster has live infrastructure

- **GIVEN** a workspace whose cluster state records a VPC that still holds instances or other resources in AWS
- **WHEN** the user runs `init --clean`
- **THEN** `init` fails with `Setup.CleanRefusedLiveInfrastructure`, which names the VPC and the instances and tells the user to run `down` first
- **AND** no local file is deleted

#### Scenario: init --clean proceeds when AWS shows no infrastructure

- **GIVEN** a workspace whose cluster state records a VPC that AWS shows empty or gone, or records no VPC
- **WHEN** the user runs `init --clean`
- **THEN** the cleanup runs and `init` proceeds as before

### Requirement: Infrastructure Provisioning

The system MUST provision EC2 instances in AWS with configurable instance types and counts. The system MUST create a per-cluster data bucket for high-volume storage and CloudWatch metrics.

#### Scenario: Provision EC2 instances

- **GIVEN** an initialized cluster configuration
- **WHEN** the user provisions the cluster
- **THEN** EC2 instances are launched and accessible via SSH.

#### Scenario: K3s deployed on provisioned instances

- **GIVEN** provisioned instances
- **WHEN** provisioning completes
- **THEN** K3s Kubernetes is deployed on all nodes.

#### Scenario: Pod logs and kubelet eviction on a node with a data disk

- **GIVEN** a node whose data disk is mounted at `/mnt/db1`
- **WHEN** its K3s start script runs
- **THEN** `/var/log/pods` is a link to `/mnt/db1/container-logs`, and logs already in `/var/log/pods` are kept
- **AND** the kubelet's hard eviction thresholds for `nodefs.available` and `imagefs.available` are `0%`, so a database that fills its own disk does not make the kubelet evict pods, while the memory and inode thresholds keep their defaults.

#### Scenario: Per-cluster data bucket created

- **GIVEN** cluster provisioning
- **WHEN** infrastructure is created
- **THEN** a per-cluster S3 data bucket named `easy-db-lab-data-<cluster-id>` is created and tagged with cluster metadata.

#### Scenario: CloudWatch metrics on data bucket

- **GIVEN** a per-cluster data bucket
- **WHEN** provisioning completes
- **THEN** CloudWatch S3 request metrics are configured on the data bucket.

### Requirement: Cluster Teardown

The system MUST clean up all AWS resources on cluster teardown. Teardown MUST NOT set any S3 lifecycle, expiry, or retention rule, on the account bucket or on any data bucket, and MUST NOT delete any object that holds the owner's data. `down` MUST NOT accept a `--retention-days` option.

Before any infrastructure is torn down, the system MUST save everything the cluster still holds that is not yet in S3.  It MUST NOT take any VictoriaMetrics or VictoriaLogs snapshot.  The save runs in two phases.

**Phase A, in order:**
1. Mirror every Grafana annotation inside Loki's accepted window (not older than `Constants.Loki.MAX_ENTRY_AGE_HOURS`, not more than `Constants.Loki.MAX_ENTRY_AHEAD_HOURS` ahead) to Loki, reporting any annotation outside it with the `AnnotationsOutsideLokiWindow` warning and keeping it in Grafana and the S3 JSON backup.  This runs only while the logs signal is not yet recorded.
2. Stop the telemetry senders: delete the OTel collector DaemonSet and wait until its pods are gone, so its last batches reach Loki, Mimir and Tempo while they still accept writes.  The stop MUST succeed when the collector is already gone.

**Phase B, in parallel.**  Every step MUST run to completion, and a failed step MUST NOT stop the others:
- **Logs:** the Loki flush.  It MUST stop Loki's ingester with a synchronous flush and wait for the flush to finish, then stop the Loki process and wait until it has exited, so it builds and uploads its index.  IF the mirror failed, the Loki flush MUST NOT run, Loki MUST keep running, and the logs signal fails with the cause "not run, the annotation mirror failed".
- **Metrics:** the Mimir flush.  It MUST stop Mimir's ingester with a synchronous flush and wait for the flush to finish, then stop the Mimir process.
- **Traces:** the Tempo drain.  It MUST wait until Tempo holds no live traces for any tenant and its count of created traces has not changed across two readings at least 2 seconds apart.  Then it MUST poll the control node's disk until no Tempo write-ahead block holds a `meta.json` and every local complete block that holds a `meta.json` also holds its `flushed` marker.  Tempo MUST NOT be stopped or restarted.
- **Profiles:** no flush.  `down` MUST report that profiles need no flush, because Pyroscope writes each batch to S3 before it accepts it.
- **Annotations:** back up the Grafana annotations.

Rules for both phases:
- Every step MUST have a timeout.  The Tempo drain's timeout is 5 minutes.
- `down` MUST only flush, wait for the flush to finish, and refuse to tear down if it did not finish.  It MUST NOT run any other check: no check that a backend runs before its flush, and no check of write-ahead data, head compaction, or objects in S3 after it.
- The logs signal and the metrics signal MUST each be recorded in the cluster state at the moment its flush succeeds, with the time it completed.  One writer MUST make every state write, and each write MUST replace the state file atomically.  A `down` MUST skip a recorded signal.  The annotations backup, the collector stop, the Tempo drain and the profiles report MUST run on every `down` until a save succeeds for every signal.  When every Phase B step succeeds, the record MUST also note that the save is complete, before the infrastructure teardown starts; a later `down` that finds this note MUST skip both phases, open no tunnel, report every signal as already saved, and go straight to the teardown.  `up` MUST clear the record.
- IF any step fails or times out, `down` MUST stop after every Phase B step has finished: it MUST NOT tear down any infrastructure, MUST NOT start, scale up or restart any backend or the collector, and MUST leave each backend as its step left it.  It MUST report every failed signal with its step and cause, the state of each backend (running, ingester stopped, or scaled to 0), and that `down --force` tears down without the data not yet saved, and it MUST exit non-zero.  A step that never reached the cluster (the connection was refused, the SOCKS tunnel is down, or there is no kubeconfig because K3s never started) MUST be reported as unable to reach the cluster, with its error, and the workload it owns MUST be reported as not reached, never as stopped, scaled to 0 or deleted.  The write-ahead data and local blocks stay on the control node's disk.
- `down` MUST NOT retry a step within one run.
- `down` MUST NEVER scale Loki or Mimir up or recreate a backend pod.  A teardown that fails after a successful save MUST restore nothing and MUST report the teardown failure.
- The `--force` flag MUST skip both phases.  It MUST list the signals that will not be saved with the teardown preview, before the confirmation prompt.  The list is every signal not yet recorded among logs and metrics, plus traces and annotations, and it is empty once a complete save is recorded; profiles are never listed.
- A cluster that redirects its telemetry MUST skip both phases.
- Teardown is final.  Once `down` has saved the tail and started the infrastructure teardown, the cluster is done: no operation is supported on it afterwards (no `up`, no `grafana update-config`, no start, scale-up or restart of any backend).  Re-running `down` is the only supported step.  Behavior of any other operation on such a cluster is out of scope.

#### Scenario: Teardown removes AWS resources

- **GIVEN** a running cluster
- **WHEN** the user tears it down with confirmation
- **THEN** all AWS resources (EC2, NAT gateways, security groups, route tables, subnets, internet gateways, VPC) are terminated.

#### Scenario: Teardown keeps a VPC supplied with --vpc

- **GIVEN** a cluster initialized with `init --vpc <id>`, in a VPC that also holds the user's own resources and other clusters' resources
- **WHEN** `down` runs
- **THEN** only the resources that carry this cluster's `ClusterId` tag are removed: its instances, subnets, security group and internet gateway, and the default route to that gateway in the VPC's route tables
- **AND** the VPC and every untagged or differently tagged resource in it are left as they are
- **AND** the dry-run preview names the VPC as kept

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
- **THEN** every tagged cluster VPC and its resources are removed, and the `easy-db-lab-compactor` VPC is kept
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

#### Scenario: A re-run after a complete save goes straight to teardown

- **GIVEN** a cluster whose state records a complete save, and whose infrastructure teardown then failed part-way, with the control node already gone
- **WHEN** the user runs `down` again and confirms
- **THEN** no save step runs and no tunnel to the control node is opened
- **AND** every signal is reported as already saved, and the teardown goes ahead

#### Scenario: down runs no verify check

- **GIVEN** a running cluster
- **WHEN** the user tears it down
- **THEN** `down` flushes Loki and Mimir, drains Tempo, and waits for each to finish
- **AND** it runs no Loki, Mimir or Tempo running check, no Loki index write-ahead check, no Loki index-in-S3 check, no Mimir head-compaction check, and no Mimir block-in-S3 check

#### Scenario: A flush that does not finish stops down

- **GIVEN** a teardown whose Mimir ingester shutdown fails or times out
- **WHEN** the command runs without `--force`
- **THEN** the metrics signal fails with its step and cause
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

### Requirement: Cluster Status

The system MUST provide comprehensive status of cluster resources including nodes, networking, Kubernetes pods, and running workloads.

#### Scenario: Display cluster status

- **GIVEN** a running cluster
- **WHEN** the user checks status
- **THEN** the state of EC2 instances, VPC networking, K8s pods, stress jobs, and database versions is displayed.

### Requirement: Local Cleanup

The system MUST allow cleanup of locally generated cluster files.

Cleanup MUST remove only what easy-db-lab wrote in `bin/`: the ten tool wrappers, the `edl-socks-tunnel` script, and the marker, by name. It MUST NOT remove any other file in `bin/`, and MUST remove `bin/` itself only when it is then empty.

#### Scenario: Remove local cluster files

- **GIVEN** a previously initialized cluster
- **WHEN** the user runs local cleanup
- **THEN** state files, SSH config, the proxy env file, and cached configuration are removed.

#### Scenario: Cleanup removes the wrappers

- **GIVEN** a workspace whose `bin/` holds only the tool wrappers, `edl-socks-tunnel`, and their marker
- **WHEN** the user runs local cleanup
- **THEN** the wrappers, `edl-socks-tunnel`, the marker, and the `bin/` directory are removed

#### Scenario: Cleanup keeps files it did not write

- **GIVEN** a workspace whose `bin/` holds the tool wrappers, the marker, and another file
- **WHEN** the user runs local cleanup
- **THEN** the wrappers and the marker are removed
- **AND** the other file and `bin/` remain

### Requirement: Cluster State Backup

The system MUST back up cluster configuration files to S3 for recovery.

#### Scenario: Incremental config backup

- **GIVEN** a running cluster
- **WHEN** configuration changes occur
- **THEN** changed files are incrementally backed up to S3.

#### Scenario: Full config backup

- **GIVEN** a cluster with backed-up state
- **WHEN** the user triggers a full backup
- **THEN** all configuration files (state, SSH config, kubeconfig, cassandra patches) are persisted to S3.

### Requirement: Cluster State Restore

The system MUST support restoring cluster configuration from S3 using VPC identification. A restored workspace MUST get its tool wrappers and its proxy env file, so wrapped tools and `env.sh` work without running `up`.

#### Scenario: Restore state from VPC ID

- **GIVEN** a VPC ID from a previously provisioned cluster
- **WHEN** the user restores state
- **THEN** cluster configuration is recovered from S3 and the local environment is rebuilt.
- **AND** the tool wrappers, their marker, and the proxy env file are written

#### Scenario: Restore when no backup exists

- **GIVEN** no backup exists for a VPC
- **WHEN** restore is attempted
- **THEN** the user is informed that no configuration was found.

### Requirement: Architecture derived from instance type

The system MUST derive each node group's CPU architecture from that group's resolved instance type at initialization time, and MUST persist the derived architecture per node group in cluster state. The system MUST NOT expose any option to set the architecture directly.

Derivation MUST fail fast at initialization, before any EC2 instance is created, when the architecture cannot be determined. The system MUST NOT fall back to a default architecture.

#### Scenario: Architecture is derived from the instance type

- **GIVEN** a user initializes a cluster and specifies an instance type for a node group but does not — and cannot — specify an architecture
- **WHEN** the cluster is initialized
- **THEN** the architecture for that node group is derived from the instance type
- **AND** the derived architecture is persisted per node group in cluster state.

#### Scenario: Each node group may resolve to a different architecture

- **GIVEN** a user specifies a database instance type of one architecture and an application instance type of another
- **WHEN** the cluster is initialized
- **THEN** each node group's architecture is derived independently from its own instance type.

#### Scenario: Initialization fails fast when the architecture cannot be derived

- **GIVEN** a user specifies an instance type whose architecture cannot be determined (the instance type is unknown in the region, or it maps to no single supported architecture)
- **WHEN** the cluster is initialized
- **THEN** initialization fails with an error naming the instance type
- **AND** no EC2 instances are created
- **AND** the system does not fall back to a default architecture.

### Requirement: Provisioning reports success only when it fully succeeded

The system MUST NOT report successful provisioning unless every provisioning step succeeded. When any provisioning step fails, `up` MUST abort at the point of failure and exit non-zero.

There is no provisioning step whose failure may be logged and stepped over. A step that is skipped because there is nothing for it to do has not failed; a step that was attempted and threw has failed, and MUST abort provisioning.

This applies to every step invoked during provisioning, including nested commands whose exit codes were previously discarded, and to operations whose results were previously reduced to a log line.

#### Scenario: A failing provisioning step aborts `up`
- **WHEN** any provisioning step fails during `up` — writing configuration files, node setup, K3s cluster setup, StorageClass creation, node labeling, Tailscale startup, IAM policy application, or observability deployment
- **THEN** `up` SHALL abort at that step
- **AND** `up` SHALL exit non-zero
- **AND** the reported error SHALL identify the step that failed

#### Scenario: A failing nested command aborts `up`
- **WHEN** `up` invokes a nested command and that command returns a non-zero exit code
- **THEN** `up` SHALL abort
- **AND** `up` SHALL exit non-zero

#### Scenario: Unreachable cluster API aborts `up`
- **GIVEN** the cluster's Kubernetes API cannot be reached
- **WHEN** `up` attempts to apply node labels, StorageClasses, or the observability stack
- **THEN** `up` SHALL abort and exit non-zero
- **AND** `up` SHALL NOT report a successfully provisioned cluster

#### Scenario: Progress events assert only verified outcomes
- **WHEN** a provisioning step emits an event announcing its completion
- **THEN** that event SHALL be emitted only if the step actually succeeded

#### Scenario: Explicit opt-out is not a failure
- **WHEN** the user passes `--no-setup`
- **THEN** node setup SHALL be skipped
- **AND** `up` SHALL exit zero if all remaining steps succeeded

### Requirement: Cluster shape invariants are validated before provisioning

The system MUST validate the cluster's required shape before any AWS resource is provisioned, so that an unsatisfiable configuration fails before EC2 instances are launched rather than being discovered part-way through `up`.

A cluster MUST have a control node. An S3 bucket MUST be configured. Neither may be treated as an optional condition that causes provisioning steps to be silently skipped.

#### Scenario: Missing control node fails before provisioning
- **GIVEN** a cluster configuration that would produce no control node
- **WHEN** the user runs `up`
- **THEN** the command SHALL fail before any EC2 instance is launched
- **AND** the error SHALL state that a control node is required

#### Scenario: Missing S3 bucket fails before provisioning
- **GIVEN** no S3 bucket is configured
- **WHEN** the user runs `up`
- **THEN** the command SHALL fail before any EC2 instance is launched
- **AND** the error SHALL state that an S3 bucket is required

#### Scenario: Provisioning steps do not defend against missing invariants
- **WHEN** provisioning proceeds past validation
- **THEN** no provisioning step SHALL skip its work on the grounds that a control node or S3 bucket is absent

### Requirement: A cluster with zero database nodes is a valid configuration

The system MUST support provisioning a cluster with no database nodes. Workloads such as Trino with OpenSearch require a control node and application nodes but no database nodes.

Work that exists solely to serve database nodes MUST be skipped without warning when there are none. A supported configuration MUST NOT produce warning output.

#### Scenario: Zero database nodes provisions successfully
- **GIVEN** a cluster configuration with a control node, application nodes, and zero database nodes
- **WHEN** the user runs `up`
- **THEN** provisioning SHALL complete successfully and exit zero

#### Scenario: Database node labeling is skipped silently
- **GIVEN** a cluster with zero database nodes
- **WHEN** provisioning reaches database node labeling
- **THEN** the step SHALL be skipped
- **AND** no warning SHALL be emitted to the user

### Requirement: Control node SSH readiness is confirmed before it is used

The system MUST confirm that the control node is accepting SSH connections before any provisioning step depends on connecting to it.

#### Scenario: Provisioning waits for control node SSH
- **WHEN** `up` waits for instances to become reachable
- **THEN** the wait SHALL include the control node, not only database nodes
- **AND** provisioning SHALL NOT proceed to node setup or tunnel establishment until the control node accepts SSH connections

#### Scenario: Control node that never accepts SSH aborts `up`
- **GIVEN** the control node does not accept SSH connections within the retry window
- **WHEN** `up` waits for SSH readiness
- **THEN** `up` SHALL exit non-zero

### Requirement: up makes sure the account compactor runs

`up` SHALL start the account compactor service when it is not running, on every cluster, redirected ones included.  When the service is missing it SHALL create it; when its desired count is 0 it SHALL start it with the current task definition.  When the service already runs, `up` SHALL leave it as it is: it SHALL NOT change its task definition, restart it or scale it.  `up` SHALL NOT wait for the task to reach the running state.

#### Scenario: up starts a missing compactor
- **WHEN** `up` runs and no compactor service runs in the account
- **THEN** `up` starts it, and it runs exactly 1 task

#### Scenario: up leaves a running compactor alone
- **WHEN** `up` runs and the compactor service already runs
- **THEN** `up` leaves it as it is, with the same task definition and the same task

### Requirement: down stops the account compactor after the last cluster

After its infrastructure teardown succeeds, `down` SHALL count the VPCs tagged `easy_cass_lab=1`, in every enabled region, whose `bucket` tag names the account bucket, leaving out the VPCs this `down` tore down.  When none is left, `down` SHALL stop the compactor service by setting its desired count to 0.  Otherwise it SHALL leave the service running.

#### Scenario: The last cluster stops the compactor
- **WHEN** `down` runs for the last tagged cluster that names the account bucket, in any region
- **THEN** `down` stops the compactor service

#### Scenario: Another cluster keeps the compactor running
- **WHEN** `down` runs and another tagged cluster, in any region, names the account bucket
- **THEN** the compactor service keeps running

## Success Criteria

- Users can provision a 3-node cluster in under 10 minutes from command execution.
- Cluster teardown removes all AWS resources within 5 minutes.
- New users can create their first cluster by following documentation in under 30 minutes.
- System supports at least 5 concurrent database nodes per cluster.

## Assumptions

- AWS region defaults to us-west-2 for pre-built AMIs; other regions require building a custom AMI.
- Instance types are selected for cost-effective testing, not production workloads.
- Security groups are configured for lab use with appropriate access controls.
- Lab data is ephemeral by default; backup/restore is user-initiated.
- Users are responsible for monitoring AWS costs; the tool provides teardown commands.
