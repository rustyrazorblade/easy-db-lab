# Spark

easy-db-lab supports provisioning Apache Spark clusters via AWS EMR for analytics workloads.

## Enabling Spark

There are two ways to enable Spark:

### Option 1: During Init (before `up`)

Enable Spark during cluster initialization with the `--spark.enable` flag. The EMR cluster will be created automatically when you run `up`:

```bash
easy-db-lab init --spark.enable
easy-db-lab up
```

#### Init Spark Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `--spark.enable` | Enable Spark EMR cluster | false |
| `--spark.master.instance.type` | Master node instance type | m5.xlarge |
| `--spark.worker.instance.type` | Worker node instance type | m5.xlarge |
| `--spark.worker.instance.count` | Number of worker nodes | 3 |

#### Example with Custom Configuration

```bash
easy-db-lab init \
  --spark.enable \
  --spark.master.instance.type m5.2xlarge \
  --spark.worker.instance.type m5.4xlarge \
  --spark.worker.instance.count 5
```

### Option 2: After `up` (standalone `spark init`)

Add Spark to an existing environment that is already running. This is useful when you forgot to pass `--spark.enable` during `init`, or when you decide to add Spark later:

```bash
easy-db-lab spark init
```

Prerequisites: `easy-db-lab init` and `easy-db-lab up` must have been run first.

#### Spark Init Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `--master.instance.type` | Master node instance type | m5.xlarge |
| `--worker.instance.type` | Worker node instance type | m5.xlarge |
| `--worker.instance.count` | Number of worker nodes | 3 |

#### Example with Custom Configuration

```bash
easy-db-lab spark init \
  --master.instance.type m5.2xlarge \
  --worker.instance.type m5.4xlarge \
  --worker.instance.count 5
```

## Submitting Spark Jobs

Submit JAR-based Spark applications to your EMR cluster:

```bash
easy-db-lab spark submit \
  --jar /path/to/your-app.jar \
  --main-class com.example.YourMainClass \
  --conf spark.easydblab.keyspace=my_keyspace \
  --conf spark.easydblab.table=my_table \
  --wait
```

### Submit Options

| Option | Description | Required |
|--------|-------------|----------|
| `--jar` | Path to JAR file (local path or `s3://` URI) | Yes |
| `--main-class` | Main class to execute | Yes |
| `--conf` | Spark configuration (`key=value`), can be repeated | No |
| `--env` | Environment variable (`KEY=value`), can be repeated | No |
| `--args` | Arguments for the Spark application | No |
| `--wait` | Wait for job completion | No |
| `--name` | Job name (defaults to main class) | No |

When `--jar` is a local path, it is automatically uploaded to the cluster's S3 bucket before submission. When it is an `s3://` URI, it is used directly.

### Using a JAR Already on S3

If your JAR is already on S3 (e.g., from a CI pipeline or a previous upload), pass the S3 URI directly:

```bash
easy-db-lab spark submit \
  --jar s3://my-bucket/jars/your-app.jar \
  --main-class com.example.YourMainClass \
  --conf spark.easydblab.keyspace=my_keyspace \
  --wait
```

This skips the upload step entirely, which is useful for large JARs or when resubmitting the same job.

## Cancelling a Job

Cancel a running or pending Spark job without terminating the cluster:

```bash
easy-db-lab spark stop
```

Without `--step-id`, this cancels the most recent job. To cancel a specific job:

```bash
easy-db-lab spark stop --step-id <step-id>
```

The cancellation uses EMR's `TERMINATE_PROCESS` strategy (SIGKILL). The API is asynchronous — use `spark status` to confirm the job has been cancelled.

## Checking Job Status

### View Recent Jobs

List recent Spark jobs on the cluster:

```bash
easy-db-lab spark jobs
```

Options:

- `--limit` - Maximum number of jobs to display (default: 10)

### Check Specific Job Status

```bash
easy-db-lab spark status --step-id <step-id>
```

Without `--step-id`, shows the status of the most recent job.

Options:

- `--step-id` - EMR step ID to check
- `--logs` - Download step logs (stdout, stderr)

## Retrieving Logs

Download logs for a Spark job:

```bash
easy-db-lab spark logs --step-id <step-id>
```

Logs are automatically decompressed and include:

- `stdout.gz` - Standard output
- `stderr.gz` - Standard error
- `controller.gz` - EMR controller logs

## Architecture

When Spark is enabled, easy-db-lab provisions:

- **EMR Cluster**: Managed Spark cluster with master and worker nodes
- **S3 Integration**: Logs stored at `s3://<bucket>/spark/emr-logs/`
- **IAM Roles**: Service and job flow roles for EMR operations
- **Observability**: Each EMR node runs an OTel Collector (host metrics, OTLP forwarding), OTel Java Agent (auto-instrumentation for logs/metrics/traces), and Pyroscope Java Agent (continuous CPU/allocation/lock profiling). All telemetry flows to the control node's observability stack.

### Timeouts and Polling

- **Job Polling Interval**: 5 seconds
- **Maximum Wait Time**: 4 hours
- **Cluster Creation Timeout**: 30 minutes

## Spark with Cassandra

A common use case is running Spark jobs that read from or write to Cassandra. Use the [Spark Cassandra Connector](https://github.com/datastax/spark-cassandra-connector):

```scala
import com.datastax.spark.connector._

val df = spark.read
  .format("org.apache.spark.sql.cassandra")
  .options(Map("table" -> "my_table", "keyspace" -> "my_keyspace"))
  .load()
```

Ensure your JAR includes the Spark Cassandra Connector dependency and configure the Cassandra host in your Spark application.

## Spark Modules

All Spark job modules live under the `spark/` directory and share unified configuration via `spark.easydblab.*` properties. You can compare performance across implementations by swapping the JAR and main class while keeping the same `--conf` flags.

### Module Overview

| Module | Gradle Path | Main Class | Description |
|--------|-------------|------------|-------------|
| `common` | `:spark:common` | — | Shared config, data generation, CQL setup |
| `bulk-writer-sidecar` | `:spark:bulk-writer-sidecar` | `DirectBulkWriter` | Cassandra Analytics, direct sidecar transport |
| `bulk-writer-s3` | `:spark:bulk-writer-s3` | `S3BulkWriter` | Cassandra Analytics, S3 staging transport |
| `connector-writer` | `:spark:connector-writer` | `StandardConnectorWriter` | Standard Spark Cassandra Connector |
| `connector-read-write` | `:spark:connector-read-write` | `KeyValuePrefixCount` | Read→transform→write example |

### Building

#### Pre-build Cassandra Analytics (one-time, for bulk-writer modules)

The cassandra-analytics library requires JDK 11 to build:

```bash
bin/build-cassandra-analytics
```

Options:
- `--force` - Rebuild even if JARs exist
- `--branch <branch>` - Use a specific branch (default: trunk)

#### Build JARs

```bash
# Build all Spark modules
./gradlew :spark:bulk-writer-sidecar:shadowJar :spark:bulk-writer-s3:shadowJar \
  :spark:connector-writer:shadowJar :spark:connector-read-write:shadowJar

# Or build individually
./gradlew :spark:bulk-writer-sidecar:shadowJar
./gradlew :spark:connector-writer:shadowJar
```

### Usage

All modules use the same `--conf` properties for easy comparison.

#### Direct Bulk Writer (Sidecar)

```bash
easy-db-lab spark submit \
  --jar spark/bulk-writer-sidecar/build/libs/bulk-writer-sidecar-*.jar \
  --main-class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
  --conf spark.easydblab.contactPoints=host1,host2,host3 \
  --conf spark.easydblab.keyspace=bulk_test \
  --conf spark.easydblab.localDc=us-west-2 \
  --conf spark.easydblab.rowCount=1000000 \
  --wait
```

#### S3 Bulk Writer

```bash
easy-db-lab spark submit \
  --jar spark/bulk-writer-s3/build/libs/bulk-writer-s3-*.jar \
  --main-class com.rustyrazorblade.easydblab.spark.S3BulkWriter \
  --conf spark.easydblab.contactPoints=host1,host2,host3 \
  --conf spark.easydblab.keyspace=bulk_test \
  --conf spark.easydblab.localDc=us-west-2 \
  --conf spark.easydblab.s3.bucket=my-bucket \
  --conf spark.easydblab.rowCount=1000000 \
  --wait
```

#### Standard Connector Writer

```bash
easy-db-lab spark submit \
  --jar spark/connector-writer/build/libs/connector-writer-*.jar \
  --main-class com.rustyrazorblade.easydblab.spark.StandardConnectorWriter \
  --conf spark.easydblab.contactPoints=host1,host2,host3 \
  --conf spark.easydblab.keyspace=bulk_test \
  --conf spark.easydblab.localDc=us-west-2 \
  --conf spark.easydblab.rowCount=1000000 \
  --wait
```

#### Convenience Script

The `bin/spark-bulk-write` script handles JAR lookup, host resolution, and health checks:

```bash
# From a cluster directory
spark-bulk-write direct --rows 10000
spark-bulk-write s3 --rows 1000000 --parallelism 20
spark-bulk-write connector --keyspace myks --table mytable
```

### Configuration Properties

All modules share these properties via `spark.easydblab.*`:

| Property | Description | Default |
|----------|-------------|---------|
| `spark.easydblab.contactPoints` | Comma-separated database hosts | Required |
| `spark.easydblab.keyspace` | Target keyspace | Required |
| `spark.easydblab.table` | Target table | `data_<timestamp>` |
| `spark.easydblab.localDc` | Local datacenter name | Required |
| `spark.easydblab.rowCount` | Number of rows to write | 1000000 |
| `spark.easydblab.parallelism` | Spark partitions for generation | 10 |
| `spark.easydblab.partitionCount` | Cassandra partitions to distribute across | 10000 |
| `spark.easydblab.replicationFactor` | Keyspace replication factor | 3 |
| `spark.easydblab.skipDdl` | Skip keyspace/table creation | false |
| `spark.easydblab.compaction` | Compaction strategy | (default) |
| `spark.easydblab.s3.bucket` | S3 bucket (S3 mode only) | Required for S3 |
| `spark.easydblab.s3.endpoint` | S3 endpoint override | AWS S3 |

### Table Schema

The test data generators produce this schema:

```sql
CREATE TABLE <keyspace>.<table> (
    partition_id bigint,
    sequence_id bigint,
    course blob,
    marks bigint,
    PRIMARY KEY ((partition_id), sequence_id)
);
```
