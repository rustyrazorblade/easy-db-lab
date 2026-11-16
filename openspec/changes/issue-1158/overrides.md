## Overrides existing behavior

### cluster-lifecycle: Provisioning reports success only when it fully succeeded
**Currently:** "When any provisioning step fails, `up` MUST abort at the point of failure and exit non-zero."  Scenario "A failing provisioning step aborts `up`" lists node setup and K3s cluster setup among the steps that abort at once.
**This change:** The rule splits into shared steps and node steps.  A shared step still aborts at once.  A node step is retried, then the node is replaced.  The other nodes are not interrupted.  A node that cannot be brought up fails `up` (non-zero, naming it) once the others finish.  `up` still never reports success unless every node is Ready.

### cluster-lifecycle (in `issue-1048`, not yet archived): up works with 1,000 database nodes
**Currently:** "An instance it finds shutting-down, terminated, stopping or stopped MUST end the wait at the poll that sees it, naming each such instance ... and saying that a re-run of `up` replaces it."  Scenario "An instance EC2 ends during the running wait fails up at once".
**This change:** Such an instance is replaced at once by `up` itself, within the replacement limits.  The wait still ends at the poll that sees it, and no deadline is waited out.  `up` fails only if the node cannot be replaced.

**Currently:** "Per-host work (instance setup, AxonOps, registry TLS, K3s agents, the SSH readiness wait, the Cilium node image check) MUST run in parallel on at most `Constants.Scale.HOST_CONCURRENCY` hosts at once."
**This change:** Each node's steps run in that node's own pipeline.  The cap of `HOST_CONCURRENCY` stays, as a limit on open SSH sessions across all pipelines.  K3s agent joins have their own lower limit, `JOIN_CONCURRENCY`.  AxonOps stays a shared post-step.

**Currently:** "After the K3s agents start, `up` MUST wait until every node is registered."  Scenario "Every node registers before the storage classes are made".
**This change:** Unchanged in effect.  Each pipeline waits for its own node's registration and Ready, and the storage classes run after every pipeline has ended.  The whole-cluster wait (`waitForNodesRegistered`) is no longer what `up` uses.

### docs: large-clusters.md
**Currently:** "Per-node work (setup, the SSH readiness wait, K3s agents, registry TLS) runs on at most 50 nodes at once."  "The wait for instances to run fails as soon as EC2 reports one `shutting-down` ... A re-run of `up` launches a replacement under the same name."
**This change:** Rewritten for per-node pipelines, the join limit, the gate, replacement and the health check (tasks section 9).

## Conflicts with other in-flight changes

- `issue-1048` is merged (every task checked) but not archived.  Its ADDED requirement "up works with 1,000 database nodes" is not yet in `openspec/specs/cluster-lifecycle/spec.md`.  This change adds new requirements next to it and does not MODIFY it, because a MODIFIED block needs the requirement to exist in the main spec.  Archive `issue-1048` first.  Then, when this change is archived, fold the three overridden bullets above into that requirement (tasks 11.1).
- `issue-1157` (embedded etcd) is specced in parallel and changes only the K3s server's datastore flags and where etcd's data lives.  This change does not touch the server's start flags or the datastore.  The gate reads the API server's readiness, which works on either datastore.  The two can land in either order.  The 80-node run in tasks section 10 runs on whichever is on `main` at the time and records which.
- `issue-1064` and `issue-969` touch neither `cluster-lifecycle` nor `event-system`.
