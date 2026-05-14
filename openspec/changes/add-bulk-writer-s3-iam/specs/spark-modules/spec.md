## ADDED Requirements

### Requirement: bulk-writer-s3-iam module in Spark subproject family

The `spark/bulk-writer-s3-iam` module SHALL be a Gradle subproject under `spark/` producing its own standalone shadow JAR, following the same structure as existing bulk-writer modules.

#### Scenario: Gradle module resolution

- **WHEN** the project is built with `./gradlew :spark:bulk-writer-s3-iam:shadowJar`
- **THEN** Gradle MUST resolve the module from `spark/bulk-writer-s3-iam/` and produce a shadow JAR

#### Scenario: Swap to IAM module using same conf flags

- **WHEN** a user submits with `--jar bulk-writer-s3-iam.jar --main-class ...IamBulkWriter` using the same `--conf spark.easydblab.*` flags as `bulk-writer-s3`
- **THEN** the job MUST write to the same keyspace/table schema using the same data generator, with the only behavioral difference being the credential handoff to the sidecar
