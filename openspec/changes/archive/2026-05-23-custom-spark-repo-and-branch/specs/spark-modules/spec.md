## MODIFIED Requirements

### Requirement: Spark modules organized under spark parent

All Spark job modules SHALL be Gradle subprojects under the `spark/` directory, using nested module paths. The cassandra-analytics source used to build bulk-writer modules SHALL be configurable via `spark/cassandra-analytics-source.properties`.

#### Scenario: Gradle module resolution

- **WHEN** the project is built with `./gradlew :spark:common:build` or `./gradlew :spark:bulk-writer-sidecar:shadowJar`
- **THEN** Gradle MUST resolve the modules from `spark/common/` and `spark/bulk-writer-sidecar/` respectively

#### Scenario: Common module provides shared config and data generation

- **WHEN** a Spark job module depends on `:spark:common`
- **THEN** it MUST have access to `SparkJobConfig`, `DataGenerator`, `BulkTestDataGenerator`, and `CqlSetup`

#### Scenario: Build prerequisite uses configured source

- **WHEN** `bin/build-cassandra-analytics` is run before building bulk-writer modules
- **THEN** it MUST clone and build from the repo and branch specified in `spark/cassandra-analytics-source.properties`
