## Why

The existing S3 bulk writer passes static STS credentials (access key + secret + session token) to the Cassandra sidecar for SSTable import. When running on EMR with IAM instance profiles attached to both the EMR nodes and the Cassandra sidecar pods, this is unnecessary — both sides can authenticate directly via the AWS default credential chain. A new module using `STORAGE_CREDENTIAL_TYPE=IAM` eliminates the credential-passing step and enables deployments where static keys are unavailable or undesirable.

## What Changes

- New Gradle subproject `spark/bulk-writer-s3-iam` with its own shadow JAR
- New `EasyDbLabIamStorageExtension` that returns `StorageCredentialPair.iamPair(region, region)` instead of extracted STS keys — no `DefaultCredentialsProvider` instantiation, no credential validation
- New `IamBulkWriter` main class that sets `STORAGE_CREDENTIAL_TYPE=IAM` in write options alongside `DATA_TRANSPORT=S3_COMPAT`
- `spark/settings.gradle.kts` updated to include the new module

## Capabilities

### New Capabilities

- `bulk-writer-s3-iam`: Spark bulk writer using S3 transport with IAM instance profile credentials; no static keys extracted or transmitted to sidecar

### Modified Capabilities

- `spark-modules`: New module added to the Spark subproject family (module structure requirement extends to cover `bulk-writer-s3-iam`)

## Impact

- New files only under `spark/bulk-writer-s3-iam/`
- `spark/settings.gradle.kts` gains one `include` line
- No changes to core easy-db-lab, `SidecarManifestBuilder`, `SparkJobConfig`, or the existing `bulk-writer-s3` module
- Requires cassandra-analytics built from the `iam_credentials` fork branch (already configured in `spark/cassandra-analytics-source.properties`)
- Custom sidecar image with IAM support must be deployed: `102382809497.dkr.ecr.us-west-2.amazonaws.com/rustyrazorblade/cassandra-sidecar`
