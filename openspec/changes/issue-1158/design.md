## Context

`Up.execute()` today is a fixed sequence of cluster-wide phases.  Each per-node phase fans out through `BoundedParallel` (`Constants.Scale.HOST_CONCURRENCY` = 50) and waits for every node before the next phase starts.

1. `provisioningPreflight.verify`, account bucket, test document index.
2. `provisionInfrastructure`: discovery, `LaunchCapacityPreflight.verifyCapacity`, VPC and networking, `verifyLaunchPermitted`, then `ClusterProvisioningService.provisionAll`.  That makes one thread per server type, batched `RunInstances` (one call per subnet, at most 100 a call), the running wait and the status-check wait for the whole type, and the EMR and OpenSearch threads.  Each batch is written to `state.json` through `onHostsCreated` as soon as it launches.
3. `writeConfigurationFiles` (sshConfig, env.sh, environment.sh, AxonOps workbench) and `WriteConfig` (cassandra.patch.yaml; its seed is db0's private IP).
4. `SshReadinessWait`: control and db hosts, one time budget.
5. `downloadCassandraVersions` from db hosts.
6. `SetupInstance`: app hosts, then db hosts, then control.  Each group runs in parallel and waits for the whole group.  environment.sh names db0's private IP.
7. Tailscale start and route check, or the SOCKS tunnel.
8. `startK3sOnAllNodes`: the Cilium node image check (every node), registry TLS (certificate on control, then every node), then `K3sClusterService.setupCluster`.  That starts the server, reads the token, downloads the kubeconfig, and runs `onServerReady` (`cilium install`, which returns without waiting for the operator).  Then every agent's `configure` and `start` run on the pool of 50.  `start` runs `start-k3s-agent.sh`, which ends in a blocking `systemctl start k3s-agent`.
9. `labelControlAndAwaitNodes`: label control, then wait until every node is registered.
10. Storage classes, the observability stack, Cilium install annotations, AxonOps.

Per node: running, status checks, SSH, setup, image check, registry TLS, agent configure and start, registration.  Shared: preflights, VPC, the batched launch, workspace files, the registry certificate, Tailscale or the tunnel, the K3s server, kubeconfig, Cilium, storage classes, observability, AxonOps, EMR and OpenSearch.

At 79 nodes (#1134), step 8 started 50 agents at once against a sqlite-backed server while the Cilium operator was still creating its CRDs.  The API server's APF rejected thousands of requests, the operator restarted 3 times on CRD-creation timeouts, and 50 SSH sessions sat in `systemctl start k3s-agent` for 10 minutes.  `up` printed nothing during that time.  #1157 moves the datastore to embedded etcd.  That is the structural fix for write latency and is a separate change.  This change fixes how `up` drives the nodes: no join before the control plane is ready, a cap on simultaneous joins, detection and reporting of an unhealthy control plane, and per-node recovery.

Constraints: no source checkout at runtime; resilience4j for retries; typed events; constants in `Constants`; detekt with no suppressions; Koin; no SOCKS JVM properties (the K8s API is reached through `K8sClientProvider`'s per-client proxy, as today).

## Goals / Non-Goals

**Goals:**

- Each node moves through its own steps without waiting for the other nodes.
- A node that fails is retried, then replaced, without blocking the other nodes.
- No agent joins before the control plane is ready, and at most `JOIN_CONCURRENCY` join at once.
- An unhealthy control plane stops joins and replacements, and `up` reports it.
- A bad node is shut down and replaced with no manual step, and the rest of the cluster does not wait for it.
- A small cluster gets the same result as today.

**Non-Goals:**

- The K3s datastore (#1157).
- More than one control node, or replacing the control node (owner decision 3).
- Restarting K3s automatically when the control plane is unhealthy.  The issue asks `up` to wait and report.  A restart remedy can follow once the later 80-node data shows whether it is needed.
- Retrying a launch in another zone or instance type after `InsufficientInstanceCapacity`.  The existing spec forbids it.
- Event timestamps on the console.  The progress line carries the elapsed time.
- Parallelising the operator-side fan-outs of `status` and `exec`.

## Decisions

### D1. A bring-up coordinator with one virtual thread per node

A new package `services/bringup/` holds the following:

- `NodeBringUp`: the coordinator `up` calls.
- `NodePipeline`: one node's ordered steps.
- `NodeStep`: a step with a name, a retry policy, a timeout, and whether its failure can be fixed by replacing the node.
- `ControlPlaneGate`.
- `JoinLimiter`.
- `ControlPlaneHealthMonitor`.
- `NodeReplacer`.
- `BringUpProgress`.

`Up` becomes a list of shared steps around one `nodeBringUp.run(...)` call.  The coordinator starts each pipeline on `Executors.newVirtualThreadPerTaskExecutor()`.  It also keeps the existing EMR and OpenSearch threads.

Virtual threads, because 1,000 pipelines spend almost all their time blocked: on EC2 state, on SSH, on the gate, on a join permit, on node readiness.  A platform thread per node costs a megabyte of stack each, and a pool of 50 brings back the head-of-line blocking this change removes.

Bounded resources are bounded by semaphores, not by the thread count:

- **SSH sessions:** `HOST_CONCURRENCY` (50).  Every SSH step takes a permit for the length of its remote call, so the operator's machine still opens at most 50 sessions.
- **Joins:** `JOIN_CONCURRENCY` (16), see D4.

`EventContext` is a `ThreadLocal`, and a virtual thread does not inherit it.  So each pipeline pushes the `up` command name when it starts, and events keep their `commandName`.

Alternatives: keep `BoundedParallel` per phase and add only the gate and the join limit.  That fixes the #1134 stall, but one slow or bad node still holds every other node at each barrier, and there is nowhere to put per-node replacement.  `StructuredTaskScope` is a preview API in Java 21 and is not used.

### D2. Steps, and which ones are shared

The order inside `up`:

1. **Before any pipeline (shared, unchanged):** the provisioning preflight, the account bucket, discovery, `verifyCapacity`, VPC and networking, `verifyLaunchPermitted`.  Then the **batched first launch**: `RunInstances` per subnet, in chunks of 100, as today.  A per-node `RunInstances` would make 1,000 calls and be throttled.  So the first launch stays batched, and each node's pipeline starts the moment its batch returns its instance ID.  Discovered instances (a re-run) start their pipelines at once.
2. **Control pipeline:** running, status checks, SSH, setup, Cilium image check.  Then `up` writes the workspace files for the hosts known so far, because the tunnel reads `sshConfig`.  Then Tailscale start and route check, or the SOCKS tunnel.  Then the registry certificate (generate, upload) and registry TLS on control.  This step releases the **certificate latch**.  Then the K3s server, token and kubeconfig, which release the **server latch**.  Then the control node's `type=control` label, the Cilium install and the Tailscale masquerade when it applies.  Then it starts the health monitor and waits for the gate (D3) to open.
3. **db and app pipelines:** running, status checks, SSH, setup, Cilium image check, registry TLS (waits on the certificate latch), join (waits on the gate, then takes a join permit), registered, Ready.
4. **After every pipeline has ended (shared):** rewrite the workspace files with the final hosts (D6), storage classes, the observability stack, the Cilium install annotations, AxonOps, and wait for EMR and OpenSearch.

Cross-node inputs, and how each is met:

- `environment.sh` and the app nodes' `stress.env` name db0's private IP, and `cassandra.patch.yaml` seeds name it too.  Private IPs are known when `RunInstances` returns.  So the workspace files are written right after the first launch, before any setup step runs.  If db0 is later replaced, the shared post-step rewrites the files and pushes the two environment files to every node again.  This is the only case where a node's setup output depends on another node.
- `cassandra_versions.yaml` is downloaded once, by the first db pipeline to pass SSH.  The download sits behind a once-latch.  It is skipped when the file exists, as today.
- Registry TLS on a node needs the certificate in S3, so it waits on the certificate latch.
- `SetupInstance` today reads the region over IMDS from the first app node, to set the app nodes' `CASSANDRA_EASY_STRESS_DEFAULT_DC`.  Each app pipeline reads its own node's region instead.  Every node is in the same region, so the value is the same.  This removes a dependency on another node.

`SetupInstance`'s per-node work moves into a `NodeSetupService` with one method per server type.  `setup-instances` (the command) keeps its behaviour by calling that service through `HostOperationsService.withHosts`.

`--no-setup` ends each pipeline after SSH, as today.  `--hosts` limits the db and app pipelines to the named hosts, as it limits setup and agent start today.  The control pipeline always runs.

### D3. The control-plane gate

The gate opens once, when the following are all true, read through the K8s API client:

- `/readyz` on the API server answers 200.
- The control node is registered.
- **Cilium only:** the `cilium-operator` Deployment has its desired number of ready replicas.
- **Cilium only:** every CRD in `Constants.Cilium.REQUIRED_CRDS` exists and has the `Established` condition.  This is the set the Cilium agent waits for at start, for `Constants.Cilium.VERSION`: CiliumNode, CiliumEndpoint, CiliumIdentity, CiliumNetworkPolicy, CiliumClusterwideNetworkPolicy, and the others that version registers.  The list is checked against the pinned version during implementation.  A Cilium version bump must update it, and the KDoc says so.

The gate polls every `Constants.Scale.GATE_POLL_INTERVAL_SECONDS` (5) under a deadline of `GATE_TIMEOUT_SECONDS` (600).  If the deadline passes, the control node has failed (D7), and the event names each condition that was still false.  Before the gate opens, `Cilium.OperatorReady` and `K3s.ControlPlaneGateOpen` are emitted.

Why a CRD check as well as operator readiness: in #1134 the operator was Running and restarting while its CRD creation timed out.  The agents wait for CRDs, not for the operator.

Under Flannel there is no operator and no CRD.  The gate is the API server and the registered control node.

### D4. The join limit, and a join that does not block on systemd

`JoinLimiter` is a fair `Semaphore(JOIN_CONCURRENCY)` (16).  It is a constant in `Constants.Scale`, as the issue asks.  16 sits in the issue's range of 10 to 20.  At about 15 s per registration, 1,000 nodes join in about 16 minutes.

A pipeline takes a permit after the gate is open and while the control plane is healthy (D8).  It holds the permit through `configure`, the agent start and **registration**: its Node object exists.  It releases the permit before it waits for Ready.  The registration burst (Node create, lease, node-password secret, CiliumNode) is what saturated the datastore.  Ready mostly waits on the node's own Cilium agent and image pulls.  Holding the permit until Ready would make the join of 1,000 nodes about 5 times slower, and gives the health check nothing it does not already see.

`start-k3s-agent.sh` ends with `systemctl start --no-block k3s-agent`.  Registration is read from the K8s API by one shared node informer (D9), not from the SSH call.  No SSH session or SSH permit is then held while an agent waits on the server, which is what hung in #1134.

### D5. Retry, then replace

Each `NodeStep` has a resilience4j policy built with `RetryUtil`.  It uses the checked decorators where SSH can throw a checked exception, and an `InterruptedException` is never retried.

| Step | Attempts | Timeout per attempt | Replaceable |
|---|---|---|---|
| running + public IP | 1 | `INSTANCE_RUNNING_TIMEOUT` | yes |
| status checks | 1 | `INSTANCE_STATUS_TIMEOUT` | yes |
| SSH ready | polls within `SSH_READY_TIMEOUT_SECONDS` | as today | yes |
| setup | 2 | `NODE_SETUP_TIMEOUT_SECONDS` (900) | yes |
| Cilium image check | 1 | as today | **no**, fails `up` (an AMI fault; a replacement has the same image) |
| registry TLS | 3 | 120 s | yes |
| join (configure + start + registered) | 2, the second runs `systemctl restart k3s-agent` | `NODE_REGISTRATION_TIMEOUT_SECONDS` (300) | yes |
| Ready | 1 | `NODE_READY_TIMEOUT_SECONDS` (600) | yes |

When a step's attempts run out and the step is replaceable, the node is replaced (D6) and its pipeline starts again from "running".  Some instances end on their own: the shared state watcher sees `shutting-down`, `terminated`, `stopping` or `stopped` while the pipeline is in any step.  Those are replaced at once, with no retry, because waiting cannot bring them back.  This supersedes the 1048 scenario "An instance EC2 ends during the running wait fails up at once" (see overrides).

A failure of the join or Ready step while the control plane is unhealthy is not counted against the node.  The step waits for health to return (D8) and then runs again.

A failure that is not replaceable, or a replacement whose launch fails with `InsufficientInstanceCapacity` or a refused dry run, ends that node's pipeline as failed.  It does not start another replacement.

### D6. How a node is replaced

`NodeReplacer` runs one replacement at a time under a lock.  Two replacements checking capacity together could both see room for one.  Replacements are rare, so serialising them costs nothing.

1. Wait until the control plane is healthy (D8).
2. `TerminateInstances` on the old instance.  Wait until EC2 reports it out of `pending` and `running`.  Discovery counts only `pending` and `running`, so no later `up` or `down` sees two instances with one alias.
3. If the node had registered: delete its Node object and its K3s node-password secret (`<alias>.node-password.k3s` in `kube-system`).  Without that, K3s refuses the new instance's registration under the same name ("Node password rejected").  The Node delete also lets the Cilium operator collect the old CiliumNode.  Its secondary ENIs are deleted with the instance (Cilium's ENI `deleteOnTermination` default).
4. Capacity: `LaunchCapacityPreflight.verifyCapacity` for a one-instance `LaunchGroup` with the node's ordinal, then `verifyLaunchPermitted` for that one-instance launch.  Each replacement is counted against the quotas, the subnet addresses and the dry run, as the issue asks.
5. Launch one instance.  It has the same alias, the subnet `ordinal % subnets` (the alias's zone), and the same AMI, type, tags and EBS config as the first launch.  Its ordinal tags are `first = ordinal` and `stride = 1`.  `EC2InstanceService` gets a single-instance launch that reuses the batched path's request builder.
6. Under the state lock, replace the host with that alias in `state.json` (new instance ID, IPs, zone) and save.  The save is atomic, as today: a temp file and a rename.  The host list stays one entry per alias, in ordinal order.
7. Emit `Provision.NodeReplaced` and start the pipeline again.

After the last pipeline ends, the shared post-step rewrites `sshConfig`, `env.sh` and the other workspace files from `state.json`.  If db0 was replaced, it also pushes `environment.sh` and `stress.env` to every node again (D2).

### D7. Limits, and how `up` ends

- Per node: at most `Constants.Scale.MAX_NODE_REPLACEMENTS` replacements (2, so 3 instances in all; owner decision 1).
- Cluster-wide: at most `max(MIN_CLUSTER_REPLACEMENTS = 3, ceil(nodes × CLUSTER_REPLACEMENT_FRACTION = 0.05))`.  A fault every node has, for example a setup script bug, a missing package or a bad kernel, would otherwise replace the whole cluster 2 times over.  When the cluster limit is reached, no more replacements start.  A node that would need one fails.
- The control node is not replaced (owner decision 3).  A control step failure after its retries fails `up` at once.  Every other pipeline is interrupted, because none can join without it.  The control node and every instance launched so far stay running and recorded, for debugging and for a re-run.

When a db or app node fails for good (out of replacements, a non-replaceable failure, or the cluster limit), `up` **fails after the others finish** (owner decision 2).  The other pipelines run to the end.  The shared post-steps run for the nodes that came up, so Grafana and the logs are there to debug with.  Then `up` emits `Provision.NodesFailed` with each failed alias, its last step and the reason, and exits non-zero.  The failed node's last instance is kept, not terminated, for debugging.  A re-run of `up` sends it through its pipeline again.

This changes the requirement "Provisioning reports success only when it fully succeeded".  A node step failure no longer aborts `up` at that step.  `up` still never reports success unless every node is Ready and every shared step succeeded.

### D8. The control-plane health check

`ControlPlaneHealthMonitor` starts after the K3s server is up and runs on its own virtual thread until the last pipeline ends.  Every `HEALTH_SAMPLE_INTERVAL_SECONDS` (5) it takes one sample:

- The latency of a `get nodes` call with `limit=1`.  It goes through the datastore like a full list but does not transfer 1,000 nodes.  A timeout counts as the maximum.
- The growth since the previous sample of `apiserver_flowcontrol_rejected_requests_total`, summed over priority levels.  It is read from the API server's `/metrics` through the same client.  This is the 429 count #1134 saw: node-high 2027, global-default 596.

The control plane is **unhealthy** when one sample has a latency above `HEALTH_LATENCY_LIMIT_MS` (2,000) or more than `HEALTH_REJECTION_LIMIT` (25) new rejections.  It is **healthy** again after `HEALTH_RECOVERY_SAMPLES` (3) healthy samples in a row.  The latency goes through the SOCKS tunnel or Tailscale, which adds tens of milliseconds, so the limit is set well above that.

While unhealthy:

- No join permit is given out.  Pipelines already holding one keep it.
- No replacement starts.
- A join or Ready step that times out is not counted (D5).

It emits `Provision.ControlPlaneUnhealthy` once on the change to unhealthy, with the latency, the rejection growth and the limits.  It emits `ControlPlaneHealthy` once on recovery, with how long it lasted.  If it stays unhealthy for `HEALTH_UNHEALTHY_TIMEOUT_SECONDS` (900), the control node has failed (D7).  The event names the last sample, and the message suggests `systemctl restart k3s` on the control node as #1134 found.  `up` does not restart K3s itself (Non-Goals).

A sample that cannot reach the API at all counts as unhealthy.  It does not fail `up` by itself.

### D9. Shared watchers instead of per-node polling

1,000 pipelines that each poll EC2 or the K8s API would be throttled, and would load the control plane this change protects.  So two shared watchers serve every pipeline:

- **Instance state watcher:** one `DescribeInstances` and one `DescribeInstanceStatus` per poll for every instance still waiting, at most 100 IDs a request, as today.  It completes each node's future when it is running with a public IP or has passed its status checks, and fails it when the instance ends.  It keeps watching instances that are already past those steps, so the end of one is seen in any step (D5).
- **Node watcher:** one fabric8 informer on Node objects, through the existing per-client proxy.  It gives each pipeline its node's "registered" and "Ready" signals.  One list and one watch replace a list every 5 s per waiting node.  `K8sService.waitForNodesRegistered` is no longer used by `up`.

### D10. Events and progress

New typed events, per events/CLAUDE.md:

- `Provision.NodeStepStarted(alias, step, attempt)` and `Provision.NodeStepCompleted(alias, step, attempt, durationMs)`.  At 1,000 nodes with 9 steps this is 18,000 events, so they go to Redis but **not to the console**.  `Event` gets `fun showOnConsole(): Boolean = true`, which these two override, and `ConsoleEventListener` skips them.  That is the `event-system` delta.
- `Provision.NodeStepFailed(alias, step, attempt, maxAttempts, reason, willRetry)`: an error event.
- `Provision.NodeReplacing(alias, oldInstanceId, step, reason, replacement, limit)` and `Provision.NodeReplaced(alias, oldInstanceId, newInstanceId, privateIp)`.
- `Provision.NodeReady(alias, instanceId, elapsedSeconds, replacements)`: one console line per node.  This is about the line count today's `K3s.AgentConfiguring` already prints.
- `Provision.NodesFailed(nodes: List<FailedNode(alias, step, reason, instanceId)>)`: an error event.
- `K3s.ControlPlaneGateOpen(waitedSeconds)`, `Cilium.OperatorReady`, and `K3s.ControlPlaneGateTimedOut(conditions)` (error).
- `Provision.ControlPlaneUnhealthy(latencyMs, rejections, latencyLimitMs, rejectionLimit)` (error) and `Provision.ControlPlaneHealthy(unhealthySeconds)`.
- `Provision.JoinWaiting(alias, inFlight, limit)` is not added.  The progress line shows the joins in flight.
- `Provision.BringUpProgress(elapsedSeconds, total, byStep: Map<String, Int>, joining, ready, replacements, failed, controlPlane)`.  It is emitted every `PROGRESS_INTERVAL_SECONDS` (15) while pipelines run.  Its console line looks like this: `[4m30s] 80 nodes: ssh 2, setup 11, join 16/16, ready 49, replaced 1, failed 0, control plane healthy`.  In #1134, `up` went silent for 10 minutes.  This line names the step every node is in.

The existing per-service events (`K3s.AgentConfiguring`, `Ec2.InstancesCreating` and so on) stay.

### D11. Testing

Unit tier: the coordinator and the policies are pure logic over interfaces.  The tests use fakes, not mocks.

- `FakeNodeSteps`: a per-alias, per-attempt script of outcomes.
- `FakeInstanceFleet`: launch, terminate and state, with recorded calls.
- `FakeControlPlane`: scripted health samples and gate conditions.
- A fake `ClusterStateManager` that records each save.
- Zero intervals.

Each test makes a decision observable, for example a call order, a state file entry, or an exit outcome.  Cases:

- A slow node does not delay another node's join.  The other node is Ready while the slow one is still in setup.
- A step that fails once is retried and the node becomes Ready with no replacement.
- A step that fails every attempt replaces the node.  The new instance has the same alias and subnet.  The old one was terminated before the launch.  `state.json` holds the new ID and IPs, one entry per alias.  The Node and the node-password secret were deleted.  The capacity check and the dry run ran for exactly one instance.
- An instance terminated mid-setup is replaced with no retry.
- The per-node limit is reached, the node fails, the others finish, the post-steps run, and `up` exits non-zero naming the node and its step.  Its last instance is not terminated.
- The cluster limit is reached, so no further replacement starts.
- A Cilium image check failure fails `up` and makes no replacement.
- No join starts before the gate opens.  The gate waits for every condition, and its timeout names the false ones.
- With 40 nodes, at most 16 joins are ever in flight, measured by the fake.
- A permit is released at registration, not at Ready.
- While the control plane is unhealthy, no permit is issued and no replacement starts.  A join timeout in that window is not counted.  It recovers after 3 healthy samples.  Staying unhealthy past the deadline fails `up`.
- A control step failure interrupts every pipeline and fails `up`.
- `--no-setup` ends the pipelines after SSH.
- `--hosts` runs only the named pipelines.
- The progress event counts nodes per step correctly.
- Health samples become unhealthy or healthy at the boundaries.
- `ConsoleEventListener` does not print a `showOnConsole() = false` event.

Integration tier (K3s TestContainers, since `K8sService` is never mocked for cluster behaviour):

- The gate's readiness reader reports a CRD as present only once it is `Established`.
- The replacement cleanup deletes the Node and the `<alias>.node-password.k3s` secret.
- The health sampler reads latency and the rejected-requests counter from a real API server.

The real AWS verification is in tasks section 10.

### D12. How a second provider fits

easy-db-lab is moving to be cloud-provider agnostic, and this change does not build a second provider.  It keeps the bring-up coordinator free of provider types, so one can be added without touching `NodeBringUp`, `NodePipeline` or the policies.  No class in `services/bringup/` imports `providers.aws` or `services.aws`; the EC2 adapters live in `services/bringup/aws/`.

The coordinator sees only these ports:

- **`InstanceFleet`:** terminate one instance and wait until it is gone, check that one replacement may launch (capacity and permission), and launch one instance under an alias in that alias's zone.  It takes and returns provider-neutral types: a string instance ID and a `ClusterHost`.  EC2: `Ec2InstanceFleet`.
- **`InstanceTracker`:** for each instance, when it runs with an address, when the provider's own health checks pass, and when it ends on its own.  EC2: `Ec2InstanceStateWatcher`, over `DescribeInstances` and `DescribeInstanceStatus`.
- **`NodeSteps`:** the per-node work after the instance is up: SSH, setup, image check, registry TLS, join, and the control node's steps.  The K3s and Cilium specifics are behind it.
- **`ControlPlaneReader`:** the gate's conditions and the health samples.  The K3s and Cilium CRD specifics are behind it.  Fabric8: `Fabric8ControlPlaneReader`.
- **`NodeCleanup`:** removes a replaced node's cluster objects (the K3s Node and its `<alias>.node-password.k3s` secret).

The shared pre-steps (preflight, bucket, discovery, capacity, network, launch permission, the batched launch) stay outside the coordinator, behind the existing provisioning service.

What a second adapter implements:

- **GCP or Azure, with K3s:** `InstanceFleet` and `InstanceTracker` on that provider's compute API, plus its own pre-steps.  `NodeSteps`, `ControlPlaneReader` and `NodeCleanup` are reused unchanged.
- **EKS, or any existing cluster:** no machines are launched, so `InstanceTracker` completes `running` and `statusOk` at once and never ends an instance, and `InstanceFleet` refuses replacement, so a failed node fails `up`.  `NodeSteps` drops the K3s server start and join and waits for the provider's node instead.  `ControlPlaneReader` checks only what that cluster needs, and `NodeCleanup` does nothing.

## Risks / Trade-offs

- [The join limit makes a 1,000-node join take about 16 minutes] → The limit is a constant, and the later 80-node run (after #1157) records the per-node registration time.  Raise it once #1157 lowers write latency.
- [Health thresholds are guesses until measured] → The later 80-node run records every sample (Redis or the debug log).  The thresholds are constants and are tuned from that data.
- [The fabric8 informer through the SOCKS tunnel] → The informer is on the existing per-client proxy.  The tunnel already carries `waitForNodesRegistered`'s lists.  If the watch fails, the informer relists, and a pipeline's Ready timeout catches a watcher that never recovers.
- [MINA SSH from virtual threads may pin carrier threads in `synchronized` blocks] → The SSH permit caps SSH at 50 sessions, so a pinned carrier blocks at most that many.  The forced-failure run is the first check; the later 80-node run is the second.
- [The Cilium CRD list goes stale on a version bump] → It lives next to `Constants.Cilium.VERSION`, and the KDoc says to update both.  A missing CRD makes the gate time out naming it.  A stale list never lets an agent through too early.
- [Replacing db0 changes the seed and the stress host] → The post-step rewrites the workspace files and pushes the two environment files again.  Kits read hosts from `state.json` at start, after `up`.
- [The cluster replacement limit could fail a large `up` that hit one bad zone] → The failure names every node and the reason, the instances are kept, and a re-run continues.
- [A failed node is kept running] → It is recorded in `state.json`, so `down` removes it.  Its cost is one instance until `down` or a re-run.

## Migration Plan

None.  Clusters are ephemeral.  The change ships in one release.  `issue-1048` is archived in this change's PR, before any code, so this change MODIFIES "up works with 1,000 database nodes" directly.

## Owner decisions

The owner approved the spec with these answers to its open questions (issue #1158):

1. **Replacement limit per node:** 2 replacements, 3 instances in all, with a cluster-wide limit of `max(3, ceil(5% of nodes))`.  A bad node must be handled gracefully: shut down and replaced.  At 80 nodes a bad host is likely, so this path must be solid and well tested.
2. **A node out of replacements:** the other nodes and the cluster-wide steps finish, then `up` exits non-zero, names each failed node and its step, and keeps its instance for debugging.
3. **The control node is not replaced:** its steps are retried, then `up` fails at once and keeps every instance.
4. **No 80-node run in this change.**  Verification is one small real cluster with forced bad nodes.  The 80-node run happens after #1157 and #1158 are both merged, and never gates this merge.
