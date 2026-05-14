# Bulk Writer S3 IAM

Spark bulk writer that uses S3 transport with IAM instance profile credentials. No static keys are extracted or passed to the sidecar — both the Spark executor and the Cassandra sidecar authenticate via their attached AWS IAM roles.

## ADDED Requirements

### Requirement: IAM credential mode for S3 bulk write

The `bulk-writer-s3-iam` module SHALL use `StorageCredentialPair.iamPair()` so that no static credentials are extracted from the Spark executor or transmitted to the sidecar.

#### Scenario: Write options include IAM credential type

- **WHEN** `IamBulkWriter` builds the write options map
- **THEN** the map MUST contain `STORAGE_CREDENTIAL_TYPE=IAM` and `DATA_TRANSPORT=S3_COMPAT`

#### Scenario: Extension does not resolve static credentials

- **WHEN** `EasyDbLabIamStorageExtension.initialize()` is called
- **THEN** it MUST NOT instantiate `DefaultCredentialsProvider` or call `resolveCredentials()`

#### Scenario: Storage configuration uses IAM auth markers

- **WHEN** `EasyDbLabIamStorageExtension.getStorageConfiguration()` is called
- **THEN** it MUST return a `StorageTransportConfiguration` built from `StorageCredentialPair.iamPair(region, region)` with `IamStorageAuth` as both write and read auth

### Requirement: Region detection at initialization

The IAM extension SHALL detect the AWS region via `DefaultAwsRegionProviderChain` and fail fast if the region cannot be determined.

#### Scenario: Region resolved from IMDS on EMR

- **WHEN** the extension initializes on an EMR node
- **THEN** it MUST successfully resolve the region from instance metadata without any explicit configuration

#### Scenario: Region detection failure throws at startup

- **WHEN** `DefaultAwsRegionProviderChain` cannot determine a region
- **THEN** `initialize()` MUST throw `IllegalStateException` with a message indicating region detection failed

### Requirement: Same S3 bucket configuration as static module

The IAM module SHALL accept the same `spark.easydblab.s3.bucket` and `spark.easydblab.s3.readBuckets` properties as the static writer, with the same validation rules.

#### Scenario: Missing bucket property fails with usage help

- **WHEN** `spark.easydblab.s3.bucket` is not set
- **THEN** `initialize()` MUST throw `IllegalArgumentException` naming the missing property and showing usage

#### Scenario: Missing read buckets property fails with usage help

- **WHEN** `spark.easydblab.s3.readBuckets` is not set
- **THEN** `initialize()` MUST throw `IllegalArgumentException` naming the missing property and showing the `dc1:bucket1,dc2:bucket2` format
