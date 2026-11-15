| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | Code outside `services/aws` uses `ClusterStoragePath`; no class outside the S3 adapter renders `s3://` | `object-store: No scheme outside the S3 adapter`; `S3 locations still read as s3 URIs`; `A Spark jar given as an s3 URI still resolves` | ✅ |
| AC2 | A unit test that needs an object store uses the in-memory adapter | `object-store: Stub-only tests use the adapter`; `Content round-trips`; `Listing follows key order and startAfter`; `A non-recursive listing returns only direct children`; `Reading a missing object fails` | ✅ |
| AC3 | On AWS the rendered Loki, Mimir, Tempo, Pyroscope and compactor config is identical to today's | `object-store: The AWS configuration is unchanged`; `The compactor task definition is unchanged` | ✅ |
| AC4 | A different object-store block renders with no S3 endpoint (stub-block test) | `object-store: A different block renders with no S3 in it`; `The endpoint lives only in the S3 fragments` | ✅ |
| AC5 | `check` on JDK 21 passes with no new `@Suppress`, threshold or baseline change | `object-store: The model stays outside the SDK boundary` (ArchUnit part) | ⚠️ Excluded as a scenario: a build gate, enforced by task 6.4 |
| Amendment | Store prefix in front of every backend root, empty on AWS | `object-store: An empty prefix leaves every key unchanged`; `A store prefix leads every key`; `observability-store: Every root moves under a store prefix` | ✅ |
| Amendment | `aws_region` and `s3_bucket` become neutral `store_*` keys | `observability-store: Neutral keys on AWS`; `The former keys are gone` | ✅ |
| Amendment | Credentials from a Secret or workload identity; two store identities (K5) | — | ⚠️ Excluded: owned by #1035 (owner decision 2026-11-13); `StoreBackend` KDoc names the extension point (task 3.1) |
| Amendment | Compactor lease under the store prefix | — | ⚠️ Excluded: the lease is not on this branch; recorded as a requirement on #1048 |
| Owner 4a | Bucket-policy delete denies are prefix-aware; no change on AWS | `observability-store: An empty prefix leaves the policies unchanged`; `A store prefix adds denied paths and keeps the old ones`; `account-compactor: The task role policy is unchanged with an empty prefix`; `The task role reaches the compacted roots under a prefix` | ✅ |
| Risk | A missed key rename leaves an empty bucket at runtime | `object-store: The AWS configuration is unchanged` (expanded output) | ✅ |
| Risk | Event payloads lose their `s3://` locations | `object-store: S3 locations still read as s3 URIs` | ✅ |
| Risk | A store prefix narrows the account-wide deny another cluster relies on | `observability-store: A store prefix adds denied paths and keeps the old ones` | ✅ |
| Risk | A fragment spliced at the wrong indentation | `object-store: The AWS configuration is unchanged`; `A different block renders with no S3 in it` (parsed at the block's path) | ✅ |
| Risk | The neutral types pull in the AWS SDK | `object-store: The model stays outside the SDK boundary` | ✅ |
| Risk | Golden files go stale after an intended backend config edit | — | ⚠️ Excluded as a scenario: a process rule, recorded in `configuration/CLAUDE.md` (task 6.2) |
| Notes | Verify on a real AWS cluster: `up`, query all four signals, `down` | — | ⚠️ Excluded as a scenario: a manual `requires-aws` check, task 6.5 |
