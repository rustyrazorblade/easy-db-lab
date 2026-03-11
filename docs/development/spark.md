# Spark Development

This guide covers developing and testing Spark-related functionality in easy-db-lab.

## Project Structure

All Spark modules live under `spark/` with shared configuration:

- `spark/common/` — Shared config (`SparkJobConfig`), data generation (`BulkTestDataGenerator`), CQL setup
- `spark/bulk-writer-sidecar/` — Cassandra Analytics, direct sidecar transport (`DirectBulkWriter`)
- `spark/bulk-writer-s3/` — Cassandra Analytics, S3 staging transport (`S3BulkWriter`)
- `spark/connector-writer/` — Standard Spark Cassandra Connector (`StandardConnectorWriter`)
- `spark/connector-read-write/` — Read→transform→write example (`KeyValuePrefixCount`)

Gradle modules use nested paths: `:spark:common`, `:spark:bulk-writer-sidecar`, etc.

## Prerequisites

The bulk-writer modules depend on [Apache Cassandra Analytics](https://github.com/apache/cassandra-analytics), which requires JDK 11 to build.

### One-Time Setup

```bash
bin/build-cassandra-analytics
```

Options:
- `--force` - Rebuild even if already built
- `--branch <branch>` - Use a specific branch (default: trunk)

## Building

```bash
# Build all Spark modules
./gradlew :spark:bulk-writer-sidecar:shadowJar :spark:bulk-writer-s3:shadowJar \
  :spark:connector-writer:shadowJar :spark:connector-read-write:shadowJar

# Build individually
./gradlew :spark:bulk-writer-sidecar:shadowJar
./gradlew :spark:connector-writer:shadowJar

# Output locations
ls spark/bulk-writer-sidecar/build/libs/bulk-writer-sidecar-*.jar
ls spark/connector-writer/build/libs/connector-writer-*.jar
```

Shadow JARs include all dependencies except Spark (provided by EMR).

## Running Tests

Main project tests exclude bulk-writer modules to avoid requiring cassandra-analytics:

```bash
./gradlew :test
```

## Testing with a Live Cluster

### Using bin/spark-bulk-write

This script handles JAR lookup, host resolution, and health checks:

```bash
# From a cluster directory (where state.json exists)
spark-bulk-write direct --rows 10000
spark-bulk-write s3 --rows 1000000 --parallelism 20
spark-bulk-write connector --keyspace myks --table mytable
```

### Using bin/submit-direct-bulk-writer

Simplified script for direct bulk writer testing:

```bash
bin/submit-direct-bulk-writer [rowCount] [parallelism] [partitionCount] [replicationFactor]
```

### Manual Spark Job Submission

All modules use unified `spark.easydblab.*` configuration:

```bash
easy-db-lab spark submit \
    --jar spark/bulk-writer-sidecar/build/libs/bulk-writer-sidecar-*.jar \
    --main-class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
    --conf spark.easydblab.contactPoints=host1,host2 \
    --conf spark.easydblab.keyspace=bulk_test \
    --conf spark.easydblab.localDc=us-west-2 \
    --conf spark.easydblab.rowCount=1000 \
    --conf spark.easydblab.replicationFactor=1 \
    --wait
```

## Debugging Failed Jobs

When a Spark job fails, easy-db-lab automatically queries logs and displays failure details.

### Manual Log Retrieval

```bash
easy-db-lab spark logs --step-id <step-id>
easy-db-lab spark status --step-id <step-id>
easy-db-lab spark jobs
```

### Direct S3 Access

Logs are stored at: `s3://<bucket>/spark/emr-logs/<cluster-id>/steps/<step-id>/`

```bash
aws s3 cp s3://<bucket>/spark/emr-logs/<cluster-id>/steps/<step-id>/stderr.gz - | gunzip
```

## Adding a New Spark Module

1. Create a directory under `spark/` (e.g., `spark/bulk-reader/`)
2. Add `build.gradle.kts` — use an existing module as a template
3. Add `include "spark:bulk-reader"` to `settings.gradle`
4. Depend on `:spark:common` for shared config
5. Use `SparkJobConfig.load(sparkConf)` for configuration
6. Implement your main class and submit via `easy-db-lab spark submit`

## Architecture Notes

### Shared Configuration

`SparkJobConfig` in `spark/common` provides:
- Property constants (`PROP_CONTACT_POINTS`, etc.)
- Config loading from `SparkConf` with validation
- Schema setup via `CqlSetup`
- Consistent defaults across all modules

### Why Shadow JAR?

Bulk-writer modules use the Gradle Shadow plugin because:
1. EMR provides Spark, so those dependencies are `compileOnly`
2. Cassandra Analytics has many transitive dependencies
3. `mergeServiceFiles()` properly handles `META-INF/services` for SPI

### Cassandra Analytics Modules

Some cassandra-analytics modules aren't published to Maven:
- `five-zero.jar` - Cassandra 5.0 bridge
- `five-zero-bridge.jar` - Bridge implementation
- `five-zero-types.jar` - Type converters
- `five-zero-sparksql.jar` - SparkSQL integration

These are referenced directly from `.cassandra-analytics/` build output.
