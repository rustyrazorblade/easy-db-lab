# S3 Bulk Writer

Bulk write data to Cassandra using S3 as a staging layer. This module uses the cassandra-analytics library with S3_COMPAT transport mode to enable efficient large-scale data loading.

## Overview

The S3 bulk write process works as follows:

1. **Spark generates SSTables** from source data using cassandra-analytics
2. **Bundles SSTables** into ZIP files with manifest.json (checksums + token ranges)
3. **Uploads to S3** via the EasyDbLabStorageExtension
4. **Pushes notifications** to Cassandra Sidecar REST API (not polling)
5. **Sidecar downloads** bundles, validates checksums, filters by token ranges
6. **Imports to Cassandra** using native SSTable import APIs

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           EMR Cluster (Spark)                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌───────────────┐         ┌──────────────────────────┐                │
│  │  Source Data  │────────▶│  cassandra-analytics     │                │
│  │  (DataFrame)  │         │  - Generate SSTables     │                │
│  └───────────────┘         │  - Create bundles (ZIP)  │                │
│                            │  - Calculate checksums   │                │
│                            │  - Assign token ranges   │                │
│                            └───────────┬──────────────┘                │
│                                        │                                │
│                            ┌───────────▼──────────────┐                │
│                            │  EasyDbLabStorage        │                │
│                            │  Extension               │                │
│                            │  - IAM credentials       │                │
│                            │  - Region detection      │                │
│                            └───────────┬──────────────┘                │
└────────────────────────────────────────┼───────────────────────────────┘
                                         │
                                         │ (3) Upload bundles
                                         ▼
                            ┌─────────────────────────┐
                            │      S3 Bucket          │
                            │  easy-db-lab-data-*     │
                            │                         │
                            │  bulkwrite/             │
                            │  ├─ bundle-001.zip      │
                            │  ├─ bundle-002.zip      │
                            │  └─ manifest.json       │
                            └────────┬────────────────┘
                                     │
                                     │ (4) Push notification (HTTP POST)
                                     │
        ┌────────────────────────────┼────────────────────────────┐
        │                            ▼                            │
        │  ┌─────────────────────────────────────────────┐        │
        │  │        Cassandra Sidecar (port 9043)        │        │
        │  │  - Receive notification                     │        │
        │  │  - Download from S3                         │        │
        │  │  - Validate checksums                       │        │
        │  │  - Filter by token ranges                   │        │
        │  └────────────────┬────────────────────────────┘        │
        │                   │                                     │
        │                   │ (6) Import SSTables                 │
        │                   ▼                                     │
        │  ┌──────────────────────────────────────────┐           │
        │  │          Cassandra Node                  │           │
        │  │  - Native SSTable import                 │           │
        │  │  - Data immediately available            │           │
        │  └──────────────────────────────────────────┘           │
        │                                                         │
        │              Cassandra Cluster (VPC)                    │
        └─────────────────────────────────────────────────────────┘

Key Benefits:
- No direct network connection needed between Spark and Cassandra
- S3 provides durability and staging for large datasets
- Push-based notification (not polling) for low latency
- Token range filtering ensures data goes to correct nodes
- Checksum validation guarantees data integrity
```

## Requirements

### Infrastructure (Provisioned by easy-db-lab)

- **EMR Cluster** with `EasyDBLabEMREC2Role` instance profile
  - Instance profile provides S3 access credentials via IMDS
  - No manual credential configuration needed
- **S3 Bucket** (`clusterState.dataBucket`) with appropriate permissions
  - Typically: `easy-db-lab-data-{clusterId}`
- **Cassandra Nodes** running Sidecar on port 9043
  - Sidecar must be running before job submission

### Dependencies

- AWS SDK v2: `auth` and `regions` modules (included in shadow JAR)
- cassandra-analytics 0.3.0-SNAPSHOT (included in shadow JAR)

## Configuration

### Required Spark Configuration Properties

```bash
--conf spark.easydblab.contactPoints=<cassandra-hosts>  # Comma-separated Sidecar contact points
--conf spark.easydblab.keyspace=<keyspace>               # Target keyspace
--conf spark.easydblab.localDc=<datacenter>              # Local datacenter name
--conf spark.easydblab.s3.bucket=<bucket-name>           # S3 bucket for staging (REQUIRED for S3 transport)
```

### Optional Properties

```bash
--conf spark.easydblab.table=<table>                     # Table name (default: data_<timestamp>)
--conf spark.easydblab.rowCount=<count>                  # Number of rows (default: 1000000)
--conf spark.easydblab.parallelism=<num>                 # Spark partitions (default: 10)
--conf spark.easydblab.partitionCount=<count>            # Cassandra partitions (default: 10000)
--conf spark.easydblab.replicationFactor=<rf>            # Replication factor (default: 3)
--conf spark.easydblab.skipDdl=true|false                # Skip DDL creation (default: false)
--conf spark.easydblab.compaction=<strategy>             # Compaction strategy
--conf spark.easydblab.s3.endpoint=<url>                 # Custom S3 endpoint (for testing or S3-compatible storage)
```

**Note**: Property keys and transport mode constants are defined as public constants to prevent typos and improve discoverability. Configuration properties are in `SparkJobConfig` (e.g., `PROP_S3_BUCKET`, `TRANSPORT_S3_COMPAT`), and the extension class name is in `EasyDbLabStorageExtension.EXTENSION_CLASS_NAME`.

## Usage Example

### Submit to EMR Cluster

```bash
spark-submit \
  --class com.rustyrazorblade.easydblab.spark.S3BulkWriter \
  --master yarn \
  --deploy-mode cluster \
  --num-executors 10 \
  --executor-cores 4 \
  --executor-memory 8g \
  --driver-memory 4g \
  --conf spark.easydblab.contactPoints=10.0.1.1:9043,10.0.1.2:9043,10.0.1.3:9043 \
  --conf spark.easydblab.keyspace=bulk_test \
  --conf spark.easydblab.localDc=datacenter1 \
  --conf spark.easydblab.s3.bucket=easy-db-lab-data-abc123 \
  --conf spark.easydblab.rowCount=10000000 \
  --conf spark.easydblab.parallelism=20 \
  s3://easy-db-lab-bucket/spark/bulk-writer-s3-12.jar
```

### With Custom S3 Endpoint (for testing)

```bash
spark-submit \
  --class com.rustyrazorblade.easydblab.spark.S3BulkWriter \
  --master yarn \
  --deploy-mode cluster \
  --conf spark.easydblab.contactPoints=10.0.1.1:9043,10.0.1.2:9043 \
  --conf spark.easydblab.keyspace=bulk_test \
  --conf spark.easydblab.localDc=datacenter1 \
  --conf spark.easydblab.s3.bucket=test-bucket \
  --conf spark.easydblab.s3.endpoint=https://s3.custom.endpoint \
  --conf spark.easydblab.rowCount=1000000 \
  bulk-writer-s3-12.jar
```

## How It Works

### Credential Auto-Detection

The `EasyDbLabStorageExtension` uses AWS SDK's `DefaultCredentialsProvider` to automatically obtain credentials:

1. **EC2 instance profile via IMDS** (recommended for EMR) - automatically configured by easy-db-lab
2. **Environment variables**: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
3. **System properties**: `aws.accessKeyId`, `aws.secretAccessKey`
4. **AWS profile configuration**: `~/.aws/credentials`

**No manual credentials required** in Spark config. If credential resolution fails, the extension provides a clear error message listing all four credential sources to check.

### Region Auto-Detection

The extension uses AWS SDK's `DefaultAwsRegionProviderChain` to detect the AWS region:

1. `AWS_REGION` environment variable
2. `aws.region` system property
3. EC2 instance metadata (automatic on EMR)
4. AWS profile configuration (`~/.aws/config`)

If no region can be detected, the extension fails fast with a clear error message listing the configuration options.

### Lifecycle Logging

The extension logs lifecycle events to track progress:

- **INFO**: Initialization, job start, upload completion, stage success, import success
- **DEBUG**: Individual bundle uploads (size, S3 path), credential resolution details
- **ERROR**: Stage failures, import failures, job failures with stack traces

Example log output:
```
[job-123] Initialized EasyDbLabStorageExtension (isDriver: true)
[job-123] S3 Bucket: easy-db-lab-data-abc123
[job-123] AWS Region: us-west-2
[job-123] AWS credentials resolved successfully (session token: present)
[job-123] Bulk write transport started (125ms)
[job-123] Uploaded bundle: s3://easy-db-lab-data-abc123/bulkwrite/bundle-001.zip (52428800 bytes)
[job-123] All 10 SSTable bundles uploaded (10000000 rows) in 45000ms
[job-123] Cluster 'default' staging succeeded in 60000ms
[job-123] Cluster 'default' import succeeded in 120000ms
[job-123] Bulk write job SUCCEEDED (total: 185000ms)
```

## Troubleshooting

### Error: "S3 bucket name cannot be empty"

**Cause**: The `spark.easydblab.s3.bucket` property was not provided or is empty.

**Solution**: Add `--conf spark.easydblab.s3.bucket=<bucket-name>` to your spark-submit command.

**Note**: Bucket names must be 3-63 characters. The AWS SDK will validate detailed format rules (lowercase, DNS-compliant, etc.) during S3 operations.

### Error: "Failed to resolve AWS credentials"

**Cause**: None of the AWS credential sources are configured.

**Error Message Shows**:
```
Failed to resolve AWS credentials. Ensure one of the following is configured:
  1. EC2 instance profile (IMDS) - recommended for EMR
  2. Environment variables: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
  3. System properties: aws.accessKeyId, aws.secretAccessKey
  4. AWS profile configuration (~/.aws/credentials)
```

**Solution**:
1. **On EMR**: Verify cluster has `EasyDBLabEMREC2Role` instance profile attached
2. **Local testing**: Set `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` environment variables
3. Check IAM role has `s3:*` permissions on the data bucket
4. Verify instance profile is attached to EMR nodes

### Error: "Unable to detect AWS region"

**Cause**: No AWS region is configured in any of the standard locations.

**Error Message Shows**:
```
Unable to detect AWS region. Set AWS_REGION environment variable or
aws.region system property, or ensure EC2 instance metadata is accessible.
```

**Solution**:
1. **On EMR**: Region is automatically detected from EC2 metadata (no action needed)
2. **Local testing**: Set `AWS_REGION` environment variable (e.g., `export AWS_REGION=us-west-2`)
3. **Alternative**: Set system property `--conf spark.driver.extraJavaOptions="-Daws.region=us-west-2"`

### Error: "Access Denied" during S3 upload

**Cause**: Instance profile lacks S3 write permissions.

**Solution**:
1. Verify S3 bucket policy allows `s3:*` from `EasyDBLabEMREC2Role`
2. Check bucket is in the same AWS account as EMR cluster
3. Verify bucket exists and is accessible from EMR region

### Error: "Connection refused" to Sidecar

**Cause**: Cassandra Sidecar is not running or unreachable.

**Solution**:
1. Verify Cassandra Sidecar is running on port 9043: `curl http://<cassandra-host>:9043/api/v1/health`
2. Check security groups allow EMR → Cassandra on port 9043
3. Ensure contact points use correct IP addresses (private IPs if in same VPC)

### No bundles appearing in S3

**Cause**: Write options not configured correctly or extension not initialized.

**Solution**:
1. Check Spark logs for extension initialization: `Initialized EasyDbLabStorageExtension`
2. Verify `data_transport=S3_COMPAT` in write options
3. Verify `data_transport_extension_class` is set correctly
4. Check for earlier exceptions during extension initialization

### Bundles uploaded but not imported

**Cause**: Sidecar didn't receive push notification or failed to download.

**Solution**:
1. Check Spark logs for `Cluster 'default' staging succeeded` message
2. Check Sidecar logs for download attempts and errors
3. Verify Sidecar can access S3 bucket (check Sidecar IAM role)
4. Verify token ranges match between SSTables and Cassandra ring

## Build

Build the shadow JAR:

```bash
./gradlew :spark:bulk-writer-s3:shadowJar
```

Output: `spark/bulk-writer-s3/build/libs/bulk-writer-s3-12.jar`

## Architecture

### Single-Datacenter Mode (Current)

- Uses a single default cluster ID for read/write configurations
- Write and read from the same S3 bucket and region
- Suitable for single-region deployments

### Multi-Datacenter Support (Future)

Multi-DC support will use **S3 Cross-Region Replication (CRR)** to replicate data from a single source bucket to regional replica buckets.

**Architecture:**
```
┌──────────────────────────────────────────────────────────────────┐
│                      EMR Cluster (Spark)                         │
│                                                                  │
│  Spark Job ───▶ Write SSTables ───▶ Upload to Source Bucket     │
└────────────────────────────┬─────────────────────────────────────┘
                             │
                             ▼
                ┌─────────────────────────┐
                │   Source S3 Bucket      │
                │   (us-east-1)           │
                │   easy-db-lab-data-*    │
                └─────────────────────────┘
                             │
                             │ AWS S3 Cross-Region Replication
                             │ (automatic, managed by AWS)
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ Replica Bucket  │  │ Replica Bucket  │  │ Replica Bucket  │
│  (us-west-2)    │  │  (eu-west-1)    │  │  (ap-south-1)   │
└────────┬────────┘  └────────┬────────┘  └────────┬────────┘
         │                    │                    │
         ▼                    ▼                    ▼
  ┌─────────────┐      ┌─────────────┐      ┌─────────────┐
  │  Cassandra  │      │  Cassandra  │      │  Cassandra  │
  │  DC West    │      │  DC Europe  │      │  DC Asia    │
  └─────────────┘      └─────────────┘      └─────────────┘
```

**Benefits:**
- Single source bucket configuration (simpler)
- AWS manages replication automatically
- No code changes needed in Spark job
- Each datacenter reads from its regional replica bucket (lower latency downloads)

**Configuration (Future):**
```bash
# Single source bucket for write
--conf spark.easydblab.s3.bucket=easy-db-lab-data-12345

# Replica buckets auto-configured by easy-db-lab based on cluster topology
# Each DC's Sidecar configured with its regional replica bucket endpoint
```

**Implementation Notes:**
- S3 CRR buckets must be pre-configured by easy-db-lab provisioning
- Each datacenter's Sidecar is configured with its regional replica bucket
- Spark job only writes to source bucket; AWS handles replication
- The `DEFAULT_CLUSTER_ID = "default"` will remain, as all DCs use the same logical cluster
- Multi-DC support is primarily a configuration change, not a code change

**Trade-offs:**
- ✅ Simple configuration (one bucket)
- ✅ AWS manages replication reliability
- ✅ No additional Spark job complexity
- ⚠️ Replication delay (typically seconds to minutes)
- ⚠️ Additional AWS S3 replication costs

## See Also

- [cassandra-analytics documentation](https://github.com/apache/cassandra-analytics)
- [Cassandra Sidecar](https://github.com/apache/cassandra-sidecar)
- [Direct bulk writer](../bulk-writer-sidecar/README.md) (no S3 staging)
- [Spark Cassandra Connector](../connector-writer/README.md) (standard write path)
