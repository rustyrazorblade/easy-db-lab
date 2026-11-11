## Why

On AWS, the account compactor runs as one ECS Fargate service per account, and `down` stops it when the last cluster goes away.  That design needs a serverless runner on every cloud, and the runners on other clouds have hard limits (run-time caps, short run history, maintenance interrupts).  The owner decided that the compactor runs in the cluster on every target, AWS included.  Every cluster runs a standby compactor, and a lease object in the object store makes exactly one of them active per store.

Compaction is allowed because it is not deletion: a compactor writes a merged object that holds all of its sources' data, and then removes the sources.  Retention stays off everywhere.

## What Changes

- **Conditional writes in `ObjectStore`** (`services/ObjectStore.kt`): add `readVersioned`, `createIfAbsent` and `replaceIfVersionMatches`, with a version token and sealed results.  `S3ObjectStore` implements them with conditional PUT (`If-None-Match: *`, `If-Match: <ETag>`), without retries.  A new in-memory adapter implements the whole interface.  One contract test suite runs against both adapters.
- **New Gradle subproject `:compactor-lease`**: the conditional-write port, the S3 and in-memory conditional adapters (the in-memory one in test fixtures), the `LeaseLoop` state machine with an injected monotonic clock, the `LeaseRecord` format (kotlinx.serialization), the gate-file writer, and `LeaseAgentMain`.  It is shadowed into `agents/compactor-lease.jar` in the distribution and in the Jib image.  The root module's `ObjectStore` adapters delegate their conditional methods to it.
- **New `account-compactor` Deployment** on the control node of every cluster, built by `configuration/compactor/CompactorManifestBuilder` with fabric8, and deployed as one stage of `ObservabilityStackService`.  It runs a `lease` container (stock JRE image, the agent jar) and the Mimir, Loki, Tempo backend-scheduler and Tempo backend-worker compactors.  Each compactor is wrapped by a busybox gate script (`compactor-gate.sh`, a packaged resource in the ConfigMap) that runs the binary only while the lease is valid.  A new `CompactorAgentInstaller` prepares the scratch host paths and uploads the jar to a SHA-256 path.
- **Lease** `leases/compactor.json` in the account bucket.  Timing values go in `Constants.Compactor.Lease`.
- **IAM**: the instance role's delete deny no longer covers `mimir/`, `loki/` and `tempo/`; it keeps `grafana/` and `reports/`.  The bucket policy keeps the delete deny for the EMR roles only.  The cluster `tempo.yaml` gets an explicit `block_retention: 876000h` as a second guard.
- **CLI**: `observability compactor start` and `stop` are removed.  `observability compactor status` prints the lease.
- **Removed (the Fargate path)**: `services/aws/CompactorService.kt`, `CompactorStatusReader.kt`, `CompactorTaskRegistrar.kt`, `CompactorIam.kt`, `CompactorNetwork.kt`, `ClusterCensus.kt`, `configuration/compactor/CompactorTaskDefinition.kt`, `providers/aws/RegionalClients.kt`, the ECS retry helpers, the compactor IAM policy and roles in `AWSPolicy.kt` and `iam-policy-compactor.json`, `InfrastructureConfig.forCompactor`, `TeardownTypes.isCompactorVpc`, the ECS members of `Constants.Compactor`, every `Event.Compactor.*` event and `Event.Infra.CompactorVpcSkipping`, `down`'s `stopCompactorIfLastCluster`, the compactor call in `AccountBucketSetup`, and the `awssdk-ecs` and `awssdk-cloudwatchlogs` dependencies.
- **`down`** keeps only its flush contract: flush every backend, wait, and refuse to tear down on a failed flush.
- **Larger subnets and a fallback IP lookup**: `network/CidrBlock` splits the VPC into eight equal subnets of the VPC prefix + 3 (eight `/19`s for the default `/16`), one per availability zone, so a cluster can hold 1,000+ nodes with Cilium ENI IPAM; at most eight zones, and the VPC prefix must be `/25` or larger.  `Constants.Vpc.subnetCidr` is removed and Packer uses `CidrBlock.DEFAULT.subnetCidr(0)`.  `DefaultExternalIpService` asks `checkip.amazonaws.com` first, trims the answer, and falls back to `api.ipify.org` when it fails or is not an IPv4 address.
- **1,000 nodes and pre-launch capacity checks**: `up` launches one RunInstances call per subnet (chunks of 100, client token, throttle retry), records each instance as it launches, reads every discovery page once, waits for every instance ID, and runs per-host work on at most 50 hosts at once.  K3s agents carry their labels in their config and `up` waits for every node to register.  K3s gets a `10.224.0.0/12` pod range and a `10.240.0.0/16` service range, which no VPC block may overlap.  Before its first launch `up` checks, for the new instances only, the zone count, the zone offerings, the vCPU quota, the network interface quota and the free subnet addresses, and dry-runs each launch.  `down --dry-run` changes nothing.
- **Docs and agent docs**: `docs/user-guide/compactor.md` (rewrite) and the related user-guide and reference pages, `bin/set-policies`, the package CLAUDE.md files, and the `observability-stack` skill.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `account-compactor`: the compactor runs in every cluster behind an object-store lease, with conditional writes on `ObjectStore`; the Fargate service, its roles, its network and its start and stop commands are removed.
- `observability-store`: the cluster delete deny no longer covers the compacted roots; Tempo on a cluster gets an explicit long block retention.
- `cluster-lifecycle`: `up` no longer starts a compactor service, `down` no longer stops one, and `down --all` no longer keeps a compactor VPC.
- `networking`: the account-compactor security group path is removed; `init --cidr` requires `/25` or larger; public IP detection asks checkip.amazonaws.com first and falls back to api.ipify.org.
- `vpc-cidr-allocation`: the VPC is split into eight equal subnets of the VPC prefix + 3, one per availability zone, with at most eight zones.

## Impact

- Every `up` and `grafana update-config` deploys one more stage and uploads the agent jar (about 20 MB) once per version.
- The user IAM policy set goes from five policies to four (`bin/set-policies`).
- The live Fargate service must be stopped with the previous build before the first QA run, because the new build has no command to stop it.
- `down --all` removes the old `easy-db-lab-compactor` VPC.
- With no cluster up, nothing compacts; this gap stays open and is tracked separately.
