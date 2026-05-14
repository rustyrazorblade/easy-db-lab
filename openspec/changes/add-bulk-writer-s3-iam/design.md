## Context

The existing `bulk-writer-s3` module uses `EasyDbLabStorageExtension` to resolve actual AWS credentials (access key, secret, session token) via `DefaultCredentialsProvider` and passes them explicitly to the Cassandra sidecar as `StorageCredentials`. This works, but it means the sidecar receives static keys that expire, requiring re-resolution on each transport operation.

The forked `cassandra-analytics` library (branch `iam_credentials`) introduces `StorageCredentialPair.iamPair()` and `STORAGE_CREDENTIAL_TYPE=IAM`. When these are used, the sidecar receives only the region and a `credentialType=IAM` marker — no keys — and resolves its own credentials from its attached IAM role.

Both the EMR nodes (Spark executors) and the Cassandra sidecar pods already have IAM roles attached in the easy-db-lab AWS environment, making this mode viable.

## Goals / Non-Goals

**Goals:**
- New `spark/bulk-writer-s3-iam` module that writes via S3 transport without passing static credentials to the sidecar
- Module follows identical usage pattern to `bulk-writer-s3` (same `--conf` flags for bucket, contact points, keyspace, etc.)
- Compatible with the custom sidecar image: `102382809497.dkr.ecr.us-west-2.amazonaws.com/rustyrazorblade/cassandra-sidecar`

**Non-Goals:**
- Modifying the existing `bulk-writer-s3` module
- Adding credential-type selection to `SparkJobConfig` or `EasyDbLabStorageExtension`
- Supporting non-AWS IAM environments (EKS IRSA, ECS task roles are future concerns)

## Decisions

**Separate module rather than a flag in `bulk-writer-s3`**

The IAM and static-credential paths have meaningfully different initialization logic (no `DefaultCredentialsProvider`, no fail-fast credential validation). A flag would add conditional branches inside `EasyDbLabStorageExtension`. A new module keeps both extensions simple and independently testable. Consistent with the existing module-per-strategy pattern in `spark/`.

**`DefaultAwsRegionProviderChain` for region detection (same as static version)**

`StorageCredentialPair.iamPair(region, region)` requires a region string. On EMR the chain auto-detects from IMDS. No explicit `spark.easydblab.s3.region` property is added — the same constraint applies to the static writer and has not been a problem.

**`EasyDbLabIamStorageExtension` does not validate credentials at `initialize()` time**

In IAM mode there are no credentials to validate — the sidecar uses its own role, and the Spark executor uses the instance profile for S3 multipart upload. Fail-fast credential checks don't apply. Region detection is still validated at startup (throws if IMDS is unreachable).

**`STORAGE_CREDENTIAL_TYPE=IAM` set in `IamBulkWriter`, not in the extension**

The credential type is a Spark job write option consumed by `BulkSparkConf`, not by `StorageTransportExtension`. Setting it in the writer's `writeOptions` map mirrors the pattern used for `DATA_TRANSPORT` and other transport options in `S3BulkWriter`.

## Risks / Trade-offs

**IAM roles must have correct S3 permissions** → Both the EMR instance profile and the K8s service account backing the sidecar pod must have `s3:GetObject`, `s3:PutObject`, `s3:ListBucket` on the relevant buckets. If not configured, the failure will occur at runtime during the Spark job, not at startup.

**Region mismatch between EMR and S3 bucket** → `DefaultAwsRegionProviderChain` returns the region of the EMR cluster. If the S3 bucket is in a different region, upload will fail. Same constraint as `bulk-writer-s3`.

**Fork dependency** → This module only works with the `iam_credentials` fork of cassandra-analytics, already configured in `spark/cassandra-analytics-source.properties`. Merging upstream makes the fork constraint go away.
