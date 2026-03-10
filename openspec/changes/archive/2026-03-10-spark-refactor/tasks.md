## 1. Create directory structure and Gradle config

- [x] 1.1 Create `spark/` directory with subdirectories: `common/`, `bulk-writer-sidecar/`, `bulk-writer-s3/`, `connector-writer/`, `connector-read-write/`
- [x] 1.2 Create `spark/build.gradle.kts` with shared `subprojects {}` config (Java 11, Spark 3.5.7, Scala 2.12, common repos)
- [x] 1.3 Update `settings.gradle` to replace old flat includes with nested `spark:*` includes
- [x] 1.4 Create `spark/common/build.gradle.kts` (java-library, Spark compileOnly, Cassandra driver)
- [x] 1.5 Create `spark/bulk-writer-sidecar/build.gradle.kts` (shadow plugin, cassandra-analytics deps, guava relocation)
- [x] 1.6 Create `spark/bulk-writer-s3/build.gradle.kts` (shadow plugin, cassandra-analytics deps, guava relocation)
- [x] 1.7 Create `spark/connector-writer/build.gradle.kts` (shadow plugin, spark-cassandra-connector dep)
- [x] 1.8 Create `spark/connector-read-write/build.gradle.kts` (shadow plugin or fat jar, spark-cassandra-connector dep)

## 2. Extract shared config into common module

- [x] 2.1 Move `DataGenerator.java`, `BulkTestDataGenerator.java`, `CqlSetup.java` from `spark-shared/src/` to `spark/common/src/`
- [x] 2.2 Create `SparkJobConfig.java` in common: property constants, `load(SparkConf)`, config printing, validation with usage help
- [x] 2.3 Unify host property to `spark.easydblab.contactPoints` (replacing both `sidecar.contactPoints` and `cassandra.host`)

## 3. Migrate bulk-writer-sidecar

- [x] 3.1 Move `DirectBulkWriter.java` to `spark/bulk-writer-sidecar/src/`
- [x] 3.2 Refactor to use `SparkJobConfig` from common instead of `AbstractBulkWriter`
- [x] 3.3 Keep cassandra-analytics-specific Spark init (`BulkSparkConf.setupSparkConf()`) and write logic inline

## 4. Migrate bulk-writer-s3

- [x] 4.1 Move `S3BulkWriter.java` to `spark/bulk-writer-s3/src/`
- [x] 4.2 Refactor to use `SparkJobConfig` from common instead of `AbstractBulkWriter`
- [x] 4.3 Keep cassandra-analytics-specific Spark init and S3 transport config inline

## 5. Migrate connector-writer

- [x] 5.1 Move `StandardConnectorWriter.java` to `spark/connector-writer/src/`
- [x] 5.2 Refactor to use `SparkJobConfig` from common, removing duplicated config parsing

## 6. Migrate connector-read-write

- [x] 6.1 Move `KeyValuePrefixCount.java` to `spark/connector-read-write/src/`
- [x] 6.2 Update to use `SparkJobConfig` from common where applicable

## 7. Delete old modules

- [x] 7.1 Delete old top-level directories: `spark-shared/`, `bulk-writer/`, `connector-writer/`, `spark-connector-test1/`
- [x] 7.2 Remove `AbstractBulkWriter.java` (no longer needed)

## 8. Update references

- [x] 8.1 Update root `build.gradle.kts` if it references old module names
- [x] 8.2 Update shell scripts in `bin/` that reference old jar paths or module names
- [x] 8.3 Update `.github/workflows/pr-checks.yml` for new module paths
- [x] 8.4 Update `.gitignore` for new module paths
- [x] 8.5 Update `bin/end-to-end-test`: replace `step_bulk_writer_direct` with three separate steps — `step_bulk_writer_sidecar`, `step_bulk_writer_s3`, `step_connector_writer` — each submitting its respective jar with the same `spark.easydblab.*` config and a small dataset. Each step writes to its own table name and verifies the row count after completion.

## 9. Update documentation

- [x] 9.1 Update `docs/user-guide/spark.md` with new module names, jar paths, and unified config
- [x] 9.2 Update `docs/development/spark.md` with new module structure
- [x] 9.3 Update `CLAUDE.md` references to spark module organization

## 10. Verify

- [x] 10.1 Run `./gradlew :spark:common:build` and verify it compiles
- [x] 10.2 Run `./gradlew :spark:bulk-writer-sidecar:shadowJar` and verify fat JAR is produced
- [x] 10.3 Run `./gradlew :spark:bulk-writer-s3:shadowJar` and verify fat JAR is produced
- [x] 10.4 Run `./gradlew :spark:connector-writer:shadowJar` and verify fat JAR is produced
- [x] 10.5 Run `./gradlew :spark:connector-read-write:build` and verify it compiles
- [x] 10.6 Run `./gradlew check` to verify no regressions in main project
