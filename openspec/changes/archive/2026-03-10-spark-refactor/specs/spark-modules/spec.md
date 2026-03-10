## ADDED Requirements

### Requirement: Unified Spark module configuration

All Spark job modules SHALL share a common configuration interface via `spark.easydblab.*` Spark properties, enabling identical `--conf` flags across different implementations.

#### Scenario: All modules accept the same core properties

- **WHEN** any Spark job module is submitted with `--conf spark.easydblab.contactPoints=host1,host2 --conf spark.easydblab.keyspace=test --conf spark.easydblab.localDc=dc1`
- **THEN** the module MUST parse and use those properties for database connectivity and schema setup

#### Scenario: Config defaults are consistent across modules

- **WHEN** a Spark job is submitted without optional properties (`rowCount`, `parallelism`, `partitionCount`, `replicationFactor`, `skipDdl`)
- **THEN** all modules MUST use the same default values: rowCount=1000000, parallelism=10, partitionCount=10000, replicationFactor=3, skipDdl=false

#### Scenario: Missing required property fails with usage help

- **WHEN** a required property (`contactPoints`, `keyspace`, `localDc`) is not provided
- **THEN** the module MUST exit with an error listing all required and optional properties

### Requirement: Spark modules organized under spark parent

All Spark job modules SHALL be Gradle subprojects under the `spark/` directory, using nested module paths.

#### Scenario: Gradle module resolution

- **WHEN** the project is built with `./gradlew :spark:common:build` or `./gradlew :spark:bulk-writer-sidecar:shadowJar`
- **THEN** Gradle MUST resolve the modules from `spark/common/` and `spark/bulk-writer-sidecar/` respectively

#### Scenario: Common module provides shared config and data generation

- **WHEN** a Spark job module depends on `:spark:common`
- **THEN** it MUST have access to `SparkJobConfig`, `DataGenerator`, `BulkTestDataGenerator`, and `CqlSetup`

### Requirement: Independent deployable JARs per implementation

Each Spark job module SHALL produce its own standalone JAR (shadow/fat JAR) that can be submitted independently to EMR.

#### Scenario: Swap implementation by changing jar and main class

- **WHEN** a user submits a Spark job with `--jar spark/bulk-writer-sidecar/build/libs/bulk-writer-sidecar-all.jar --main-class ...DirectBulkWriter` using the same `--conf` flags
- **AND** then submits with `--jar spark/connector-writer/build/libs/connector-writer-all.jar --main-class ...StandardConnectorWriter` using the same `--conf` flags
- **THEN** both jobs MUST write to the same keyspace/table schema using the same data generator, enabling direct performance comparison
