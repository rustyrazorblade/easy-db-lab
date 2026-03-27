## Context

The cassandra-analytics library provides a `StorageTransportExtension` interface that Spark jobs must implement to use S3_COMPAT transport mode. This extension is responsible for:
- Providing AWS credentials for S3 access
- Defining S3 bucket and region configuration
- Handling lifecycle events (upload progress, completion, failures)
- Supporting multi-datacenter coordination (required)

The `spark/bulk-writer-s3` module exists to enable bulk writing to multiple Cassandra DCs simultaneously. Each DC is a separate easy-db-lab cluster with its own S3 data bucket. easy-db-lab does not support multi-DC Cassandra clusters; instead, multiple single-DC clusters are used. S3 replication copies SSTable bundles from the primary write bucket to each cluster's own read bucket.

**Current Infrastructure (Already Provisioned):**
- EMR cluster with `EasyDBLabEMREC2Role` instance profile (provides S3 access via IMDS)
- Per-cluster S3 data bucket: `easy-db-lab-data-{clusterId}` (`clusterState.dataBucket`)
- Cassandra nodes with Sidecar running on port 9043
- `SparkJobConfig` already supports `spark.easydblab.s3.bucket` and `spark.easydblab.s3.endpoint`

**How S3 Bulk Write Works:**
1. Spark job generates SSTables from source data
2. Bundles SSTables into ZIP files with `manifest.json` (checksums + token ranges)
3. Uploads bundles to S3 write bucket via extension-provided credentials
4. S3 replication copies bundles to each DC's read bucket
5. **Pushes** slice metadata to each cluster's Sidecar REST API (not polling)
6. Each Sidecar downloads bundles from its own bucket, validates checksums, filters by token ranges, imports to Cassandra

## Goals / Non-Goals

**Goals:**
- Implement `EasyDbLabStorageExtension` to enable S3_COMPAT transport
- Use existing EMR instance profile for credentials (no manual credential management)
- Support multi-datacenter bulk writes via cassandra-analytics coordinated write mode
- Follow cassandra-analytics lifecycle contract for proper logging/monitoring

**Non-Goals:**
- S3 cross-region replication setup (infrastructure concern, not Spark job)
- Custom retry logic for S3 uploads (cassandra-analytics handles this)
- Sidecar deployment or configuration (already handled by easy-db-lab provisioning)

## Decisions

### 1. Credential Strategy: AWS SDK DefaultCredentialsProvider

**Decision:** Use AWS SDK's `DefaultCredentialsProvider` to auto-detect credentials from EMR instance profile via IMDS.

**Rationale:**
- EMR nodes already have `EasyDBLabEMREC2Role` instance profile configured
- No need to pass explicit credentials via Spark config (security risk)
- Follows AWS best practices for EC2-based applications
- Handles session token rotation automatically

**Alternatives Considered:**
- **Pass credentials via --conf**: Security risk (credentials in logs/history), requires rotation management
- **STS AssumeRole**: Unnecessary complexity for single-account scenario, adds latency

### 2. S3 Bucket Configuration: Read from SparkJobConfig

**Decision:** Extension reads `spark.easydblab.s3.bucket` from SparkConf for the write bucket, and `spark.easydblab.s3.readBuckets` for per-DC read buckets.

**Rationale:**
- `SparkJobConfig` already has the write bucket property and validation
- Write bucket maps to `clusterState.dataBucket` (provisioned by easy-db-lab)
- Read buckets are separate per-DC cluster data buckets, populated via S3 replication

**Configuration format for read buckets:**
```
spark.easydblab.s3.readBuckets=dc1:easy-db-lab-data-cluster1,dc2:easy-db-lab-data-cluster2
```
Comma-separated `clusterId:bucketName` pairs. Each `clusterId` is the identifier cassandra-analytics uses to route to the correct Cassandra cluster during coordinated writes.

**Alternatives Considered:**
- **JSON config string**: More expressive but harder to pass via `--conf`; reserved name `coordinated_write_config` was a design artifact from when multi-DC was incorrectly deferred
- **Individual properties per DC**: Requires iterating unknown property keys, harder to validate

### 3. Region Detection: EC2 Instance Metadata

**Decision:** Auto-detect region using AWS SDK's `DefaultAwsRegionProviderChain`. All DCs use the same region (same AWS account).

**Rationale:**
- EMR cluster region matches S3 bucket region (both provisioned in same AWS region)
- Eliminates need for explicit region configuration
- SDK chain checks: env vars → system properties → profile → EC2 metadata

**Alternatives Considered:**
- **Per-DC region config**: Not needed; easy-db-lab clusters run in a single AWS account/region

### 4. Multi-DC Mode: Coordinated Write with Per-DC Read Buckets

**Decision:** Use cassandra-analytics coordinated write mode. The extension parses `spark.easydblab.s3.readBuckets` into a `Map<String, StorageAccessConfiguration>` — one entry per DC — and uses the coordinated-write `StorageTransportConfiguration` constructor.

**Rationale:**
- This is the core use case of the bulk-writer-s3 module
- Each DC cluster reads SSTables from its own S3 bucket (via replication from the write bucket)
- cassandra-analytics `StorageTransportConfiguration` has a dedicated constructor for this: `(prefix, objectTags, writeAccessConfig, Map<clusterId, readAccessConfig>)`

**Implementation:**
```java
// Parse spark.easydblab.s3.readBuckets=dc1:bucket1,dc2:bucket2
Map<String, StorageAccessConfiguration> readConfigs = new HashMap<>();
for (String entry : readBucketsConfig.split(",")) {
    String[] parts = entry.split(":", 2);
    String clusterId = parts[0].trim();
    String readBucket = parts[1].trim();
    readConfigs.put(clusterId, new StorageAccessConfiguration(region, readBucket, credentials));
}

return new StorageTransportConfiguration(
    KEY_PREFIX,
    ImmutableMap.of(),
    new StorageAccessConfiguration(region, writeBucket, credentials),
    readConfigs
);
```

### 5. Lifecycle Logging: SLF4J

**Decision:** Log lifecycle events (onObjectPersisted, onStageSucceeded, etc.) using SLF4J logger.

**Rationale:**
- Spark jobs already use SLF4J for logging
- EMR captures Spark logs automatically
- Provides visibility into upload progress and failures
- No need for custom event bus (this is Spark-side, not CLI-side)

**Log Levels:**
- `INFO`: Job lifecycle (start, completion, stage success)
- `DEBUG`: Per-bundle upload progress
- `ERROR`: Import failures, job failures

### 6. Dependency Management: AWS SDK v2 Minimal Modules

**Decision:** Add only required AWS SDK v2 modules: `auth` and `regions`.

**Rationale:**
- Minimize JAR size (shadow JAR already large with cassandra-analytics)
- `DefaultCredentialsProvider` in `auth` module
- `DefaultAwsRegionProviderChain` in `regions` module
- S3 client already available via cassandra-analytics transitive dependencies

## Risks / Trade-offs

**[Risk]** Instance profile credentials fail or are missing on EMR
→ **Mitigation:** Fail fast during `initialize()` with clear error message. EMR provisioning already validates IAM role setup.

**[Risk]** S3 bucket doesn't exist or lacks permissions
→ **Mitigation:** cassandra-analytics will fail on first upload with S3 error.

**[Risk]** Sidecar not running or unreachable when Spark sends notifications
→ **Mitigation:** cassandra-analytics has built-in retry logic for Sidecar API calls. Document that Sidecar must be running before job submission.

**[Risk]** S3 replication lag: write bucket has bundles but read bucket doesn't yet
→ **Mitigation:** cassandra-analytics Sidecar import uses polling/retry. Replication lag is a configuration concern (S3 replication SLA), not handled in the extension.

**[Trade-off]** Auto-detected region could mismatch bucket region in cross-region scenarios
→ **Accepted:** easy-db-lab provisions EMR and S3 in same region. Cross-region use case not currently supported. Future: add optional `spark.easydblab.s3.region` override.

**[Trade-off]** Extension logs only to SLF4J, not easy-db-lab event bus
→ **Accepted:** This is a Spark job running on EMR, not a CLI command. EMR captures Spark logs. CLI-side progress tracking requires separate tooling (e.g., `spark jobs` command polling EMR step status).
