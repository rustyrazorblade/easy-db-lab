# Spark

easy-db-lab supports provisioning Apache Spark clusters via AWS EMR for analytics workloads.

## Enabling Spark

Spark is enabled during cluster initialization with the `--spark.enable` flag:

```bash
easy-db-lab init --spark.enable
```

### Spark Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `--spark.enable` | Enable Spark EMR cluster | false |
| `--spark.master.instance.type` | Master node instance type | m5.xlarge |
| `--spark.worker.instance.type` | Worker node instance type | m5.xlarge |
| `--spark.worker.instance.count` | Number of worker nodes | 3 |

### Example with Custom Configuration

```bash
easy-db-lab init \
  --spark.enable \
  --spark.master.instance.type m5.2xlarge \
  --spark.worker.instance.type m5.4xlarge \
  --spark.worker.instance.count 5
```

## Submitting Spark Jobs

Submit JAR-based Spark applications to your EMR cluster:

```bash
easy-db-lab spark submit \
  --jar /path/to/your-app.jar \
  --main-class com.example.YourMainClass \
  --args "arg1 arg2"
```

### Submit Options

| Option | Description | Required |
|--------|-------------|----------|
| `--jar` | Path to JAR file (local or s3://) | Yes |
| `--main-class` | Main class to execute | Yes |
| `--args` | Arguments for the Spark application | No |
| `--wait` | Wait for job completion | No |
| `--name` | Job name (defaults to main class) | No |

### Using S3 for JARs

You can upload your JAR to S3 and reference it directly:

```bash
# Upload JAR to cluster S3 bucket
aws s3 cp your-app.jar s3://your-bucket/spark/your-app.jar

# Submit using S3 path
easy-db-lab spark submit \
  --jar s3://your-bucket/spark/your-app.jar \
  --main-class com.example.YourMainClass \
  --wait
```

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

## Bulk Writer

The `bulk-writer` subproject provides high-performance bulk data loading into Cassandra using [Apache Cassandra Analytics](https://github.com/apache/cassandra-analytics). It generates SSTables directly and imports them via Cassandra Sidecar.

### Transport Modes

| Mode | Class | Use Case |
|------|-------|----------|
| Direct | `DirectBulkWriter` | Lower latency, direct sidecar connection |
| S3 | `S3BulkWriter` | Large datasets, network isolation, S3 staging |

### Building the Bulk Writer

#### Step 1: Pre-build Cassandra Analytics (one-time)

The cassandra-analytics library requires JDK 11 to build. A Docker-based script handles this:

```bash
bin/build-cassandra-analytics
```

This script:

1. Clones `apache/cassandra-analytics` to `.cassandra-analytics/`
2. Builds inside Docker with JDK 11
3. Copies JARs to `bulk-writer/libs/`

Options:

- `--force` - Rebuild even if JARs exist
- `--branch <branch>` - Use a specific branch (default: trunk)

**Note**: The `.cassandra-analytics/` directory and `bulk-writer/libs/*.jar` are gitignored. Each developer runs this once locally.

#### Step 2: Build the Bulk Writer JAR

```bash
./gradlew :bulk-writer:jar
```

Output: `bulk-writer/build/libs/bulk-writer-*.jar` (~140MB fat JAR)

### Usage

#### Direct Mode

Writes SSTables directly to Cassandra via Sidecar:

```bash
easy-db-lab spark submit \
  --jar bulk-writer/build/libs/bulk-writer-*.jar \
  --main-class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
  --args "host1,host2,host3 mykeyspace mytable datacenter1 1000000 10" \
  --wait
```

#### S3 Mode

Writes SSTables to S3, then Sidecar imports them:

```bash
S3_BUCKET=my-bucket easy-db-lab spark submit \
  --jar bulk-writer/build/libs/bulk-writer-*.jar \
  --main-class com.rustyrazorblade.easydblab.spark.S3BulkWriter \
  --args "host1,host2,host3 mykeyspace mytable datacenter1 1000000 10" \
  --wait
```

#### Arguments

```
<sidecar-hosts> <keyspace> <table> <datacenter> [rowCount] [parallelism]
```

| Argument | Description | Default |
|----------|-------------|---------|
| sidecar-hosts | Comma-separated Sidecar host list | Required |
| keyspace | Target Cassandra keyspace | Required |
| table | Target Cassandra table | Required |
| datacenter | Local datacenter name | Required |
| rowCount | Number of rows to generate | 1000000 |
| parallelism | Spark partitions | 10 |

### Test Script

A minimal test script is provided:

```bash
bin/test-spark-bulk-writer
```

This script:

1. Sources `env.sh` (for SSH config)
2. Builds the JAR
3. Gets hosts from `easy-db-lab hosts`
4. Gets datacenter from `state.json`
5. Creates a test keyspace/table via SSH + cqlsh
6. Submits a DirectBulkWriter job
7. Verifies data was written

**Note**: The script sources `env.sh` which wraps `ssh` to use the generated `sshConfig` file. This enables SSH access to cluster nodes.

### GitHub Actions / CI

The Docker-based build works in CI environments:

```yaml
- name: Build Cassandra Analytics
  run: bin/build-cassandra-analytics

- name: Build Bulk Writer
  run: ./gradlew :bulk-writer:jar
```

GitHub Actions runners include Docker, so no additional setup is needed.

### Table Schema

The bulk writer generates test data with this schema:

```sql
CREATE TABLE <keyspace>.<table> (
    id bigint PRIMARY KEY,
    course blob,
    marks bigint
);
```
