## Overrides existing behavior

- `cluster-config` no longer publishes `aws_region`, `s3_bucket`, `traces_s3_prefix`, `metrics_s3_prefix` or `logs_s3_prefix`. It publishes the `store_*` keys instead. No kit, script or dashboard reads the old keys.
- `ClusterS3Path.toString()` rendered `s3://bucket/key`. `ClusterStoragePath.toString()` renders `bucket/key`. Every location shown to the user goes through `ObjectStore.uriOf`, which still renders `s3://bucket/key` on AWS.
- Every backend pod rolls once on the first `up` after the change, because its configuration and `cluster-config` change.
- Otherwise the change only adds requirements. With the empty store prefix, every rendered backend config, the compactor task definition and the compactor task-role policy are byte-identical to today's.
- The instance-role and bucket-policy delete denies also cover the same roots under any prefix (`<bucket>/*/<root>/*`), so a deny no longer depends on which prefix the last caller passed.

## Conflicts with other in-flight changes

- #1048 (compactor agent as an image, in-memory adapter with conditional write; not on this branch): #1048 extends `InMemoryObjectStore` from this change with its conditional write, and puts the compactor lease under the store prefix. If #1048 merges first with its own adapter, keep one class.
- PR #1071 (in-cluster compactor) narrows the instance role's deny under `mimir/`, `loki/` and `tempo/`. Whichever lands second rebases the deny onto `underAnyPrefix` of design D6; the deny stays independent of the prefix either way.
- #1035 (per-environment observability storage): re-specced on top of this change. It adds the volume `StoreBackend` case, the credential source and the two store identities.
- #1091 (backends as Services): touches the same manifest builders. Textual conflicts in the builders are possible; no behavioural conflict.
