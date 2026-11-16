## 1. Constants and events

- [ ] 1.1 `Constants.Scale`: add `JOIN_CONCURRENCY` (16), `MAX_NODE_REPLACEMENTS` (owner decision, recommended 2), `MIN_CLUSTER_REPLACEMENTS` (3), `CLUSTER_REPLACEMENT_FRACTION` (0.05), `GATE_TIMEOUT_SECONDS` (600), `GATE_POLL_INTERVAL_SECONDS` (5), `NODE_SETUP_TIMEOUT_SECONDS` (900), `NODE_REGISTRATION_TIMEOUT_SECONDS` (300), `NODE_READY_TIMEOUT_SECONDS` (600), `HEALTH_SAMPLE_INTERVAL_SECONDS` (5), `HEALTH_LATENCY_LIMIT_MS` (2000), `HEALTH_REJECTION_LIMIT` (25), `HEALTH_RECOVERY_SAMPLES` (3), `HEALTH_UNHEALTHY_TIMEOUT_SECONDS` (900), `PROGRESS_INTERVAL_SECONDS` (15), each with KDoc saying why
- [ ] 1.2 `Constants.Cilium.REQUIRED_CRDS`: the CRDs the Cilium agent waits for at `Constants.Cilium.VERSION`, read from that version's source or chart; the KDoc says a version bump must update it
- [ ] 1.3 `Event`: add `fun showOnConsole(): Boolean = true`; `ConsoleEventListener` skips an event that returns false unless `isError()`; unit test both cases (event-system delta)
- [ ] 1.4 `events/Event.kt`: add `Provision.NodeStepStarted`, `NodeStepCompleted` (both `showOnConsole = false`), `NodeStepFailed` (error), `NodeReplacing`, `NodeReplaced`, `NodeReady`, `NodesFailed` (error, list of `FailedNode`), `ControlPlaneUnhealthy` (error), `ControlPlaneHealthy`, `BringUpProgress`; `K3s.ControlPlaneGateOpen`, `K3s.ControlPlaneGateTimedOut` (error); `Cilium.OperatorReady`.  Each one has structured fields and a `toDisplayString()`.  Add a unit test for the progress line's text from a sample `byStep` map
- [ ] 1.5 `events/CLAUDE.md`: list the new events and `showOnConsole`

## 2. Per-node building blocks (unit tier, test first)

- [ ] 2.1 `services/bringup/NodeStep` and `StepPolicy`: name, attempts, per-attempt timeout, replaceable flag; built on `RetryUtil` with the checked decorators; never retries `InterruptedException`
- [ ] 2.2 `JoinLimiter`: fair semaphore of `JOIN_CONCURRENCY`, which also refuses a permit while `ControlPlaneHealth` is unhealthy; tests: never more than the limit in flight with 40 callers; no permit while unhealthy; a permit is given when health returns
- [ ] 2.3 `SshSessionLimiter`: a semaphore of `HOST_CONCURRENCY` that every SSH step takes for the length of its remote call; test the cap
- [ ] 2.4 `ControlPlaneHealth` state machine (pure): samples in, healthy/unhealthy out, recovery after `HEALTH_RECOVERY_SAMPLES`, unhealthy deadline; tests at each threshold boundary and for an unreachable sample
- [ ] 2.5 `ReplacementBudget`: per-node and cluster-wide limits; tests for both limits and the `max(MIN, ceil(n × fraction))` rule at 3, 20, 80 and 1,000 nodes
- [ ] 2.6 `BringUpProgress`: counts nodes per step from pipeline state changes and emits `BringUpProgress` every interval; test the counts after a scripted sequence

## 3. Shared watchers

- [ ] 3.1 `InstanceStateWatcher`: one DescribeInstances and one DescribeInstanceStatus per poll for every instance it watches, at most 100 IDs a request, through `EC2InstanceService`; completes per-instance futures for running with a public IP and for status OK; fails them when an instance ends; keeps watching instances past those steps.  Unit test with a fake EC2 layer: batching, ending in any step, and a dropped instance
- [ ] 3.2 `NodeWatcher`: one fabric8 informer on Node objects through `K8sClientProvider` (the existing per-client proxy; no JVM proxy properties); per-alias registered and Ready futures.  Integration test on K3s TestContainers: a node that registers completes registered, Ready completes on its condition
- [ ] 3.3 `ControlPlaneSampler`: `get nodes` with `limit=1` latency, and the sum of `apiserver_flowcontrol_rejected_requests_total` from `/metrics`.  Integration test on K3s TestContainers reads both; unit test for the metric text parse

## 4. Control-plane gate

- [ ] 4.1 `ControlPlaneGate`: polls API server readiness, control node registered, and under Cilium the operator Deployment's ready replicas and every `REQUIRED_CRDS` CRD `Established`; opens once; times out naming the false conditions; emits `Cilium.OperatorReady` and `K3s.ControlPlaneGateOpen`.  Unit tests with a fake reader: opens only when all hold, Flannel needs no Cilium conditions, timeout names the false ones
- [ ] 4.2 Integration test on K3s TestContainers: a CRD that exists but is not yet `Established` does not count; once established it does

## 5. Node steps as services

- [ ] 5.1 Move `SetupInstance`'s per-node work into `NodeSetupService` (one method per server type); app nodes read their own region from IMDS; `setup-instances` calls the service through `HostOperationsService.withHosts` with the same behaviour; existing `SetupInstance` tests pass
- [ ] 5.2 `K3sClusterService`: split into `startServer(control)` (server, token, kubeconfig, backup) and `joinAgent(host, labels)` (configure and a non-blocking start); `setupCluster` is removed once `up` no longer calls it; update `K3sClusterServiceTest`
- [ ] 5.3 `start-k3s-agent.sh`: `systemctl start --no-block k3s-agent`; a retry of the join step runs `systemctl restart --no-block k3s-agent`; update `K3sAgentServiceTest`
- [ ] 5.4 `NodeCleanup` (K8s side of replacement): delete the Node and `<alias>.node-password.k3s` in `kube-system`, tolerating either already gone; integration test on K3s TestContainers
- [ ] 5.5 `EC2InstanceService.launchOne(spec, alias, ordinal)`: one instance in subnet `ordinal % subnets`, ordinal tags `first = ordinal`, `stride = 1`, using the batched path's request builder; `terminateAndWait(instanceId)` until the instance is neither pending nor running.  Unit tests on the request built (subnet, tags, count)
- [ ] 5.6 `LaunchCapacityPreflight`: a one-instance `CapacityPlan` and `verifyLaunchPermitted` path for a replacement; test that the plan counts exactly one instance in the alias's zone

## 6. Replacement

- [ ] 6.1 `NodeReplacer`: under one lock, wait for healthy, terminate and wait, K8s cleanup when registered, capacity and dry run for one instance, launch, atomic state write by alias, emit `NodeReplacing` and `NodeReplaced`.  Unit tests with `FakeInstanceFleet` and a recording state manager: terminate before launch; same alias and subnet; one host per alias in the saved state, in ordinal order; cleanup only when registered; a capacity refusal or a refused dry run fails the node with no launch
- [ ] 6.2 State write: the replacement and the batched launch's `onHostsCreated` go through one writer under `Up`'s state lock; test that concurrent writes from many pipelines keep every host

## 7. Pipeline and coordinator

- [ ] 7.1 `NodePipeline`: the step order per server type (D2), latches for the certificate and the gate, the join permit held to registration, and an end-state of Ready or Failed(step, reason).  Steps after an `InterruptedException` stop
- [ ] 7.2 `NodeBringUp`: starts one virtual thread per node (`newVirtualThreadPerTaskExecutor`), pushes the command name onto `EventContext` in each, starts pipelines as launch batches return, runs the control pipeline, starts the health monitor, interrupts every pipeline when the control pipeline fails, and returns every node's end-state
- [ ] 7.3 Unit tests with `FakeNodeSteps`, `FakeInstanceFleet` and `FakeControlPlane` (zero intervals): a slow node does not delay another's Ready; retry with no replacement; replacement after the attempts run out; replacement with no retry for an instance that ended; per-node limit reached, the others finish, and the result names the node and step with its instance kept; cluster limit stops replacements; image check failure fails with no replacement; no join before the gate; join limit holds at 40 nodes; permit released at registration; unhealthy blocks permits and replacements and a join timeout then is not counted; control failure interrupts all; `--no-setup` ends after SSH; `--hosts` runs only the named pipelines
- [ ] 7.4 `Up`: replace steps 4-9 of today's flow with the shared pre-steps, `NodeBringUp.run`, and the shared post-steps (final workspace files, re-push of `environment.sh` and `stress.env` when db0 was replaced, storage classes, observability, Cilium annotations, AxonOps).  Emit `NodesFailed` and throw `CommandFailedException` when any node failed.  Update `Up` tests for the success path, a failed node, and a failed shared step
- [ ] 7.5 Remove code `up` no longer uses (`SshReadinessWait` if unused elsewhere, `labelControlAndAwaitNodes`, the per-phase `BoundedParallel` calls in `up`); keep `BoundedParallel` for its other callers
- [ ] 7.6 Run `./gradlew test`, `./gradlew integrationTest` and `detekt` on JDK 21 in a subagent; fix every finding in code, with no suppression, threshold change or baseline entry

## 8. Specs

- [ ] 8.1 Check the change with `openspec validate issue-1158`

## 9. Docs

- [ ] 9.1 `docs/user-guide/large-clusters.md`: per-node pipelines, the control-plane gate, the join limit, retry and replacement with both limits, the health check and what its report means, the progress line, and how `up` ends when a node fails; remove the "runs on at most 50 nodes at once" and "a re-run of `up` launches a replacement" text
- [ ] 9.2 `src/main/kotlin/.../commands/CLAUDE.md`: the `HostOperationsService` section says `up` no longer fans out per phase; `services/CLAUDE.md` gets a short section on `services/bringup/`
- [ ] 9.3 Root `CLAUDE.md` and `configuration/CLAUDE.md`: update any line that describes `up`'s phase order

## 10. Real AWS verification (`requires-aws`)

- [ ] 10.1 Small-cluster regression: 1 control, 3 db, 1 app, default profile, with the off-hours tag WORKFLOW.md names.  `up` exits zero with every node Ready; `status`, a kit install and start, and `down` work.  Record the `up` output and wall time in the PR
- [ ] 10.2 Forced failure: 1 control, 6 db.  While db2 is in setup, terminate its instance from the AWS CLI.  `up` reports `NodeReplacing` and `NodeReplaced` for db2, finishes with exit zero, `state.json` holds db2 once with the new instance ID, the old instance is terminated, `kubectl get nodes` shows db2 Ready once, and no `node-password` rejection appears in the K3s journal.  Record the commands, the output and `aws ec2 describe-instances` for both IDs
- [ ] 10.3 Forced join failure: same cluster shape.  Make db4's agent fail to register (for example a wrong token written into its agent config before its join step).  `up` retries, then replaces db4, and the replacement joins under the same name
- [ ] 10.4 About 80 nodes: 1 control, 76 db `i4i.xlarge`, 2 app `c6id.4xlarge`, default profile, with the off-hours tag WORKFLOW.md names.  `up` finishes with no manual step.  Record: the `up` wall time and each progress line; the most joins in flight; the time from gate open to the last Ready; every health sample (latency and rejection growth); any `ControlPlaneUnhealthy` and how long it lasted; replacements; cilium-operator restarts (`kubectl -n kube-system get pods`); and which K3s datastore was in use (#1157).  Compare with the #1134 numbers.  Tear down with `down` and record that it found every instance, including any replaced one's successor
- [ ] 10.5 Post the evidence in the PR with no account numbers, bucket names or company names

## 11. Archive

- [ ] 11.1 After `issue-1048` is archived, fold the overrides in `overrides.md` into "up works with 1,000 database nodes" when archiving this change
