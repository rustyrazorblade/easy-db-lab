## MODIFIED Requirements

### Requirement: Provisioning reports success only when it fully succeeded

The system MUST NOT report successful provisioning unless every provisioning step succeeded and every node is Ready.

A shared provisioning step is one that runs once for the cluster: writing configuration files, the control node's steps, the K3s server, the CNI install, Tailscale startup, StorageClass creation, IAM policy application, observability deployment and nested commands.  When a shared step fails, `up` MUST abort at that step and exit non-zero.

A node step is one that runs for one database or application node: running, status checks, SSH, setup, the image check, registry TLS, join, registration and Ready.  A failed node step MUST be retried and then fixed by replacing the node, as "up replaces a node whose step keeps failing" says.  It MUST NOT abort the other nodes.  A node that cannot be brought up MUST make `up` exit non-zero once the other nodes have finished.

There is no provisioning step whose failure may be logged and stepped over.  A step that is skipped because there is nothing for it to do has not failed.  A step that was attempted and threw has failed: a shared step aborts provisioning, and a node step is retried, replaced, or fails its node.

This applies to every step invoked during provisioning, including nested commands whose exit codes were previously discarded, and to operations whose results were previously reduced to a log line.

#### Scenario: A failing shared provisioning step aborts `up`
- **WHEN** a shared provisioning step fails during `up` (writing configuration files, a control node step, K3s server start, the CNI install, StorageClass creation, Tailscale startup, IAM policy application, or observability deployment)
- **THEN** `up` SHALL abort at that step
- **AND** `up` SHALL exit non-zero
- **AND** the reported error SHALL identify the step that failed

#### Scenario: A failing node step does not abort the other nodes
- **GIVEN** an `up` of several db nodes
- **WHEN** a step of db3 fails
- **THEN** db3's step SHALL be retried, and then db3 replaced, as its limits allow
- **AND** the other nodes SHALL continue through their own steps without waiting for db3

#### Scenario: A node that cannot be brought up fails `up` after the others finish
- **GIVEN** a node that fails for good: it ran out of replacements, it failed a step that a replacement cannot fix, or the cluster replacement limit was reached
- **WHEN** every other node's steps have ended
- **THEN** `up` SHALL run the shared steps that follow the nodes for the nodes that came up
- **AND** `up` SHALL exit non-zero
- **AND** the reported error SHALL name each failed node, the step it failed and why

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

## ADDED Requirements

### Requirement: up brings each node up in its own pipeline

`up` MUST bring up each database and application node in its own pipeline.  The pipeline runs that node's steps in order: running with a public IP, status checks, SSH, setup, the Cilium node image check (Cilium only), registry TLS, join, registration and Ready.  A node MUST start its next step as soon as its own step finishes.  It MUST NOT wait for any other node, except where a step waits for the control plane (the registry certificate, the control-plane gate, a join permit).

The first launch MUST stay batched, as "up works with 1,000 database nodes" says.  A node's pipeline MUST start when the RunInstances call that launched it returns.  A node found by discovery MUST start at once.

The control node MUST run its own pipeline: running, status checks, SSH, setup, Tailscale or the SOCKS tunnel, the registry certificate and its registry TLS, the K3s server and the kubeconfig, the CNI install, and the control-plane gate.  StorageClasses, the observability stack, AxonOps and the final rewrite of the workspace files MUST run once, after every node's pipeline has ended.

At most `Constants.Scale.HOST_CONCURRENCY` SSH sessions MUST be open at once across all pipelines.

`--no-setup` MUST end each pipeline after SSH.  `--hosts` MUST limit the database and application pipelines to the named hosts, and MUST NOT limit the control node's.

#### Scenario: A slow node does not hold back the others
- **GIVEN** an `up` of 10 db nodes where db4's setup takes much longer than the others'
- **WHEN** the other nodes finish setup
- **THEN** they go on to registry TLS, join and Ready while db4 is still in setup

#### Scenario: A node whose launch call returns first starts first
- **GIVEN** an `up` of 300 db nodes launched in several RunInstances calls
- **WHEN** the first call returns
- **THEN** that call's nodes start their pipelines before the later calls return

#### Scenario: The SSH session limit holds across pipelines
- **GIVEN** an `up` of 200 nodes
- **WHEN** their pipelines run SSH steps
- **THEN** no more than `Constants.Scale.HOST_CONCURRENCY` SSH sessions are open at any time

#### Scenario: Registry TLS waits for the certificate
- **GIVEN** a db node that reaches registry TLS before the control node has uploaded the registry certificate
- **WHEN** its registry TLS step starts
- **THEN** it waits until the certificate is uploaded, then configures the node

#### Scenario: --hosts limits the node pipelines only
- **WHEN** the user runs `up --hosts db2`
- **THEN** only db2's pipeline and the control node's pipeline run

#### Scenario: A small cluster ends as it does today
- **GIVEN** a cluster of 1 control, 3 db and 1 app node
- **WHEN** the user runs `up`
- **THEN** every node is Ready, the StorageClasses and the observability stack are deployed, and `up` exits zero, as before this change

### Requirement: No node joins before the control plane is ready

No database or application node MUST start its K3s agent before the control-plane gate is open.  The gate MUST open only when all of these hold:

- the API server answers its readiness check;
- the control node is registered;
- under Cilium, the Cilium operator has its desired ready replicas;
- under Cilium, every CRD the Cilium agent needs at the pinned Cilium version exists and is Established.

The gate MUST open once and stay open.  If it does not open within `Constants.Scale.GATE_TIMEOUT_SECONDS`, `up` MUST fail and name each condition that was still false.

#### Scenario: Agents wait for the Cilium CRDs
- **GIVEN** a Cilium cluster whose operator is running but has not yet established its CRDs
- **WHEN** db nodes reach their join step
- **THEN** none starts its K3s agent until every required CRD is Established

#### Scenario: A Flannel cluster's gate needs no Cilium
- **GIVEN** a `--cni=flannel` cluster
- **WHEN** the API server is ready and the control node is registered
- **THEN** the gate opens

#### Scenario: The gate times out
- **GIVEN** a Cilium operator that never becomes ready
- **WHEN** `Constants.Scale.GATE_TIMEOUT_SECONDS` passes
- **THEN** `up` fails, naming the operator as not ready and each CRD not yet Established

### Requirement: At most a fixed number of nodes join at once

At most `Constants.Scale.JOIN_CONCURRENCY` nodes MUST be in their join step at once.  A node MUST hold its join permit from the start of its K3s agent configuration until its Node object is registered, and MUST release it before it waits for Ready.  Starting the K3s agent MUST NOT block an SSH session until the agent has registered.  Registration MUST be read from the Kubernetes API.

#### Scenario: The join limit holds
- **GIVEN** an `up` of 80 nodes that reach their join step together
- **WHEN** they join
- **THEN** no more than `Constants.Scale.JOIN_CONCURRENCY` are in their join step at any time

#### Scenario: A permit is released at registration
- **GIVEN** a node whose Node object has registered but is not yet Ready
- **WHEN** another node is waiting for a join permit
- **THEN** the waiting node gets the permit without waiting for the first node to be Ready

#### Scenario: A stalled join holds no SSH session
- **GIVEN** a K3s agent that cannot register because the control plane is saturated
- **WHEN** its node waits for registration
- **THEN** no SSH session to that node stays open for the wait

### Requirement: up replaces a node whose step keeps failing

A node step that fails MUST be retried under that step's policy.  When its attempts run out, and the failure is one a new instance can fix, `up` MUST replace the node:

1. wait until the control plane is healthy;
2. terminate the old instance, and wait until EC2 reports it neither pending nor running;
3. if the node had registered, delete its Node object and its K3s node password;
4. check capacity and dry-run the launch for one instance;
5. launch one instance with the same alias, in the subnet for that alias's ordinal, with the same instance type, AMI, tags and volumes;
6. replace that alias's host in the cluster state with the new instance ID and addresses, in one atomic write, keeping one host per alias;
7. start the node's pipeline again.

A replaced instance MUST always be terminated before its replacement is launched.  An instance that EC2 ends on its own (shutting-down, terminated, stopping or stopped) during any step MUST be replaced at once, with no retry.  A Cilium node image check failure MUST NOT be replaced: it MUST fail `up`, as before.  A replacement launch that fails for capacity, or whose dry run is refused, MUST fail the node and start no further replacement for it.  The control node MUST NOT be replaced: a control node step that fails after its retries MUST fail `up` at once.  Every instance launched so far MUST stay running and recorded.

#### Scenario: A step that fails once is retried
- **GIVEN** a db node whose registry TLS step fails once and then succeeds
- **WHEN** its pipeline runs
- **THEN** the node becomes Ready with no replacement

#### Scenario: A node whose setup keeps failing is replaced
- **GIVEN** db5, whose setup fails on every attempt
- **WHEN** its attempts run out
- **THEN** db5's instance is terminated
- **AND** a new instance named db5 is launched in db5's subnet after a capacity check and dry run for one instance
- **AND** the cluster state holds db5 once, with the new instance ID and addresses
- **AND** db5's pipeline starts again from the running step, while the other nodes go on

#### Scenario: A registered node is removed from K3s before its replacement joins
- **GIVEN** db7, which registered and then failed to become Ready on every attempt
- **WHEN** it is replaced
- **THEN** the Node object db7 and its K3s node password are deleted before the new db7 joins
- **AND** the new db7 registers under the same name

#### Scenario: An instance killed mid-setup is replaced
- **GIVEN** db2's instance is terminated outside `up` while its setup runs
- **WHEN** `up` sees the instance end
- **THEN** db2 is replaced with no retry of setup, and the other nodes are not interrupted

#### Scenario: An AMI missing the Cilium node fixes is not replaced
- **GIVEN** a Cilium cluster whose node fails the node image check
- **WHEN** the check fails
- **THEN** `up` fails naming the node and the missing files, and no replacement is launched

#### Scenario: A control node failure fails up
- **GIVEN** a control node whose K3s server does not start after its retries
- **WHEN** the step fails
- **THEN** `up` fails at once, naming the step, and every launched instance stays running and recorded

### Requirement: Replacements are limited

`up` MUST replace one node at most `Constants.Scale.MAX_NODE_REPLACEMENTS` times.  In all, `up` MUST make at most the larger of `Constants.Scale.MIN_CLUSTER_REPLACEMENTS` and `Constants.Scale.CLUSTER_REPLACEMENT_FRACTION` of the node count, rounded up.  A node that needs a replacement past either limit MUST fail.  Its last instance MUST be kept running and recorded in the cluster state.  A re-run of `up` MUST send it through its pipeline again.

#### Scenario: A node runs out of replacements
- **GIVEN** db9, whose setup fails on every instance
- **WHEN** it has been replaced `Constants.Scale.MAX_NODE_REPLACEMENTS` times and fails again
- **THEN** db9 fails, its last instance is kept, and `up` exits non-zero naming db9 once the other nodes finish

#### Scenario: A fault every node has stops at the cluster limit
- **GIVEN** an `up` of 80 nodes whose setup fails on every node
- **WHEN** the cluster replacement limit is reached
- **THEN** no further replacement is launched, and `up` fails naming the failed nodes

### Requirement: up watches control-plane health while nodes join

From the K3s server's start until the last node's pipeline ends, `up` MUST sample the control plane every `Constants.Scale.HEALTH_SAMPLE_INTERVAL_SECONDS`.  A sample is the latency of a minimal `get nodes` request and the growth of the API server's rejected-request count since the last sample.  The control plane MUST be unhealthy when a sample's latency exceeds `Constants.Scale.HEALTH_LATENCY_LIMIT_MS`, its rejection growth exceeds `Constants.Scale.HEALTH_REJECTION_LIMIT`, or the API cannot be reached.  It MUST be healthy again after `Constants.Scale.HEALTH_RECOVERY_SAMPLES` healthy samples in a row.

While the control plane is unhealthy, `up` MUST NOT give out a join permit and MUST NOT start a replacement.  A join or Ready step that times out while it is unhealthy MUST NOT count against its node.  `up` MUST report the change to unhealthy and the change back to healthy, once each.  If the control plane stays unhealthy for `Constants.Scale.HEALTH_UNHEALTHY_TIMEOUT_SECONDS`, `up` MUST fail, naming the last sample.  `up` MUST NOT restart K3s itself.

#### Scenario: An unhealthy control plane stops joins and replacements
- **GIVEN** nodes waiting to join, and a node due for replacement
- **WHEN** a sample shows the API server rejecting requests above the limit
- **THEN** no join permit is given out and no replacement starts, and `up` reports the control plane unhealthy with the latency and rejection count

#### Scenario: The control plane recovers
- **GIVEN** an unhealthy control plane
- **WHEN** `Constants.Scale.HEALTH_RECOVERY_SAMPLES` healthy samples follow in a row
- **THEN** `up` reports it healthy, with how long it was unhealthy, and joins resume

#### Scenario: A join timeout while unhealthy is not the node's fault
- **GIVEN** a node whose registration times out while the control plane is unhealthy
- **WHEN** the control plane recovers
- **THEN** the node's join runs again without counting a failed attempt or a replacement

#### Scenario: A control plane that never recovers fails up
- **GIVEN** a control plane unhealthy for `Constants.Scale.HEALTH_UNHEALTHY_TIMEOUT_SECONDS`
- **WHEN** that time passes
- **THEN** `up` fails, naming the last latency and rejection count

### Requirement: up reports each node's progress

`up` MUST emit typed events for each node step's start, completion and failure, each replacement's start and completion, each node's readiness, the control-plane gate, and each control-plane health change.  Step start and completion events MUST NOT be printed to the console.  Every other event in this requirement MUST be printed.  While pipelines run, `up` MUST print a progress line every `Constants.Scale.PROGRESS_INTERVAL_SECONDS` with the elapsed time, the number of nodes in each step, the joins in flight against the limit, and the counts of Ready, replaced and failed nodes and the control-plane state.

#### Scenario: The progress line names where every node is
- **GIVEN** an `up` of 80 nodes in which 16 are joining, 49 are Ready and 15 are in setup
- **WHEN** a progress interval passes
- **THEN** `up` prints one line with the elapsed time and those counts

#### Scenario: A replacement is reported
- **WHEN** a node is replaced
- **THEN** `up` prints the alias, the old instance ID, the failed step and the reason, then the new instance ID and private IP

#### Scenario: Step events reach Redis but not the console
- **GIVEN** `EASY_DB_LAB_REDIS_URL` is set
- **WHEN** a node starts and completes a step
- **THEN** the Redis channel receives both events, and the console prints neither
