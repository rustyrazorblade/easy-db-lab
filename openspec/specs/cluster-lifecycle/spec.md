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

#### Scenario: AWS has no capacity for an instance type in a zone

- **GIVEN** a launch during `up` that AWS refuses with `InsufficientInstanceCapacity`
- **WHEN** `up` reports the provisioning failures
- **THEN** it emits `Provision.InstanceCapacityShortage`, which names the node role, the instance type and the availability zone, in place of the generic `Provision.InfrastructureFailure`
- **AND** its text says the failure is a capacity shortage, and suggests a different availability zone (`--azs`) or instance type
- **AND** `up` makes no capacity preflight and no automatic retry in another zone or type.

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
- IF any step fails or times out, `down` MUST stop after every Phase B step has finished: it MUST NOT tear down any infrastructure, MUST NOT start, scale up or restart any backend or the collector, and MUST leave each backend as its step left it.  It MUST report every failed signal with its step and cause, the state of each backend (running, ingester stopped, or scaled to 0), and that `down --force` tears down without the data not yet saved, and it MUST exit non-zero.  The write-ahead data and local blocks stay on the control node's disk.
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

#### Scenario: Teardown by VPC id with no state keeps a VPC easy-db-lab did not tag

- **GIVEN** no cluster state, and a VPC with no easy-db-lab tags (no `easy_cass_lab=1` marker, or no `ClusterId`)
- **WHEN** `down <vpc-id>` runs
- **THEN** only the resources that carry an easy-db-lab `ClusterId` tag and the marker tag are removed
- **AND** the VPC is kept, and `down` says it was kept

#### Scenario: Teardown by VPC id with no state deletes a VPC easy-db-lab created

- **GIVEN** no cluster state, and a VPC that carries the `easy_cass_lab=1` marker and a `ClusterId` tag (or the packer VPC, which carries the marker and its name)
- **WHEN** `down <vpc-id>` runs
- **THEN** the VPC and everything in it are removed

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
- **THEN** every tagged cluster VPC and its resources are removed
- **AND** each per-cluster data bucket that is empty is deleted, and each data bucket that still holds objects is kept with no expiry rule

#### Scenario: Teardown requires confirmation

- **GIVEN** a teardown request without confirmation
- **WHEN** the command runs
- **THEN** the user is prompted for approval before proceeding.

#### Scenario: Declining the confirmation stops nothing

- **GIVEN** a running cluster
- **WHEN** the user declines the teardown prompt, or runs `down --dry-run`
- **THEN** no save step runs, the collector, Loki and Mimir keep running, and nothing is torn down

#### Scenario: A dry run changes nothing

- **GIVEN** a cluster in any teardown mode: the current cluster, a specific VPC, `--all`, or `--packer`, including one where no resources are found
- **WHEN** the user runs `down --dry-run`
- **THEN** the resources that would be deleted are previewed and the command exits zero
- **AND** the cluster state is not changed: it is not marked down, and its VPC and Tailscale device are kept
- **AND** the SOCKS tunnel is not stopped and no Tailscale device is removed

#### Scenario: A dry run after the VPC is gone only previews the device removal

- **GIVEN** a cluster whose VPC an earlier `down` removed, but whose control node is still recorded as a Tailscale device
- **WHEN** the user runs `down --dry-run`
- **THEN** it reports that the device would be removed from the tailnet and exits zero
- **AND** the device stays recorded, the cluster state is not marked down, and the SOCKS tunnel is not stopped

#### Scenario: A dry run with nothing recorded to tear down fails and changes nothing

- **GIVEN** a workspace with no cluster state, or a cluster state that records neither a VPC nor a Tailscale device
- **WHEN** the user runs `down --dry-run`
- **THEN** it reports that there is nothing to tear down and exits non-zero, as `down` without `--dry-run` does
- **AND** the SOCKS tunnel is not stopped and the cluster state is not changed

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

#### Scenario: down does nothing about the compactor

- **GIVEN** a cluster whose control node runs the account compactor, as the holder or as a standby
- **WHEN** `down` runs
- **THEN** it flushes every backend, waits for each flush, and refuses to tear down if a flush did not finish
- **AND** it does not count other clusters, does not stop or start any compactor, and does not touch any lease

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

The system MUST support restoring cluster configuration from S3 using VPC identification. A restored workspace MUST get its tool wrappers and its proxy env file, so wrapped tools and `env.sh` work without running `up`. A restored workspace MUST also get an `sshConfig` and `env.sh` generated for the machine that restores, from the restored state and that machine's SSH key and SSH transport, so `status` and the SOCKS tunnel work without running `up`. `sshConfig` is never backed up: it holds the key path and routing of the machine that wrote it.

#### Scenario: Restore state from VPC ID

- **GIVEN** a VPC ID from a previously provisioned cluster
- **WHEN** the user restores state
- **THEN** cluster configuration is recovered from S3 and the local environment is rebuilt.
- **AND** the tool wrappers, their marker, and the proxy env file are written
- **AND** `sshConfig` and `env.sh` are written for this machine and name every restored host

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

#### Scenario: Database node registration waits only for the nodes that exist
- **GIVEN** a cluster with zero database nodes
- **WHEN** provisioning waits for the K3s nodes to register
- **THEN** it SHALL wait for the control and application nodes only
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

### Requirement: up checks the account can hold the new instances before it launches any

Before its first RunInstances, `up` MUST check that the account can hold the instances it is about to launch, and MUST fail without launching any instance when it cannot.  Every check counts only the instances to be launched: the ordinals in `0` to the configured count of each server type that no running or pending instance of that type holds, the same ordinals the launch fills.  When nothing is to be launched, `up` MUST make no capacity call and the checks pass.

The checks are:

1. The configured availability zones number at most `Constants.Vpc.MAX_AVAILABILITY_ZONES`.
2. Each instance type is offered in every zone its new instances land in (ordinal `i` lands in zone `i % zones`).
3. The On-Demand standard vCPU quota (`L-1216C47A`) minus the vCPUs of the running and pending On-Demand standard-family instances covers the new instances' vCPUs.  An instance type outside the standard families (A, C, D, H, I, M, R, T, Z) is not counted against it; `up` reports that its family's quota is not checked.
4. The network interface quota (`L-DF5E4CA3`) minus the network interfaces in the region covers the new nodes' interfaces: one per node under Flannel; under Cilium, the interfaces its node address and `Constants.Capacity.POD_ADDRESSES_PER_NODE` pod addresses fill, at most the type's maximum.
5. Each zone's subnet has free addresses for the node address of each new node landing in it, and under Cilium its pod addresses.  Without a VPC yet, the subnet size comes from the VPC CIDR.
6. Once the subnets and the security group exist, a RunInstances dry run of each server type's first RunInstances call, the same request that call makes (its subnet, count and ordinal tags, and the real AMI, instance type, security group, instance profile and key), answers `DryRunOperation`.  A throttled (`RequestLimitExceeded`) or 5xx answer is retried; only the final answer decides.

Every shortfall MUST be reported with a typed event naming what is short and by how much, and all of them MUST be reported before `up` stops.

#### Scenario: A cluster the account can hold passes

- **GIVEN** an account with room for the new instances
- **WHEN** the user runs `up`
- **THEN** every check passes and the instances are launched

#### Scenario: Too few vCPUs fails before any launch

- **GIVEN** an On-Demand standard vCPU quota that leaves fewer vCPUs free than the new instances need
- **WHEN** the user runs `up`
- **THEN** `up` fails before any RunInstances, naming the vCPUs needed, the vCPUs available and the shortfall

#### Scenario: A re-run with a larger count checks only the added instances

- **GIVEN** a cluster of 1,000 db nodes that already run, and a configured count of 1,002
- **WHEN** the user runs `up` again
- **THEN** the checks count 2 new db nodes, and only those are dry-run and launched

#### Scenario: Nothing to launch makes no capacity call

- **GIVEN** a cluster whose every configured instance already runs
- **WHEN** the user runs `up` again
- **THEN** no quota, offering, network interface, subnet or dry-run call is made

#### Scenario: A subnet short of addresses names its zone

- **GIVEN** a zone whose subnet has fewer free addresses than its new nodes and their pods need
- **WHEN** the user runs `up`
- **THEN** `up` fails before any launch, naming the zone, the addresses needed, the addresses free and the shortfall

#### Scenario: A launch AWS would refuse fails with AWS's message

- **GIVEN** a launch AWS refuses, for example for a missing permission or an unknown AMI
- **WHEN** `up` dry-runs it after the subnets and the security group exist
- **THEN** `up` fails before any real RunInstances, with AWS's error code and message

### Requirement: up works with 1,000 database nodes

`up` MUST provision and set up a cluster of 1,000 database nodes, and a re-run of `up` with a larger count MUST launch only the missing nodes.

- Discovery MUST read every page of DescribeInstances, count running and pending instances, and run once per `up`.
- A discovered instance that is pending, or has no public IP, MUST be waited on as a new instance is, until it runs with a public IP and passes its status checks, and MUST be kept with the IPs that wait read, so no host in the cluster state has a blank address.
- The instances to launch MUST be the ordinals in `0` to the configured count that no existing instance's alias holds, so a re-run fills the gaps an interrupted `up` left and never reuses the alias of a running instance.  Ordinal `i` is named `<serverType><i>` and lands in subnet `i % subnets`.
- RunInstances MUST launch one call per subnet, with `minCount = maxCount = n`, in chunks of at most `Constants.Scale.RUN_INSTANCES_CHUNK`, with a client token, and retry a throttled call (`RequestLimitExceeded`) with the same token.  The ordinals of one call are `subnets` apart, a gap starting a new call, and the call tags its instances with its first ordinal and that stride (`Constants.Scale.FIRST_ORDINAL_TAG`, `Constants.Scale.ORDINAL_STRIDE_TAG`), so the instance with launch index `k` is `first + k * stride`.  Each instance also gets its `Name` tag after launch.
- Each launched instance MUST be recorded in the cluster state as soon as RunInstances returns it, before it is named.
- Discovery MUST name an instance from its `Name` tag, or, when that tag never landed, from its launch ordinal tags and launch index.  The hosts in the cluster state MUST be one per alias, in ordinal order.
- The running wait MUST wait for every instance ID, tolerate `InvalidInstanceID.NotFound` while EC2 catches up, and ask only about the instances not yet running.  An instance it finds shutting-down, terminated, stopping or stopped MUST end the wait at the poll that sees it, naming each such instance with its alias and state and saying that a re-run of `up` replaces it; it MUST NOT be waited on until the deadline.  DescribeInstanceStatus and DescribeInstances requests MUST name at most 100 IDs.
- Per-host work (instance setup, AxonOps, registry TLS, K3s agents, the SSH readiness wait, the Cilium node image check) MUST run in parallel on at most `Constants.Scale.HOST_CONCURRENCY` hosts at once.  The SSH readiness wait MUST re-probe only the hosts not yet ready, within one time budget.
- After the K3s agents start, `up` MUST wait until every node is registered.  The node type and ordinal labels MUST be set in each agent's K3s configuration, not by a later per-node label call.
- The observability readiness wait MUST list pods once per poll under one deadline, and the rollout deadline MUST grow with the node count.  A pod with a container in a terminal state (`CrashLoopBackOff`, `ImagePullBackOff`, `ErrImagePull`, `Error`) MUST end the wait at the poll that sees it, naming the pod, the container and the reason; it MUST NOT be retried until the deadline.
- The observability rollout wait MUST, for each workload still rolling out, inspect that workload's pods of its latest template on every poll.  Those are the pods its selector matches that the workload owns (a pod's `ownerReferences` name the DaemonSet's uid, or the uid of the Deployment's current ReplicaSet), so another workload whose selector overlaps never fails it.  A DaemonSet's pod is of its latest template when its `pod-template-generation` label is the template's generation, or when its `controller-revision-hash` label is the hash of the DaemonSet's highest ControllerRevision, so a pod kept through a template set back to an earlier one (A, then B, then A) counts as current.  It MUST end the wait at the poll that sees a container of such a pod in a terminal state (the same classification as the readiness wait, except that a container in `CrashLoopBackOff` counts only once its `restartCount` reaches 3, `Constants.K8s.ROLLOUT_CRASH_LOOP_MIN_RESTARTS`, and the error then names the restart count and, for any container failure, how the container last exited (`last exit <code> (<reason>)`) and how many other current pods of the workload fail for the same reason), or a pending DaemonSet's pod that is not Ready on a node whose `Ready` condition has been `False` or `Unknown` for longer than 60 seconds (`Constants.K8s.ROLLOUT_NODE_NOT_READY_GRACE_SECONDS`), measured from the condition's `lastTransitionTime` (a pod that is already Ready never fails the wait for its node; a missing or unparseable `lastTransitionTime` counts as just transitioned).  A pending DaemonSet's terminating pod of an older template, on a node with no pod of the current template, MUST have its node judged the same way, because a rolling update creates the new pod there only after the old one is gone; the error then names the DaemonSet, the old pod and the node.  An image pull failure (`ErrImagePull`, `ImagePullBackOff`) or an `Error` still fails the wait at once; a crash loop fails it after 3 restarts.  It MUST read only the nodes of those pods, by name, and not list every node; it reads at most `Constants.K8s.ROLLOUT_NODE_CHECKS_PER_LOOK` of them per workload per poll, each poll reading on, by node name, from the last node the previous poll read and wrapping round, so a node that stays unready is read within `ceil(S / bound)` polls, where S is the number of distinct such nodes over those polls, even while other pods become Ready or are scheduled between polls.  The error MUST start with `Rollout cannot complete:` and name the workload, the pod, the node and the reason, and the wait MUST emit `K8s.RolloutBlocked` with those fields first.  A rollout that is only slow (pods being created, images being pulled) MUST still wait until the deadline.  While it waits, it MUST emit `K8s.RolloutsProgress` (the elapsed time and the workloads still pending) when that set changes, and every `Constants.Scale.PROGRESS_INTERVAL_SECONDS` while it does not.  When the deadline passes, it MUST emit `K8s.RolloutsTimedOut` and fail with an error naming each pending workload, what it waits on, and the pods it waits on (current pods not Ready, and stranded old pods) with each pod's node and state, at most `Constants.K8s.ROLLOUT_UNREADY_PODS_LISTED_PER_WORKLOAD` per workload and a count of the rest.

#### Scenario: A crash-looping pod fails the rollout wait after 3 restarts

- **GIVEN** an observability workload still rolling out whose current pod's container is in `CrashLoopBackOff`
- **WHEN** `up` waits for the observability rollouts
- **THEN** the wait goes on while the container has restarted fewer than 3 times
- **AND** fails at the poll that sees it in `CrashLoopBackOff` with 3 or more restarts, naming the workload, the pod, the node, the reason, the restart count and the container's last exit code

#### Scenario: A rollout that times out names the pods it waits on

- **GIVEN** an observability workload whose pod never becomes Ready, and has no container in a terminal state
- **WHEN** `up` waits for the observability rollouts and the deadline passes
- **THEN** the wait fails with an error naming the workload, what it waits on, and its unready pods, each with its node and state

#### Scenario: A DaemonSet pod that cannot pull its image fails the rollout wait at once

- **GIVEN** an observability DaemonSet whose pod on one node is in `ErrImagePull` or `ImagePullBackOff`
- **WHEN** `up` waits for the observability rollouts
- **THEN** the wait fails at the poll that sees it, well before the deadline, with an error naming the DaemonSet, the pod, the node and the reason

#### Scenario: A DaemonSet pod on a NotReady node fails the rollout wait after the node has been NotReady for 60 s

- **GIVEN** a pending observability DaemonSet with a pod that is not Ready on a node whose `Ready` condition is `False` or `Unknown`
- **WHEN** `up` waits for the observability rollouts
- **THEN** the wait fails after the node has been NotReady for 60 s, naming the DaemonSet, the pod, the node and the node's condition
- **AND** a node that has been NotReady for 60 s or less does not fail the wait

#### Scenario: Launched instances survive a failure later in up

- **GIVEN** an `up` whose second RunInstances call, running wait, or status check fails
- **WHEN** `up` stops
- **THEN** every instance already launched is in the cluster state, and a re-run does not launch it again

#### Scenario: A re-run fills the gaps a partial launch left

- **GIVEN** a cluster of 9 configured db nodes in 3 subnets whose db0, db3 and db6 run, because the launch into the second subnet failed
- **WHEN** `up` runs again
- **THEN** it launches exactly db1, db2, db4, db5, db7 and db8, each in subnet `ordinal % 3`, and the capacity checks count those six in those zones

#### Scenario: An instance whose name tag failed is still found

- **GIVEN** an `up` whose CreateTags call fails after RunInstances returned the instances
- **WHEN** `up` stops, and a later `up` discovers the cluster
- **THEN** the instances are in the cluster state, and discovery names them from their launch tags and does not launch them again

#### Scenario: A re-run in the pending window waits for the pending instances

- **GIVEN** an `up` that launched db0, which is still pending with no public IP
- **WHEN** `up` runs again
- **THEN** it waits until db0 runs and passes its status checks, does not launch it again, and records db0 with its public IP

#### Scenario: An instance EC2 ends during the running wait fails up at once

- **GIVEN** an `up` waiting for db1 to run
- **WHEN** EC2 reports db1 terminated
- **THEN** `up` fails at that poll, well before the wait's deadline, naming db1's instance ID, its alias and its state, and saying a re-run of `up` replaces it

#### Scenario: A re-run continues the numbering

- **GIVEN** a cluster whose db0 to db999 run
- **WHEN** `up` runs with a count of 1,002
- **THEN** it launches db1000 and db1001, in subnets `1000 % subnets` and `1001 % subnets`

#### Scenario: Every node registers before the storage classes are made

- **WHEN** `up` starts K3s on every node
- **THEN** it waits until every node is registered with the server, and fails naming the missing nodes when one does not register in time

### Requirement: Workspace files resolve against the workspace

Every workspace-owned file the CLI opens SHALL resolve against the workspace directory, never against the process's current directory. This covers `state.json`, `kubeconfig`, `environment.sh`, `setup_instance.sh`, `cassandra-jmx-rules.yaml`, `axonops-workbench.json`, the local Cassandra version conf directories, `profiles/` and `logs/`. The workspace directory SHALL be absolute. A path the user types as an argument SHALL resolve against the process's current directory, as before. A command SHALL create no file in the process's current directory unless that directory is the workspace.

#### Scenario: State is read and written in the workspace

- **GIVEN** a workspace W and a process whose current directory is a different directory D
- **WHEN** a command saves and then reloads cluster state
- **THEN** it writes and reads `W/state.json`
- **AND** nothing is created in D

#### Scenario: Kubernetes client uses the workspace kubeconfig

- **GIVEN** a workspace W holding `kubeconfig`, and a process whose current directory is D
- **WHEN** a command builds a Kubernetes client
- **THEN** the client is configured from `W/kubeconfig`

#### Scenario: Other workspace files resolve against the workspace

- **GIVEN** a workspace W and a process whose current directory is D
- **WHEN** `setup-instance`, `cassandra update-config`, `cassandra start`, the profiling fetch, or Spark log download opens or writes its workspace file
- **THEN** each path is under W

#### Scenario: Today's normal case is unchanged

- **GIVEN** the process's current directory is the workspace
- **WHEN** any command runs
- **THEN** it reads and writes the same files as today

#### Scenario: Missing state names its absolute path

- **GIVEN** a workspace W with no `state.json`
- **WHEN** a command loads cluster state
- **THEN** the error names the absolute path of `W/state.json` and tells the user to run the command from the cluster's workspace or run `init` there

#### Scenario: Uploading authorized keys leaves no file behind

- **WHEN** the user runs `upload-keys`
- **THEN** no `authorized_keys_extra` file remains in the workspace or the current directory

#### Scenario: A typed path resolves where it was typed

- **GIVEN** a process whose current directory is D
- **WHEN** the user runs `upload-keys keys` with a relative directory
- **THEN** it reads `D/keys`

### Requirement: init --local creates a local workspace with no AWS

`init --local` MUST create a workspace whose environment is `k3s-docker`, with no AWS API call and no AWS profile load or prompt.

`--data-dir PATH` sets the data directory:

- It defaults to `observability-data` under the workspace.
- A given relative path MUST be resolved against the current directory.
- `state.json` MUST record the absolute path.
- `--data-dir` without `--local` MUST make `init` fail, naming both options and writing nothing, reported the same way as a refused AWS-only option.

`init --local` MUST record:

- `tailscaleActive: false`;
- the local Docker capability set, with any capability overrides applied;
- Flannel as the CNI;
- the Docker daemon's architecture for every role, not the JVM's (they differ under Rosetta), so `init --local` needs a running Docker daemon;
- `exposure: PortForward`, so the CLI reaches cluster Services by a port-forward on loopback, on a free port chosen on each use. CQL is a single-endpoint session.

It MUST write `EDL_NETWORK_MODE=direct` to the proxy env file.

AWS-only `init` options given explicitly with `--local` MUST make `init` fail and name each option, writing nothing. These are `--instance`, `--db.instance-type`, `--azs`, the `--ebs.*`, `--spark.*` and `--opensearch.*` options, `--vpc`, `--cidr`, `--cni`, `--ami` and `--open`.

`clean` MUST NOT remove the data directory. `init` MUST NOT refuse a directory because the data directory already exists. Nothing in easy-db-lab deletes the data directory.

#### Scenario: init --local with no AWS configuration

- **GIVEN** a machine with no `~/.aws` configuration and no easy-db-lab profile
- **WHEN** the user runs `init --local` in an empty directory
- **THEN** it succeeds, makes no AWS API call and shows no profile prompt
- **AND** `state.json` records the `k3s-docker` environment

#### Scenario: Default data directory is under the workspace

- **WHEN** the user runs `init --local` with no `--data-dir`
- **THEN** `state.json` records `<workspace>/observability-data` as an absolute path

#### Scenario: A given data directory is recorded

- **WHEN** the user runs `init --local --data-dir ../shared-data`
- **THEN** `state.json` records the absolute path that `../shared-data` names from the current directory

#### Scenario: Local init writes direct mode to the env file

- **WHEN** `init --local` completes
- **THEN** the proxy env file holds `EDL_NETWORK_MODE=direct`

#### Scenario: AWS-only options are refused with --local

- **WHEN** the user runs `init --local --instance r5.xlarge --azs a,b`
- **THEN** `init` fails with a message that names `--instance` and `--azs`
- **AND** no file is written

#### Scenario: --data-dir without --local

- **WHEN** the user runs `init --data-dir /tmp/x`
- **THEN** `init` fails with an error that says `--data-dir` requires `--local`

#### Scenario: AWS init is unchanged

- **GIVEN** a configured AWS profile
- **WHEN** the user runs `init` without `--local`
- **THEN** `state.json` records the `ec2-k3s` environment and every other recorded value is as today
- **AND** the env file holds no `EDL_NETWORK_MODE`

### Requirement: up and down need a provisioner for the environment

`up` and `down` MUST dispatch on the recorded environment. For an environment with no provisioner in this easy-db-lab, they MUST fail before any side effect and before any profile check, with an `Environment.ProvisionerUnavailable` event that names the command and the environment. On `ec2-k3s`, `up` and `down` MUST behave as today.

#### Scenario: up in a local workspace before the local provisioner exists

- **GIVEN** a `k3s-docker` workspace and an easy-db-lab with no K3s-in-Docker provisioner
- **WHEN** the user runs `up`
- **THEN** it fails with a message that names `up` and `K3s in Docker`
- **AND** no profile prompt appears and no AWS call is made

#### Scenario: up on EC2 + K3s is unchanged

- **GIVEN** an `ec2-k3s` workspace
- **WHEN** the user runs `up`
- **THEN** provisioning, the account bucket preparation, the tunnel and the observability stack behave as today

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
