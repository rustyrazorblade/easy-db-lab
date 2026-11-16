## Why

`up` moves every node through each phase together: launch, status checks, SSH, setup, registry TLS, agent join.  Each phase waits for its slowest node, and one bad node fails the whole `up`.  At 79 nodes (#1134), 50 agents registered at once while the Cilium operator was still creating its CRDs.  `up` then hung for 10 minutes in 50 blocked `systemctl start k3s-agent` calls.  It printed nothing, did not detect the stall and did not recover.  A manual `systemctl restart k3s` on the control node was needed to finish.

## What Changes

- Each node runs its own pipeline on its own virtual thread: running, status checks, SSH, setup, image check, registry TLS, join, registered, Ready.  A node goes on as soon as its own step is done.  It does not wait for the other nodes.
- Shared steps keep their order.  The VPC, the capacity checks and the batched first launch run before any pipeline.  The control node's pipeline then generates the registry certificate, starts Tailscale or the SOCKS tunnel, starts the K3s server and installs the CNI.  Then it opens the control-plane gate.  The storage classes, the observability stack, the workspace files that name other hosts, and AxonOps run once every pipeline has finished.
- **Control-plane gate:** no agent joins until the API server is ready.  Under Cilium the gate also needs the Cilium operator Ready and its CRDs established.
- **Join limit:** at most `Constants.Scale.JOIN_CONCURRENCY` (16) agents register at once.  The agent is started without blocking on systemd, and its registration is read from the Kubernetes API, so no SSH session hangs on a stalled join.
- **Retry then replace:** a step that fails is retried under its own policy.  When the retries run out, the node is replaced.  `up` terminates its instance, removes the old node from K3s, and launches a new instance with the same alias in the same subnet.  It updates `state.json` and starts the pipeline again.  The other nodes do not wait.  Each replacement first runs the capacity checks and a RunInstances dry run for one instance.
- **Replacement limits:** at most `Constants.Scale.MAX_NODE_REPLACEMENTS` (2) per node, so 3 instances in all, and a cluster-wide limit of the larger of 3 and 5% of the nodes, so a fault that every node has (a bad AMI, a wrong instance type) fails `up` instead of replacing the whole cluster.
- **Control-plane health check:** while K3s runs, `up` samples the latency of a `get nodes` call and the API server's rejected (429) request count.  While the control plane is unhealthy, `up` issues no join permits and makes no replacements.  A join step that times out then is not counted against its node.  `up` reports the unhealthy state, and it reports again when the control plane recovers.  If it stays unhealthy past a deadline, `up` fails and names what it saw.
- **Events and progress:** typed events for each node's steps, step failures, replacements and readiness, for the gate, and for the health state.  `up` also prints a periodic progress line with each step's node count and the elapsed time.
- **Changed failure behaviour:** a node failure no longer aborts `up` at once.  `up` fails, exiting non-zero and naming the nodes, when a node uses up its replacements, when the cluster-wide limit is reached, when the control node fails after its K3s server started, or when a shared step fails.  For a failed db or app node it does this after the other pipelines and the shared post-steps finish, and keeps the failed node's instance for debugging.  A control node failure fails `up` at once.

## Capabilities

### New Capabilities

None.  The behaviour belongs to `cluster-lifecycle`.

### Modified Capabilities

- `cluster-lifecycle`: `up` brings nodes up in per-node pipelines, with retry and replacement, a control-plane gate, a join limit and a control-plane health check.  The requirement "Provisioning reports success only when it fully succeeded" changes: a node step failure is retried and replaced before it fails `up`.
- `event-system`: an event type can stay off the console and still reach Redis, for the high-volume per-node step events.

## Impact

- Code: `commands/Up.kt` (becomes a sequence of shared steps around a bring-up coordinator), `services/K3sClusterService.kt` (server and agent steps are split so the pipeline can call them), `commands/SetupInstance.kt` (per-node setup becomes a service that `up` and `setup-instances` share), `services/aws/EC2InstanceService.kt` and `LaunchCapacityPreflight.kt` (launch and check one instance), `start-k3s-agent.sh` (non-blocking start), and new bring-up classes under `services/bringup/`.  `Constants.Scale` gets the new limits and timeouts.  `events/Event.kt` gets the new events.
- Behaviour: `up` output changes, with per-node lines and a progress line.  A small cluster gets the same result as today.
- Specs: `cluster-lifecycle`, `event-system`.  This overlaps the in-flight change `issue-1048` (merged, not archived); see `overrides.md`.
- Docs: `docs/user-guide/large-clusters.md`, `commands/CLAUDE.md`, `events/CLAUDE.md`, `services/CLAUDE.md` (bring-up package).
- Related: #1134 (findings), #1157 (embedded etcd; separate change, no overlap), #1072.
