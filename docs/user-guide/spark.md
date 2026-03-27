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

## Bulk Write Implementations

easy-db-lab provides three different implementations for bulk writing data to Cassandra, each with different characteristics. All three use the same configuration properties (`spark.easydblab.*`) so you can easily compare performance by just swapping the JAR and main class.

### Implementation Comparison

| Implementation | Transport | Use Case                                           | Prerequisites                          |
|----------------|-----------|----------------------------------------------------|----------------------------------------|
| **Direct (Sidecar)** | DIRECT | Low latency, direct network path, single DC     | Sidecar running, network connectivity  |
| **S3 Staging** | S3_COMPAT | Large datasets, multi-dc                           | S3 bucket, IAM permissions             |
| **Connector** | CQL | Standard writes, compatibility                            | Cassandra native protocol              |

### Direct Bulk Writer (Sidecar Transport)

Streams SSTables directly from Spark to Cassandra nodes via the Sidecar REST API on port 9043.

**How it works:**
1. Spark generates SSTables from source data
2. SSTables are streamed directly to Sidecar endpoints
3. Sidecar validates and imports SSTables into Cassandra

**When to use:**
- Direct network connectivity between Spark and Cassandra
- Lower latency requirements
- Smaller to medium datasets

**Limitations:**
- Requires network connectivity from EMR to Cassandra on port 9043
- Streaming backpressure if Sidecar can't keep up

### S3 Bulk Writer (S3 Staging Transport)

Stages SSTables in S3, then notifies Cassandra Sidecar to download and import them.

**How it works:**
1. Spark generates SSTables and bundles them into ZIPs with manifests
2. Bundles are uploaded to S3 bucket (provided by easy-db-lab)
3. Spark pushes import notification to Sidecar REST API
4. Sidecar downloads bundles from S3, validates checksums, filters by token ranges
5. Sidecar imports SSTables into Cassandra

**When to use:**
- Large-scale bulk loads (terabytes)
- S3 provides durability and staging for retries
- Multi-DC Cassandra clusters

**Prerequisites:**
- S3 bucket (automatically provisioned by easy-db-lab as `clusterState.dataBucket`)
- EMR instance profile with S3 write permissions (automatically configured)
- Cassandra Sidecar running on port 9043
- For Cassandra 5.x: `storage_compatibility_mode: NONE` in `cassandra.yaml` (the bulk writer requires the new SSTable format)

**Credentials:**
- EMR uses instance profile (IMDS) to write to S3 - no manual credential configuration needed
- AWS region is auto-detected from EC2 metadata
- Sidecar uses its IAM role to read from S3

**Benefits:**
- S3 provides durability for large datasets
- Token range filtering ensures data goes to correct nodes
- Checksum validation guarantees data integrity

### Standard Connector Writer

Uses the DataStax Spark Cassandra Connector to write data via CQL.

**How it works:**
1. Spark generates rows as DataFrames
2. Connector batches writes and sends via Cassandra native protocol (port 9042)
3. Cassandra processes writes through normal write path (memtables → SSTables)

**When to use:**
- Smaller datasets
- Need CDC, triggers, or other write-time features
- Existing Spark Cassandra Connector pipelines

**Limitations:**
- Significantly slower than bulk writers for large datasets (goes through full write path)
- Compaction overhead after writes complete
- More network round-trips

## Spark Modules

All Spark job modules live under the `spark/` directory and share unified configuration via `spark.easydblab.*` properties. You can compare performance across implementations by swapping the JAR and main class while keeping the same `--conf` flags.

### Module Overview

| Module | Gradle Path | Main Class | Transport | Description |
|--------|-------------|------------|-----------|-------------|
| `common` | `:spark:common` | — | — | Shared config, data generation, CQL setup |
| `bulk-writer-sidecar` | `:spark:bulk-writer-sidecar` | `DirectBulkWriter` | DIRECT | Streams SSTables directly to Sidecar |
| `bulk-writer-s3` | `:spark:bulk-writer-s3` | `S3BulkWriter` | S3_COMPAT | Stages SSTables in S3, imports via Sidecar |
| `connector-writer` | `:spark:connector-writer` | `StandardConnectorWriter` | CQL | Standard writes via Cassandra native protocol |
| `connector-read-write` | `:spark:connector-read-write` | `KeyValuePrefixCount` | CQL | Read→transform→write example |

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

Streams SSTables directly to Cassandra Sidecar endpoints:

```bash
easy-db-lab spark submit \
  --jar spark/bulk-writer-sidecar/build/libs/bulk-writer-sidecar-*.jar \
  --main-class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
  --conf spark.easydblab.contactPoints=host1:9043,host2:9043,host3:9043 \
  --conf spark.easydblab.keyspace=bulk_test \
  --conf spark.easydblab.localDc=us-west-2 \
  --conf spark.easydblab.rowCount=1000000 \
  --wait
```

**Note:** Contact points should include the Sidecar port (9043).

#### S3 Bulk Writer

Stages SSTables in S3, then imports via Sidecar:

```bash
# Get the S3 bucket from cluster state
S3_BUCKET=$(jq -r '.dataBucket' state.json)

easy-db-lab spark submit \
  --jar spark/bulk-writer-s3/build/libs/bulk-writer-s3-*.jar \
  --main-class com.rustyrazorblade.easydblab.spark.S3BulkWriter \
  --conf spark.easydblab.contactPoints=host1:9043,host2:9043,host3:9043 \
  --conf spark.easydblab.keyspace=bulk_test \
  --conf spark.easydblab.localDc=us-west-2 \
  --conf spark.easydblab.s3.bucket=$S3_BUCKET \
  --conf spark.easydblab.rowCount=10000000 \
  --conf spark.easydblab.parallelism=20 \
  --wait
```

**Optional S3 endpoint override** (for testing with LocalStack or S3-compatible storage):

```bash
easy-db-lab spark submit \
  --jar spark/bulk-writer-s3/build/libs/bulk-writer-s3-*.jar \
  --main-class com.rustyrazorblade.easydblab.spark.S3BulkWriter \
  --conf spark.easydblab.contactPoints=host1:9043,host2:9043,host3:9043 \
  --conf spark.easydblab.keyspace=bulk_test \
  --conf spark.easydblab.localDc=us-west-2 \
  --conf spark.easydblab.s3.bucket=test-bucket \
  --conf spark.easydblab.s3.endpoint=https://s3.custom.endpoint \
  --conf spark.easydblab.rowCount=1000000 \
  --wait
```

**Credentials:** The EMR instance profile (`EasyDBLabEMREC2Role`) provides S3 access automatically - no manual configuration needed.

#### Standard Connector Writer

Standard CQL writes via Cassandra native protocol:

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

**Note:** Contact points for the connector use the Cassandra native protocol port (9042), not Sidecar.

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
| `spark.easydblab.skipDdl` | Skip keyspace/table creation (validates they exist) | false |
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

## Troubleshooting

### S3 Bulk Writer Issues

#### "Required property not set: spark.easydblab.s3.bucket"

The S3 bulk writer requires an S3 bucket for staging SSTables. Get the bucket from cluster state:

```bash
jq -r '.dataBucket' state.json
```

Then add it to your submit command:

```bash
--conf spark.easydblab.s3.bucket=<bucket-from-state>
```

#### "Failed to resolve AWS credentials"

The EMR instance profile should provide credentials automatically via IMDS. If this fails:

1. Verify EMR cluster has `EasyDBLabEMREC2Role` instance profile attached
2. Check IAM role has `s3:*` permissions on the data bucket
3. Verify instance metadata service (IMDS) is accessible from EMR nodes

#### "Unable to detect AWS region"

Region is auto-detected from EC2 metadata. This should work automatically on EMR. If it fails, the EMR cluster may not have proper metadata access.

#### "S3 bucket name must be between 3 and 63 characters"

Bucket names must follow AWS S3 naming rules (3-63 characters, lowercase, DNS-compliant). Verify the bucket name in `state.json`.

#### Job succeeds but no data imported

Check Cassandra 5.x compatibility:

1. Verify `storage_compatibility_mode: NONE` in `cassandra.yaml`
2. Cassandra 5.x defaults to `UPGRADING` mode which uses legacy SSTable format
3. The bulk writer requires `NONE` to use the new SSTable format

To fix:

```bash
echo "storage_compatibility_mode: NONE" >> cassandra.patch.yaml
easy-db-lab cassandra update-config
easy-db-lab cassandra restart
```

#### Bundles uploaded but Sidecar didn't import

1. Check Sidecar is running: `curl http://<cassandra-host>:9043/api/v1/health`
2. Verify Sidecar has S3 read permissions (IAM role)
3. Check Sidecar logs for download or validation errors
4. Verify token ranges match between SSTables and Cassandra ring

### Direct Bulk Writer Issues

#### "Connection refused" to port 9043

1. Verify Cassandra Sidecar is running on all nodes
2. Check security groups allow EMR → Cassandra on port 9043
3. Ensure contact points use correct IP addresses (private IPs if in same VPC)

#### Slow writes / backpressure

The direct transport streams data and can be throttled if Sidecar can't keep up. Consider:

1. Reduce `spark.easydblab.parallelism` to lower write rate
2. Use S3 staging transport for large datasets
3. Check Cassandra disk I/O and compaction status

### Connector Writer Issues

#### "No route to host" or timeout

1. Check security groups allow EMR → Cassandra on port 9042
2. Verify contact points are reachable from EMR
3. Ensure Cassandra native protocol is enabled

#### Slow performance

The connector uses the standard write path (not bulk write). For large datasets, use a bulk writer instead.
