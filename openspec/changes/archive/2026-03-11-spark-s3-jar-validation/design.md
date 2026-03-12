## Context

When `spark submit --jar s3://bucket/path.jar` is run, the S3 path is passed directly to EMR without any validation. If the jar doesn't exist, the EMR step fails minutes later with a non-obvious error. The `ObjectStore` interface already provides a `fileExists()` method that can be used for this check.

## Goals / Non-Goals

**Goals:**
- Validate S3 jar existence before submitting the EMR step
- Fail fast with a clear error message
- Integration test with LocalStack to verify the behavior

**Non-Goals:**
- Validating jar contents (class file structure, manifest, etc.)
- Validating that `--main-class` exists within the jar
- Adding S3 validation to other commands

## Decisions

**1. Validate in `SparkSubmit.execute()`, not in the service layer**

The validation belongs in the command because `SparkSubmit` already handles the S3-vs-local branching logic. When the path is S3, add a `fileExists()` check right after determining it's an S3 path. This keeps the service layer unchanged.

Alternative: Adding validation to `SparkService.submitJob()` — rejected because the service receives an already-resolved S3 path and shouldn't need to know about ObjectStore.

**2. Parse S3 path into `ClusterS3Path` for the `fileExists()` call**

The `ObjectStore.fileExists()` takes a `ClusterS3Path`. We need to parse the raw `s3://bucket/key` string into a `ClusterS3Path`. Check if `ClusterS3Path` already has a `parse()` or `fromUri()` factory method; if not, add one.

**3. Use LocalStack TestContainer for integration test**

LocalStack provides a real S3 API for testing. The test will:
- Create a bucket and upload a jar to it
- Verify `fileExists()` returns true for the existing jar
- Verify `fileExists()` returns false for a non-existent jar
- Verify the command fails fast when given a non-existent S3 jar path

## Risks / Trade-offs

- **[Added latency]** The `fileExists()` call adds one S3 HeadObject request (~50-100ms). This is negligible compared to EMR step startup time. → Acceptable trade-off for fail-fast behavior.
- **[Race condition]** The jar could be deleted between validation and EMR reading it. → Extremely unlikely in practice, and the current behavior (no validation) is strictly worse.
