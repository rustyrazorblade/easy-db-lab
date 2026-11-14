## Context

Measured on `local-k8s-edbl` at `133c4661`.  Paths below are under `src/main/kotlin/com/rustyrazorblade/easydblab/`, and resources under `src/main/resources/com/rustyrazorblade/easydblab/`.

- `configuration/ClusterS3Path.kt` holds `bucket` and key segments.  `toString()`/`toUri()` render `s3://` (`:171-185`), `fromUri()` parses it (`:100-108`), and `toEndpointUrl(region)` renders `https://<bucket>.s3.<region>.amazonaws.com/` (`:194-201`, no caller today).
- `ClusterS3Path` is used by 15 main files and 19 test files.  `toUri()` is called for display in `ClusterBackupService` (17), `ReportUpload` (2), `GrafanaAnnotationBackupService` (2), `CassandraBuild` (1) and `CassandraBuildCatalog` (1), and inside the adapter packages.  `SparkSubmit.kt:91` parses an `s3://` jar argument.
- The backend configs are filled in two ways:
  - **Runtime expansion:** Loki, Mimir and Tempo ship their YAML verbatim and run with `-config.expand-env=true`.  The `${S3_BUCKET}`, `${AWS_REGION}` and `${*_S3_PREFIX}` values come from `cluster-config` through `clusterConfigEnv()` (`LokiManifestBuilder.kt:161-162`, `MimirManifestBuilder.kt:191-192`, `TempoManifestBuilder.kt:163-176`).
  - **Build-time substitution:** Pyroscope's `config.yaml` uses `__ACCOUNT_BUCKET__`, `__AWS_REGION__` and `__PROFILES_S3_PREFIX__`, filled by `TemplateService`.
  - **Compactor:** `services/aws/CompactorTaskDefinition.kt:164-165` passes `S3_BUCKET` and `AWS_REGION` to the task, which reads `mimir.yaml`, `loki.yaml` and `services/aws/tempo-backend.yaml`.
- The delete guards are built in `providers/aws/AWSPolicy.kt`: the instance-role deny (`denyDataDeletes`, `:233-248`), the compactor task-role allow (`CompactorTaskAccess`, `:289-305`) and the bucket-policy deny (`S3BucketPolicy`, `:318-353`).  All three join a root constant to the bucket ARN with no prefix.
- #1048 (in-memory adapter and conditional write) is not on this branch.  No credential is passed to any pod today; every S3 client uses the instance role.

## Goals / Non-Goals

**Goals:**
- No class outside the S3 adapter renders `s3://`, an S3 endpoint or an S3 ARN key.
- A second store can plug in by adding one `StoreBackend` case, one adapter and one fragment per backend.
- On AWS, every rendered backend config, the compactor task definition and the compactor task-role policy are byte-identical to today's. The instance-role and bucket-policy denies gain only the same roots under any prefix (D6).

**Non-Goals:** see the proposal's out-of-scope list.  In particular there is no credential source, no volume store and no GCS or Azure code.

## Decisions (owner-approved 2026-11-13)

### D1. The path type is `ClusterStoragePath`

`configuration/ClusterStoragePath.kt` replaces `ClusterS3Path`: `bucket`, `prefix` (default `""`) and key segments.

- `getKey()` returns `prefix/segments` with empty parts dropped, so an empty prefix gives today's key.
- `toString()` returns `bucket/key`, with no scheme.  It is for logs only; user-facing locations go through `ObjectStore.uriOf`.
- `from(clusterState)` and `root(bucket)` take the store prefix from the cluster's `StoreBackend`.
- The technology helpers (`cassandra()`, `spark()`, `emrLogs()`, `kubeconfig()` …) stay; they only add key segments.
- `toUri`, `fromUri` and `toEndpointUrl` are removed.  `services/aws/S3Uri` provides `uri(path)`, `parse(uri)` and `endpointUrl(path, region)`.  `toEndpointUrl` has no caller, so it moves only if a caller needs it during the change; otherwise it is deleted.

*Rejected:* `StorePath` (the architecture doc's term).  The owner chose the issue's name, which matches `ClusterState` and `ClusterConfigData`.

### D2. Display URIs come from the adapter

`ObjectStore` gains `fun uriOf(path: ClusterStoragePath): String`.  `S3ObjectStore` returns `S3Uri.uri(path)` (`s3://bucket/key`).  `InMemoryObjectStore` returns `mem://bucket/key`.  The 23 display sites call `objectStore.uriOf(path)`, so event payloads on AWS keep the exact `s3://` string that MCP and Redis subscribers see today.

`SparkSubmit` parses its jar argument with `S3Uri.parse`, because Spark on EMR exists only on AWS.  It is a command, so it is allowed to depend on `services/aws`.

### D3. `StoreBackend` is a neutral sealed type

```kotlin
// configuration/store/StoreBackend.kt
sealed interface StoreBackend {
    val bucket: String
    val prefix: String
}

data class S3Backend(
    override val bucket: String,
    override val prefix: String = "",
    val region: String,
    val endpoint: String = "s3.$region.amazonaws.com",
) : StoreBackend
```

- `StoreBackend.from(clusterState, defaultRegion)` returns `S3Backend(clusterState.s3Bucket, "", region)`.  Only that factory knows that every cluster stores on S3 today; #1035 makes it read the recorded environment.
- The type holds plain strings and imports nothing from `software.amazon.awssdk`, so the ArchUnit rule of #746 holds with no allowlist.
- The KDoc names the two extension points #1035 owns: a volume case, and a credential source on the object-store case (Secret, IRSA or EKS Pod Identity, and the writer and compactor identities of decision K5).  No field or case exists for them yet.

*Rejected:* adding `credentialSource` now with one instance-role variant.  Nothing would read it, and #1035 owns the decision.

### D4. Each backend's object-store block is a fragment resource

Each backend config holds a placeholder where its object-store block stands today: `__OBJECT_STORE_BLOCK__`, or a key-level placeholder where the block is nested.  The fragments live next to the config:

- `configuration/mimir/store/s3.yaml`
- `configuration/loki/store/s3.yaml`
- `configuration/tempo/store/s3.yaml`
- `configuration/pyroscope/store/s3.yaml`
- `services/aws/compactor/tempo-backend/store/s3.yaml` (or next to `tempo-backend.yaml`)

Each S3 fragment holds the lines that stand in the config today, comments and indentation included.  A small neutral `StoreBlockRenderer` in `configuration/store/` maps a `StoreBackend` case to the fragment name and splices it into the config text at the placeholder with its indentation.  Each manifest builder and `CompactorTaskDefinition` call it before they publish the config.  `when` over the sealed type makes a missing case a compile error.

The fragment keeps the `${...}` and `__...__` names it has today (renamed per D5).  The runtime expansion and `TemplateService` are unchanged.

This does not build YAML from Kotlin strings: Kotlin picks a resource file and places it, the same as `TemplateService`.

*Rejected:* a typed kotlinx.serialization emitter.  Each backend nests the block differently (`blocks_storage.s3`, `common.storage.s3`, `storage.trace.s3`) and carries its own extras, and an emitter would not reproduce the hand-written comments and order, so the AWS config could not stay byte-identical.

### D5. `cluster-config` uses neutral keys

`ClusterConfigData.of` publishes:

| Today | After |
| --- | --- |
| `aws_region` | `store_region` |
| `s3_bucket` | `store_bucket` |
| — | `store_prefix` (empty on AWS) |
| — | `store_endpoint` (`s3.<region>.amazonaws.com` on AWS) |
| `traces_s3_prefix` | `traces_store_prefix` |
| `metrics_s3_prefix` | `metrics_store_prefix` |
| `logs_s3_prefix` | `logs_store_prefix` |

The values come from `StoreBackend` and `ObservabilityStore`.  The container env names change with them (`STORE_BUCKET`, `STORE_REGION`, `STORE_ENDPOINT`, `*_STORE_PREFIX`), as do the compactor's task env (`CompactorTaskDefinition.kt:164-165`) and Pyroscope's build-time names.  Only the S3 fragments name `STORE_REGION` and `STORE_ENDPOINT`.  No kit, script or dashboard reads these keys today.

The S3 fragments take the endpoint from `${STORE_ENDPOINT}` instead of the literal `s3.${AWS_REGION}.amazonaws.com`.  The expanded value is the same string, so the golden files of D7 still match.

### D6. The delete denies cover every prefix; the compactor allow names one

`AWSPolicy` builds the two denies (`denyDataDeletes` on the instance role, the Deny of `S3BucketPolicy`) from `underAnyPrefix`: each denied root at the top of the bucket, and the same root under any prefix (`arn:aws:s3:::<bucket>/*/<root>/*`; `*` spans `/` in an IAM resource, so a nested prefix is covered too).  The deny builders take no prefix at all, so every caller builds the same document.

This matters because each policy is put whole.  The EC2 instance role (`Constants.AWS.Roles.EC2_INSTANCE_ROLE`) is account-wide, and the bucket policy is shared by every cluster on the bucket.  `up`, `profile setup`, `AwsS3BucketService.ensureAccountBucket` and `AWSResourceSetupService` all put them.  A deny built from the caller's own prefix would be replaced by the next caller's, and a cluster on another prefix would lose its guard.  A deny that does not depend on the prefix keeps every cluster's guard, whoever put it last.

- With an empty prefix, each deny covers what it covered before and also the same roots under any prefix.  The golden files `instance-role-policy.json` and `bucket-policy.json` were updated for this one change: each deny's resource list gains the `*/<root>/*` entries.  Nothing else in either document changed.
- A store prefix is validated where it is built (`StorePrefix`): plain path segments, no IAM wildcard, no `..`, and a first segment that is none of the roots.
- The compactor task role's allow is the only policy that names the prefix (`storeRoots`): the unprefixed compacted roots and, when the prefix is not empty, the same roots under it.  It gains nothing outside `mimir/`, `loki/` and `tempo/`, and its document with an empty prefix is byte-identical to today's.
- Follow-up (#1035): the compactor task role is account-wide too, and `CompactorIam.ensure` puts its allow whole, so the last `up` wins: a cluster on prefix `b` replaces the allow a cluster on prefix `a` put, and the compactor then cannot compact `a/`'s prefixed roots until `a` runs `up` again.  Nothing is deleted or lost (the data stays uncompacted), so this change leaves it; #1035 makes the allow cover every prefix in use.

### D7. Golden files prove the AWS config is unchanged

Task 0 runs before any refactor, on this branch, and writes the "before" captures under `src/test/resources/com/rustyrazorblade/easydblab/golden/store/`:

- `mimir.yaml`, `loki.yaml` and `tempo.yaml`: each ConfigMap's config text, expanded against the AWS `cluster-config` values of a fixed test cluster (bucket `easy-db-lab-test`, region `us-west-2`, tenant `acme`) by a test helper that substitutes `${VAR}` the way `-config.expand-env` does.
- `pyroscope.yaml`: the `TemplateService` output.
- `compactor-task-definition.json`: the registered `RegisterTaskDefinitionRequest`, with each base64 config decoded and expanded against the task env.
- `instance-role-policy.json`, `bucket-policy.json` and `compactor-task-policy.json`: the three `AWSPolicy` documents.

After the refactor, the tests render each through the new code with `S3Backend(bucket, "", region)` and compare byte for byte.  The before-capture is committed in its own commit, so a reviewer can see it was taken from unchanged code.

A second test renders every backend with a test-only `StoreBackend` case and a stub fragment in `src/test/resources`.  It asserts that the stub block appears and that the output holds no `s3://`, no `amazonaws.com` and no `endpoint:` line from the S3 fragment.  The stub case needs a test-only implementation of the sealed type; because Kotlin seals by module and package, the renderer takes the fragment name through a small `StoreBlockSource` function so a test can supply its own.  The production `when` stays exhaustive over the real cases.

### D8. The in-memory adapter

`services/InMemoryObjectStore` implements every `ObjectStore` method over a `ConcurrentHashMap<String, ByteArray>` keyed by `bucket/key`:

- `listFiles` honours `recursive` and `startAfter` in key order, as `S3ObjectStore` does.
- `downloadFile` replaces an existing local file; `readContent` and `downloadFile` of a missing key throw.
- `deleteFile` removes the key.  The adapter is for tests and has no policy layer.

It is in `src/main` so the unit and integration tiers can both use it, and it has its own contract test.  #1048 adds the conditional write to it.

The 9 test files that mock `ObjectStore` are sorted: a file that only stubs storage moves to the adapter; a file that makes the mock throw to test a failure keeps the mock.  Expected: `TestDocumentServiceTest`, `ReportUploadTest`, `TenantDirectoryTest`, `GrafanaUpdateConfigTest`, `ObservabilityStackServiceTest`, `KitRunnerCommandFactoryTest`, `KitEndToEndTest`, `SparkSubmitTest` and `GrafanaInstallTest` are each checked; the sort is recorded in the tasks.

## Risks / Trade-offs

- **A missed key rename gives an empty bucket at runtime.**  The builders, the `${...}` names and the fragments must change together.  Mitigation: the golden files of D7 compare the expanded output, so an unexpanded or empty variable fails the test.
- **Display strings are a contract.**  Event payloads carry `s3://` locations.  Mitigation: `uriOf` keeps them; tests for `ClusterBackupService` and `ReportUpload` assert the `s3://` string.
- **Golden files are brittle.**  A later, intended edit to a backend config must update the golden file in the same change.  `configuration/CLAUDE.md` says so.
- **Shared-bucket deny (D6).**  Each deny covers every root under any prefix and names none, so whichever caller puts it last, no cluster loses its guard.
- **All backends roll once** on the first `up` after the change, because `cluster-config` and the configs change.  Expected; clusters are ephemeral.
- **Fragment indentation.**  A fragment spliced at the wrong depth gives valid but wrong YAML.  Mitigation: the golden files catch it on AWS, and the stub test parses the output with a YAML parser and reads the block at its expected path.
- **ArchUnit.**  `ClusterStoragePath`, `StoreBackend`, `S3Backend`, `StoreBlockRenderer` and `InMemoryObjectStore` stay outside `services/aws` and import no SDK type; `S3Uri` lives in `services/aws`.

## Migration Plan

None.  Clusters are ephemeral; a running cluster picks the new keys up on its next `up`.

## Open Questions

None.  The owner answered the design questions on 2026-11-13.
