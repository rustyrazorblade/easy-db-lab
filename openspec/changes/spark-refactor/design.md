## Context

Four Spark-related Gradle modules sit at the project root with inconsistent naming and heavily duplicated config parsing code. `AbstractBulkWriter` and `StandardConnectorWriter` each independently define the same `spark.easydblab.*` property constants and nearly identical `loadConfig()`/`getRequiredProperty()`/`getOptionalProperty()` methods. The bulk-writer module bundles two transport modes (Direct and S3) that should be independently deployable.

The main project (Kotlin/JVM 21) references these modules in `settings.gradle` and some shell scripts. The Spark modules all target Java 11 / Scala 2.12 / Spark 3.5.7.

## Goals / Non-Goals

**Goals:**
- Organize all Spark modules under `spark/` parent directory
- Eliminate config parsing duplication via a shared `common` module
- Split bulk-writer into separate sidecar and S3 modules
- Ensure all modules accept identical `spark.easydblab.*` config for easy comparison
- Unify host property: bulk-writer uses `spark.easydblab.sidecar.contactPoints`, connector uses `spark.easydblab.cassandra.host` — these should be consolidated into one property

**Non-Goals:**
- Changing the data generator logic or schema
- Adding new Spark job types (bulk-reader, etc. come later)
- Modifying EMR submission logic (just update jar references)
- Changing the cassandra-analytics build process

## Decisions

### 1. Module structure under `spark/`

```
spark/
├── build.gradle.kts          # subprojects { } shared config (Java 11, Spark 3.5.7)
├── common/                   # SparkJobConfig, DataGenerator, CqlSetup, BulkTestDataGenerator
│   └── build.gradle.kts      # java-library plugin, Spark + driver deps
├── bulk-writer-sidecar/      # DirectBulkWriter only
│   └── build.gradle.kts      # shadow plugin, cassandra-analytics deps
├── bulk-writer-s3/           # S3BulkWriter only
│   └── build.gradle.kts      # shadow plugin, cassandra-analytics deps
├── connector-writer/         # StandardConnectorWriter
│   └── build.gradle.kts      # shadow plugin, spark-cassandra-connector dep
└── connector-read-write/     # KeyValuePrefixCount (updated to use common config)
    └── build.gradle.kts
```

**Rationale**: Gradle's natural nested `include` syntax (`spark:common`, `spark:bulk-writer-sidecar`) keeps the root `settings.gradle` clean and groups related modules visually.

### 2. Extract `SparkJobConfig` into common

Create a `SparkJobConfig` class in `common` that encapsulates:
- All `spark.easydblab.*` property constants
- Config loading from `SparkConf` (required/optional property parsing)
- Config printing
- Schema setup delegation to `CqlSetup`

Each writer module calls `SparkJobConfig.load(sparkConf)` to get a populated config object, then uses it for schema setup and data generation. The writer-specific Spark session initialization and write logic stays in each module.

**Rationale**: The config parsing code is ~100 lines duplicated verbatim between `AbstractBulkWriter` and `StandardConnectorWriter`. A single `SparkJobConfig` eliminates this and ensures all modules accept exactly the same properties.

### 3. Unified host property

Consolidate `spark.easydblab.sidecar.contactPoints` and `spark.easydblab.cassandra.host` into a single `spark.easydblab.contactPoints` property. All modules use the same property for the database host list.

**Alternative considered**: Keep separate properties per implementation. Rejected because the goal is identical config across all modules.

### 4. Shared `subprojects {}` config in `spark/build.gradle.kts`

The parent build file sets:
- `sourceCompatibility = JavaVersion.VERSION_11`
- Common Spark dependency versions (3.5.7, Scala 2.12)
- Common repository declarations (mavenLocal for cassandra-analytics)

Each submodule only declares its own specific dependencies.

**Rationale**: Avoids duplicating Java version and Spark version across 5 build files.

### 5. AbstractBulkWriter becomes BulkWriterBase in common

The cassandra-analytics-specific parts (`BulkSparkConf.setupSparkConf()`, `CassandraDataSink`, `getBaseWriteOptions()`) move into a slim `BulkWriterBase` that lives alongside the bulk-writer modules — NOT in common, since common shouldn't depend on cassandra-analytics. Both `bulk-writer-sidecar` and `bulk-writer-s3` extend it.

Actually — since both bulk-writer modules already depend on the same cassandra-analytics libraries, `BulkWriterBase` should go in common only if common can take that dependency. It can't (common should stay lightweight). So `BulkWriterBase` stays as a class that both bulk-writer modules duplicate or share via a thin intermediate. Given there are only ~30 lines of shared bulk-writer-specific code (`initSpark`, `getBaseWriteOptions`, `writeData`), the simplest approach is: each bulk-writer module has its own main class that uses `SparkJobConfig` from common and handles its own write logic inline. No `AbstractBulkWriter` needed.

**Decision**: Delete `AbstractBulkWriter`. Each module is a standalone main class that: (1) creates SparkSession with its own config, (2) calls `SparkJobConfig.load()`, (3) does its own write. The shared code lives entirely in `SparkJobConfig` and `DataGenerator`.

## Risks / Trade-offs

- **[Risk] Shell scripts reference old module paths** → Mitigation: Update all scripts in `bin/` and CI workflows as part of the change. Grep for old names to catch all references.
- **[Risk] cassandra-analytics local Maven artifacts** → Mitigation: No change to the build process, just the directory the build file lives in. `mavenLocal()` resolution is path-independent.
- **[Risk] Duplicate bulk-writer-specific Spark init code** → Mitigation: The duplication is small (~15 lines of `BulkSparkConf` setup) and each module may diverge over time. Accepting this small duplication is better than introducing an intermediate shared module.
- **[Trade-off] connector-read-write may not use all common config properties** → It reads data rather than generating it, so `rowCount`/`parallelism`/`partitionCount` may not apply. `SparkJobConfig` should handle optional properties gracefully (already does with defaults).
