# Spark Development

This guide covers developing and testing Spark-related functionality in easy-db-lab.

## Project Structure

```
bulk-writer/                    # Spark application for bulk loading Cassandra
├── build.gradle.kts           # Gradle build with shadow plugin
└── src/main/java/             # Java source code
    └── com/rustyrazorblade/easydblab/spark/
        ├── DirectBulkWriter.java   # Direct sidecar connection mode
        └── S3BulkWriter.java       # S3 staging mode

bin/
├── build-cassandra-analytics  # Builds cassandra-analytics (JDK 11)
├── submit-direct-bulk-writer  # Test script for DirectBulkWriter
└── debug-log-pipeline         # Debug Victoria Logs pipeline

.cassandra-analytics/          # Cloned cassandra-analytics repo (gitignored)
```

## Prerequisites

The `bulk-writer` module depends on [Apache Cassandra Analytics](https://github.com/apache/cassandra-analytics), which requires JDK 11 to build. A Docker-based script handles this automatically.

### One-Time Setup

Build cassandra-analytics locally (required before bulk-writer will compile):

```bash
bin/build-cassandra-analytics
```

This script:
1. Clones `apache/cassandra-analytics` to `.cassandra-analytics/`
2. Builds inside Docker with JDK 11
3. Publishes to local Maven repository (`~/.m2/repository`)

Options:
- `--force` - Rebuild even if already built
- `--branch <branch>` - Use a specific branch (default: trunk)

## Building the Bulk Writer

After cassandra-analytics is built:

```bash
# Build the fat JAR
./gradlew :bulk-writer:shadowJar

# Output location
ls bulk-writer/build/libs/bulk-writer-*.jar
```

The shadow JAR includes all dependencies except Spark (which is provided by EMR).

## Running Tests

The main project tests exclude the bulk-writer module to avoid requiring the cassandra-analytics build:

```bash
# Run main project tests (works without cassandra-analytics)
./gradlew :test

# Run specific test class
./gradlew :test --tests "EMRSparkServiceTest"
```

## Testing with a Live Cluster

### Using bin/submit-direct-bulk-writer

This script automates the bulk writer test workflow:

```bash
bin/submit-direct-bulk-writer [rowCount] [parallelism] [replicationFactor]
```

The script:
1. Builds the bulk-writer JAR
2. Reads cluster configuration from `state.json`
3. Uploads JAR to S3
4. Submits the Spark job to EMR
5. Waits for completion
6. **Automatically downloads and displays logs on failure**
7. Verifies data on success

Example:
```bash
# Default: 1000 rows, 4 partitions, RF=1
bin/submit-direct-bulk-writer

# Custom parameters
bin/submit-direct-bulk-writer 100000 10 3
```

### Manual Spark Job Submission

```bash
# Submit with wait (blocks until completion)
easy-db-lab spark submit \
    --jar bulk-writer/build/libs/bulk-writer-*.jar \
    --main-class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
    --args "host1,host2 keyspace table datacenter 1000 4 1" \
    --wait

# Submit without wait (returns immediately)
easy-db-lab spark submit \
    --jar bulk-writer/build/libs/bulk-writer-*.jar \
    --main-class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
    --args "..."
```

## Debugging Failed Jobs

When a Spark job fails, easy-db-lab automatically:

1. Queries Victoria Logs for step-specific logs
2. Downloads stderr and stdout from S3
3. Displays the last 100 lines of stderr

### Manual Log Retrieval

```bash
# Download logs for a specific step
easy-db-lab spark logs --step-id <step-id>

# Check job status
easy-db-lab spark status --step-id <step-id>

# List recent jobs
easy-db-lab spark jobs
```

### Direct S3 Access

Logs are stored at: `s3://<bucket>/spark/emr-logs/<cluster-id>/steps/<step-id>/`

```bash
# View stderr directly
aws s3 cp s3://<bucket>/spark/emr-logs/<cluster-id>/steps/<step-id>/stderr.gz - | gunzip

# Download all step logs
aws s3 sync s3://<bucket>/spark/emr-logs/<cluster-id>/steps/<step-id>/ ./logs/
```

## EMRSparkService

The `EMRSparkService` class (`providers/aws/EMRSparkService.kt`) handles all Spark/EMR operations:

| Method | Description |
|--------|-------------|
| `submitJob()` | Submits a Spark job to EMR |
| `waitForJobCompletion()` | Polls until job completes, auto-downloads logs on failure |
| `getJobStatus()` | Returns current job state |
| `listJobs()` | Lists recent jobs on the cluster |
| `getStepLogs()` | Downloads specific log type (stdout/stderr) |
| `downloadStepLogs()` | Downloads and displays all logs for a step |

### Polling Configuration

See `Constants.kt` for tunable values:

| Constant | Value | Description |
|----------|-------|-------------|
| `EMR.POLL_INTERVAL_MS` | 5000 | Poll interval during job wait |
| `EMR.MAX_POLL_TIMEOUT_MS` | 4 hours | Maximum wait time |
| `EMR.LOG_INTERVAL_POLLS` | 12 | Log status every N polls (~60s) |
| `EMR.STDERR_TAIL_LINES` | 100 | Lines to display on failure |

## Common Issues

### Build Failure: Missing cassandra-analytics

```
Could not find org.apache.cassandra:cassandra-analytics-core_spark3_2.12:0.3-SNAPSHOT
```

**Solution**: Run `bin/build-cassandra-analytics` to build and install the dependency locally.

### Spark Job Fails with No Logs

EMR logs may take 30-60 seconds to upload to S3 after job completion. The `downloadStepLogs()` method retries automatically. If logs still aren't available:

```bash
# Wait and retry manually
sleep 60
easy-db-lab spark logs --step-id <step-id>
```

### Sidecar Connection Refused

The bulk writer connects to Cassandra Sidecar on port 9043. Ensure:
1. Sidecar is running on Cassandra nodes
2. Security groups allow port 9043 from EMR nodes
3. Correct internal IPs are being used (not public IPs)

## Architecture Notes

### Why Shadow JAR?

The bulk-writer uses the Gradle Shadow plugin to create a fat JAR because:
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
