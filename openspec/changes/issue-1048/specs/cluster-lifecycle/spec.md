## MODIFIED Requirements

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

## ADDED Requirements

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
- The running wait MUST wait for every instance ID, tolerate `InvalidInstanceID.NotFound` while EC2 catches up, and ask only about the instances not yet running.  DescribeInstanceStatus and DescribeInstances requests MUST name at most 100 IDs.
- Per-host work (instance setup, AxonOps, registry TLS, K3s agents, the SSH readiness wait, the Cilium node image check) MUST run in parallel on at most `Constants.Scale.HOST_CONCURRENCY` hosts at once.  The SSH readiness wait MUST re-probe only the hosts not yet ready, within one time budget.
- After the K3s agents start, `up` MUST wait until every node is registered.  The node type and ordinal labels MUST be set in each agent's K3s configuration, not by a later per-node label call.
- The observability readiness wait MUST list pods once per poll under one deadline, and the rollout deadline MUST grow with the node count.  A pod with a container in a terminal state (`CrashLoopBackOff`, `ImagePullBackOff`, `ErrImagePull`, `Error`) MUST end the wait at the poll that sees it, naming the pod, the container and the reason; it MUST NOT be retried until the deadline.

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

#### Scenario: A re-run continues the numbering

- **GIVEN** a cluster whose db0 to db999 run
- **WHEN** `up` runs with a count of 1,002
- **THEN** it launches db1000 and db1001, in subnets `1000 % subnets` and `1001 % subnets`

#### Scenario: Every node registers before the storage classes are made

- **WHEN** `up` starts K3s on every node
- **THEN** it waits until every node is registered with the server, and fails naming the missing nodes when one does not register in time

## REMOVED Requirements

### Requirement: up makes sure the account compactor runs

**Reason**: The account compactor no longer runs as an ECS Fargate service.  Every cluster runs its own compactor Deployment, and an object-store lease picks the active one.  `up` deploys that Deployment as one stage of the observability stack.

**Migration**: None.  The `account-compactor` capability specifies the in-cluster compactor.

### Requirement: down stops the account compactor after the last cluster

**Reason**: There is no account service to stop.  The compactor stops with its cluster's control node, and a standby on another cluster takes the lease after it expires.

**Migration**: None.  `down` only flushes, waits and refuses to tear down on a failed flush, as the `Cluster Teardown` requirement specifies.
