## Context

Verified at b1c5056b. Paths are under `src/main/kotlin/com/rustyrazorblade/easydblab/`.

| Workload | What it needs today | Without it |
|---|---|---|
| fluent-bit-journald | hostPath `/var/log/journal`, `type: Directory` (`configuration/otel/JournaldOtelManifestBuilder.kt:157-161`). It is deployed unconditionally (`services/ObservabilityStackService.kt:196`). | FailedMount, then the rollout timeout. `rancher/k3s:v1.35.1-k3s1` has no `/var/log/journal` and no `/run/systemd` (checked). |
| Pod logs | The collector tails `/var/log/pods/**/*.log` (`otel-collector-config.yaml:124-127`, pipeline `:691`) and mounts `/var/log` as `DirectoryOrCreate` (`OtelManifestBuilder.kt:637-643`). | Nothing breaks. Fluent Bit adds only systemd unit logs. |
| YACE | The node IAM role and CloudWatch (`YaceManifestBuilder.kt:61-66`), plus a static scrape job `localhost:5001` (`otel-collector-config.yaml:78-87`). | It runs with nothing to scrape, and the job reports `up=0`. |
| s3manager | `USE_IAM=true` and the S3 endpoint (`S3ManagerManifestBuilder.kt:50,89-97`). It is advertised in `Status.kt:529-545` and `server/StatusCache.kt:485-498`. | Nothing to browse. It is also amd64-only (#1036). |

The rollout wait covers only what was applied (`ObservabilityStackService.kt:166,246-255`), so a skipped stage needs no special handling there.

## Decisions

**D1. Declared per environment.** Owner, 2026-11-08.
- An environment declares what its nodes provide. AWS nodes run systemd and have a journal; the local Docker nodes do not.
- Rejected:
  - A node label plus affinity: it only helps with mixed nodes, which nothing has.
  - A `DirectoryOrCreate` hostPath: it would deploy a reader with nothing to read and hide a broken AMI.

**D2. S3 is its own capability.** Owner, 2026-11-08.
- Rejected: deriving it from #1035's `ObservabilityStorage`. That would tie this issue to #1035's merge order. Switching later is a one-line change.

**D3. The type and the decision.**
```kotlin
enum class NodeCapability(val absentReason: String) {
    SYSTEMD_JOURNAL("this environment's nodes have no systemd journal; pod logs still reach Loki through the collector"),
    CLOUDWATCH("this environment has no CloudWatch"),
    S3("this environment has no S3 bucket"),
}
@JvmInline value class NodeCapabilities(private val provided: Set<NodeCapability>) {
    operator fun contains(c: NodeCapability) = c in provided
    companion object {
        val AWS = NodeCapabilities(NodeCapability.entries.toSet())
        val LOCAL_DOCKER = NodeCapabilities(emptySet())
        fun from(state: ClusterState): NodeCapabilities = AWS   // #1040: returns the set the recorded execution environment declares
    }
}
```
- It lives in `configuration/`.
- `AWS` and `LOCAL_DOCKER` are the sets those environments declare. Once #1040 lands, `from` reads the recorded environment and returns its declared set. Consumers never branch on an environment name.
- The absent-reasons are facts about the environment, not about local Docker. A bring-your-own environment reuses them unchanged.
- The enum is not final. Further capabilities (see the proposal's Out of scope) are added when a second implementation needs them.
- It is a pure function of `ClusterState`, the same pattern as the CNI (`CollectorResources.kt:35-41`). There is no Koin binding and no new constructor parameter, because every consumer already loads `ClusterState`.

**D4. Gating lives in the stage list, not the builders.**
- The private `Stage` in `ObservabilityStackService.buildStages` (`:193-212`) gains `requires: NodeCapability?`.
- The builders stay unaware of capabilities (single responsibility).

**D5. One event per skipped stage.**
- `Event.Grafana.StageSkipped(stage: String, capability: NodeCapability, reason: String)`, in the domain that already reports observability deploy steps.
- An MCP or Redis subscriber has a reason to know a workload is absent.
- The capability enum is `@Serializable`, with a `@SerialName` per value.

**D6. The YACE scrape job.**
- Removed from `otel-collector-config.yaml`.
- `OtelManifestBuilder` renders it from a function gated on `CLOUDWATCH`, beside `buildCniScrapeJobs(cni)` (`:294`).
- `CollectorResources.build` reads the capabilities from state, so `ObservabilityStackService` and `OtelSyncService` (`:49`) agree.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| AWS loses a workload or the `yace` job through a gating mistake | A unit test: with `AWS`, the set of applied workload names equals today's, and the rendered collector config still has `yace`. |
| The kit-sync path renders a different collector config from deploy | Both go through `CollectorResources.build`. A test renders both and compares them. |
| A skipped stage is still awaited, and the rollout times out | A unit test with `LOCAL_DOCKER` asserts that nothing is awaited for the skipped stages. |
| Pod logs depend on the journald reader | An integration test on a K3s TestContainer (journal-less): a pod's stdout line reaches Loki. |
| `from(state)` is AWS everywhere until #1040 | Intended: no local environment exists until then. Tests use `LOCAL_DOCKER` directly. |
