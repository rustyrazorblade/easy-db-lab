## Why

Part of the multi-cloud epic (#1062) and Phase A of the Kubernetes-forward architecture (#1032, `docs/plans/2026-11-11-kubernetes-forward-architecture.md`).  The "neutral" `ObjectStore` interface is S3-shaped, so no second store can plug in:

- Every `ObjectStore` method takes `ClusterS3Path`, which renders `s3://` (`configuration/ClusterS3Path.kt:171-185`), parses it (`:100-108`) and builds AWS endpoint URLs (`:194-201`).
- `S3ObjectStore` is the only adapter, so 9 test files mock `ObjectStore` only to stub it.
- Loki, Mimir, Tempo, Pyroscope and the compactor's Tempo config pin `backend: s3` and the literal endpoint `s3.${AWS_REGION}.amazonaws.com` (`mimir.yaml:36-43`, `loki.yaml:31-34`, `tempo.yaml:78-81`, `pyroscope/config.yaml:13-15`, `services/aws/tempo-backend.yaml:49-52`).
- The `cluster-config` ConfigMap publishes `aws_region` and `s3_bucket` (`services/ClusterConfigData.kt:35-36`).

#1035 (per-environment observability storage) is re-specced on top of this change and plugs a volume store into the contract defined here.  #1091 (backends as Services) builds on #1035.

## What Changes

- **Neutral path.**  `ClusterS3Path` is renamed `ClusterStoragePath`: a bucket, a store prefix and key segments, with no URI scheme.  Its `toString()` no longer renders `s3://`.  `toUri`, `fromUri` and `toEndpointUrl` move to a new `services/aws/S3Uri`.
- **Display URIs through the adapter.**  `ObjectStore` gains `uriOf(path)`.  On AWS it still returns `s3://bucket/key`, so every event and message that shows a location reads exactly as today.
- **Store prefix.**  One store prefix sits in front of every backend root (`mimir/`, `loki/`, `tempo/`, `pyroscope/`), the `grafana/` and `reports/` roots and the per-cluster `clusters/` tree.  It is empty by default, so on AWS every key is unchanged.  A shared bucket the user supplies can set it later.
- **Store backend model.**  A new neutral sealed type `StoreBackend` in `configuration/store/` has one case, `S3Backend(bucket, prefix, region, endpoint)`.  Its KDoc names it as the place #1035 adds the volume case and the credential source.  #1061 adds no credential source.
- **Per-target backend block.**  Each backend config takes its object-store block from a per-backend fragment resource behind a placeholder, chosen in Kotlin from the `StoreBackend` case.  The S3 fragments hold today's exact lines, so the S3 endpoint text exists only in the S3 fragments.
- **Neutral `cluster-config` keys.**  `aws_region` and `s3_bucket` become `store_region` and `store_bucket`, and `store_prefix` and `store_endpoint` are added.  The `*_s3_prefix` keys become `traces_store_prefix`, `metrics_store_prefix` and `logs_store_prefix`.  Every builder and every `${...}` name changes with them.
- **Prefix-aware delete denies.**  The instance-role deny and the bucket-policy deny keep today's roots and add the same roots under any prefix, so the deny is the same whichever prefix the caller passes and no `up` can drop another cluster's guard.  The compactor task role's allow gains the prefixed compacted roots of its own prefix only, and with the empty prefix is byte-identical to today's.
- **In-memory adapter.**  `services/InMemoryObjectStore` implements `ObjectStore` over a map.  Unit tests that only stub storage use it; tests that simulate failures keep a mock.  #1048 later adds its conditional write to this class.
- Docs and the `observability-store` and `account-compactor` specs stop naming S3 where they mean "the store".  The `cluster-lifecycle` and `observability` specs name S3 only for AWS-only behavior (the per-cluster data bucket, CloudWatch request metrics, the state backup, and data reaching the account bucket during the `down` flush), which this change keeps; they are left as they are.

## Capabilities

### New Capabilities
- `object-store`: the neutral storage path, the `ObjectStore` contract and its in-memory adapter, the store backend model and the per-target backend block.

### Modified Capabilities
- `observability-store`: data lands under the store prefix; `cluster-config` publishes `store_*` keys; the cluster's delete deny is built from the store prefix.
- `account-compactor`: the task role's allow and the bucket-policy deny are built from the store prefix; the registered task definition is unchanged on AWS.

## Out of scope

- The filesystem / PVC store and in-process compaction (#1035).
- Store credentials (Secret, IRSA, EKS Pod Identity) and the two store identities of decision K5 (#1035, owner decision 2026-11-13).
- The compactor lease and conditional write (#1048; the lease must sit under the store prefix, recorded on #1048).
- GCS and Azure Blob adapters (#1054, #40), deferred by decision K4.
- Kit and node access to the store (#1055).
- Reshaping `ClusterState.InfrastructureState`.
- Setting a non-empty store prefix from the CLI.  This change makes the prefix a field with an empty default; choosing it per environment belongs to #1035 and #1082.

## Impact

- Main: `configuration/ClusterS3Path.kt` (renamed) and its 15 users, `services/ObjectStore.kt`, `services/aws/S3ObjectStore.kt`, new `services/aws/S3Uri.kt`, new `services/InMemoryObjectStore.kt`, new `configuration/store/`, `services/ClusterConfigData.kt`, `configuration/ObservabilityStore.kt`, the Loki, Mimir, Tempo and Pyroscope manifest builders and YAML, `services/aws/CompactorTaskDefinition.kt` and `tempo-backend.yaml`, `providers/aws/AWSPolicy.kt`, `commands/spark/SparkSubmit.kt`.
- Tests: 19 test files reference `ClusterS3Path`; 9 mock `ObjectStore`.
- Every backend pod rolls once on the first `up` after the change, because the `cluster-config` keys change (`ConfigHashAnnotator`).  That is expected.
- Docs: `docs/` pages and the `configuration/`, `services/` and `services/aws/` `CLAUDE.md` files that name `aws_region`, `s3_bucket` or the S3 blocks.
