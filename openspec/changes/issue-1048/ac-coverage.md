| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC | Two writers call create-if-absent for one key: exactly one succeeds, the other gets "already exists", on the in-memory and S3 adapters | `account-compactor: Two writers create the same key` | ✅ Covered |
| AC | Replace-if-version-matches with a stale version fails without changing the object | `account-compactor: A stale version cannot replace an object` | ✅ Covered |
| AC | Two clusters share one store: exactly one compactor holds the lease and works, the other stays idle | `account-compactor: Two clusters share one store`; `account-compactor: A standby runs no compactor` | ✅ Covered |
| AC | The holder is stopped or cannot renew: it stops compacting, and a standby acquires the lease after expiry (in-memory store, fake clock) | `account-compactor: A holder that loses the lease stops compacting`; `account-compactor: A dead agent fences its own compactors`; `account-compactor: A standby takes over after expiry` | ✅ Covered |
| AC | Merging removes no object unless its data is in the merged output (existing compactor tests pass) | `account-compactor: Compaction keeps every sample, log line and trace`; `account-compactor: Retention is off in every tool` | ✅ Covered |
| AC | `up` on AWS creates no ECS service, task definition, compactor VPC or compactor IAM role | `account-compactor: up creates no Fargate resources` | ✅ Covered |
| AC | `down` flushes all backends and waits, does not destroy on a failed flush, and does nothing about a Fargate service | `cluster-lifecycle: down does nothing about the compactor`; existing `Cluster Teardown` scenarios | ✅ Covered |
| AC | `CompactorTaskDefinition`, `CompactorTaskRegistrar` and the ECS SDK imports are gone from the repo | — | ⚠️ Excluded — a code-layout fact, not observable behavior; enforced by tasks 7.3 and 7.7, not a spec scenario |
| AC | No memory limiter is added to any OTel or compactor component | `account-compactor: The compactor has no limits` | ✅ Covered |
| Risk | Timing-based fencing: the holder must stop before a standby can take over | `account-compactor: Timing that breaks the safety margin is refused`; `account-compactor: A standby takes over after expiry` | ✅ Covered |
| Risk | Lifting the instance-role deny leaves the cluster's own Tempo able to delete by retention | `observability-store: Tempo in the cluster keeps every block` | ✅ Covered |
| Risk | EMR could delete under the compacted roots | `observability-store: EMR cannot delete compacted roots` | ✅ Covered |
| Risk | The gate does not run in distroless, non-root images, or ignores SIGTERM as PID 1 | `account-compactor: A holder that loses the lease stops compacting`; `account-compactor: A standby runs no compactor` | ✅ Covered |
| Risk | A standby with readiness probes on the Go ports would fail `up` on the second cluster | `account-compactor: A standby runs no compactor` | ✅ Covered |
| Risk | A new agent jar would not roll the pod | `account-compactor: A new agent jar rolls the pod` | ✅ Covered |
| Risk | Redirect clusters get no compactor or no prepared scratch directories | `account-compactor: up deploys the compactor on every cluster` | ✅ Covered |
| Risk | A retried conditional write that landed makes the holder think it lost | `account-compactor: Two writers create the same key` (results are exact, no retries) | ✅ Covered |
| Risk | LocalStack 3.0 has no conditional writes, so the S3 half of the contract has no home | `account-compactor: Two writers create the same key` (S3 adapter clause) | ✅ Covered |
| Risk | `status` reads the bucket from the wrong region | `account-compactor: Status shows the holder` | ✅ Covered |
| Risk | Stale Tempo scheduler cache replays old jobs after a lease returns | — | ⚠️ Excluded — internal behavior with no observable outcome to assert in a spec; handled by the wipe-on-acquire rule in the lease requirement and task 2.5 |
| Risk | A mid-compaction kill leaves partial Mimir blocks | — | ⚠️ Excluded — garbage, not data loss; partial-block deletion stays off by the retention requirement |
| Risk | The live Fargate service keeps running and breaks exclusivity during QA | — | ⚠️ Excluded — a one-time manual step, not system behavior; task 9.2 |
| Risk | Pod-network IMDS access under Cilium is unverified | — | ⚠️ Excluded — an environment fact checked by the real-AWS QA in task 9.3, not a spec behavior |
| Risk | Nothing compacts while no cluster is up | — | ⚠️ Excluded — out of scope per the issue; tracked separately |
