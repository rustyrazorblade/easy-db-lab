## Why

When a user passes an S3 path to `spark submit --jar s3://bucket/nonexistent.jar`, the job is submitted to EMR without verifying the jar exists. EMR then fails with a cryptic error minutes later. Validating the jar exists upfront gives immediate, clear feedback and avoids wasting time waiting for a doomed job to fail.

## What Changes

- Add S3 head-object validation in `SparkSubmit` when `--jar` is an S3 path, before submitting the job
- Fail fast with a clear error message if the jar does not exist at the given S3 path
- Add an `exists()` method to `ObjectStore` interface and `S3ObjectStore` implementation
- Add an integration test using LocalStack to verify the validation behavior

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `spark-emr`: Adding a requirement that S3 jar paths are validated for existence before job submission

## Impact

- **Code**: `SparkSubmit.kt` (validation logic), `ObjectStore.kt` (new `exists()` method), `S3ObjectStore.kt` (implementation)
- **Tests**: New integration test with LocalStack for S3 jar existence validation
- **Dependencies**: LocalStack TestContainer dependency (may already exist)
