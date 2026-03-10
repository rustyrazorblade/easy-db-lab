## Why

The four Spark-related Gradle submodules (`spark-shared`, `bulk-writer`, `connector-writer`, `spark-connector-test1`) are scattered at the project root with inconsistent naming and duplicated configuration. This makes it hard to compare performance across different Spark-to-Cassandra write implementations, which is the primary use case for these modules. Reorganizing under a unified `spark/` parent with shared config enables swapping jar/main-class while keeping identical `--conf` flags.

## What Changes

- Move all Spark modules under a `spark/` parent project directory
- Rename `spark-shared` → `spark:common` with expanded role: shared config constants, config parsing, schema setup, and data generation
- Split `bulk-writer` into two modules: `spark:bulk-writer-sidecar` (direct transport) and `spark:bulk-writer-s3` (S3 transport)
- Rename `connector-writer` → `spark:connector-writer`
- Rename `spark-connector-test1` → `spark:connector-read-write`, updated to use shared config from common
- Extract generic parts of `AbstractBulkWriter` (config constants, config parsing, schema setup) into `common`; each module owns its Spark init and write path
- Update `settings.gradle` to use nested module paths (`:spark:common`, `:spark:bulk-writer-sidecar`, etc.)
- Update root `build.gradle.kts` references to the renamed modules
- Update documentation (`docs/user-guide/spark.md`) to reflect new module names and jar paths

## Capabilities

### New Capabilities

- `spark-modules`: Unified Spark module organization under `spark/` parent with shared configuration, enabling easy performance comparison across write implementations

### Modified Capabilities

- `spark-emr`: Module names and jar artifact paths change, affecting how `EMRSparkService` references Spark job jars

## Impact

- **Gradle build**: `settings.gradle` and all Spark module `build.gradle.kts` files restructured
- **Root build**: References to `:bulk-writer` and `:connector-writer` in root `build.gradle.kts` must update to `:spark:bulk-writer-sidecar`, `:spark:bulk-writer-s3`, `:spark:connector-writer`
- **EMR integration**: Any code that references jar names or module paths for Spark job submission
- **CI/CD**: Build scripts and test scripts referencing old module names (`bin/test-spark-bulk-writer`, etc.)
- **Documentation**: `docs/user-guide/spark.md` and CLAUDE.md references to module structure
