## 1. Add S3 URI parsing to ClusterS3Path

- [x] 1.1 Add `fromUri(uri: String): ClusterS3Path` factory method to `ClusterS3Path.Companion` that parses `s3://bucket/key` strings into a `ClusterS3Path`. Throw `IllegalArgumentException` if the URI doesn't start with `s3://`.

## 2. Add S3 jar validation to SparkSubmit

- [x] 2.1 In `SparkSubmit.execute()`, after the `if (jarPath.startsWith("s3://"))` branch resolves the S3 jar path, add a `fileExists()` check using `ObjectStore`. Parse the S3 URI into a `ClusterS3Path` using `fromUri()` and call `objectStore.fileExists()`. Fail with `require()` and a clear error message including the S3 path if the jar does not exist.

## 3. Integration test with LocalStack

- [x] 3.1 Create an integration test class that uses LocalStack TestContainer with S3 to verify: (a) `fileExists()` returns true for an existing S3 object, (b) `fileExists()` returns false for a non-existent S3 object, (c) the validation logic in `SparkSubmit` fails fast when given a non-existent S3 jar path. Use `S3ObjectStore` with a LocalStack-configured S3 client.

## 4. Unit test for ClusterS3Path.fromUri

- [x] 4.1 Add unit tests for `ClusterS3Path.fromUri()`: valid `s3://bucket/path/file.jar` parsing, bucket-only `s3://bucket`, and invalid URIs (missing `s3://` prefix).
