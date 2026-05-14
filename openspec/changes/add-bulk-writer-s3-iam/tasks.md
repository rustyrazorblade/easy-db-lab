## 1. Module Scaffold

- [x] 1.1 Create `spark/bulk-writer-s3-iam/` directory structure mirroring `spark/bulk-writer-s3/`
- [x] 1.2 Create `spark/bulk-writer-s3-iam/build.gradle.kts` based on `bulk-writer-s3`'s, updating main class to `IamBulkWriter`
- [x] 1.3 Add `include(":spark:bulk-writer-s3-iam")` to `spark/settings.gradle.kts`

## 2. IAM Storage Extension

- [x] 2.1 Create `EasyDbLabIamStorageExtension.java` in `spark/bulk-writer-s3-iam/src/main/java/com/rustyrazorblade/easydblab/spark/`
- [x] 2.2 Implement `initialize()`: validate `PROP_S3_BUCKET` and `PROP_S3_READ_BUCKETS`, detect region via `DefaultAwsRegionProviderChain`, no credential provider
- [x] 2.3 Implement `getStorageConfiguration()`: build `StorageTransportConfiguration` using `StorageCredentialPair.iamPair(region, region)` for both write and read access configs
- [x] 2.4 Implement lifecycle callbacks (`onTransportStart`, `onAllObjectsPersisted`, `onStageSucceeded`, `onStageFailed`, `onImportSucceeded`, `onImportFailed`, `onJobSucceeded`, `onJobFailed`) with appropriate logging
- [x] 2.5 Implement no-op callbacks (`setCoordinationSignalListener`, `setCredentialChangeListener`, `setObjectFailureListener`, `onObjectApplied`)

## 3. IAM Bulk Writer Main Class

- [x] 3.1 Create `IamBulkWriter.java` based on `S3BulkWriter.java`
- [x] 3.2 Add `writeOptions.put("STORAGE_CREDENTIAL_TYPE", "IAM")` alongside `DATA_TRANSPORT=S3_COMPAT` and `DATA_TRANSPORT_EXTENSION_CLASS`

## 4. Verify Build

- [x] 4.1 Run `./gradlew :spark:bulk-writer-s3-iam:shadowJar` and confirm JAR is produced in `spark/bulk-writer-s3-iam/build/libs/`
