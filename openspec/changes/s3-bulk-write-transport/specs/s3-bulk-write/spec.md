## ADDED Requirements

### Requirement: S3 authentication via instance profile
The StorageTransportExtension SHALL obtain AWS credentials from the EMR instance profile using AWS SDK DefaultCredentialsProvider without requiring explicit credential configuration.

#### Scenario: Successful credential retrieval from instance profile
- **WHEN** the extension initializes on an EMR node with a valid instance profile
- **THEN** the extension obtains valid AWS credentials (access key, secret key, session token) via IMDS

#### Scenario: Missing instance profile
- **WHEN** the extension initializes on a node without an instance profile
- **THEN** the extension throws IllegalArgumentException with a clear error message indicating missing credentials

### Requirement: S3 bucket configuration
The StorageTransportExtension SHALL read the S3 write bucket name from the `spark.easydblab.s3.bucket` Spark configuration property.

#### Scenario: Valid bucket configuration
- **WHEN** the Spark job provides `spark.easydblab.s3.bucket=my-bucket` configuration
- **THEN** the extension configures S3 write operations to use bucket `my-bucket`

#### Scenario: Missing bucket configuration
- **WHEN** the Spark job does not provide `spark.easydblab.s3.bucket` configuration
- **THEN** the extension throws IllegalArgumentException during initialization with a message indicating the missing property

#### Scenario: Custom S3 endpoint configuration
- **WHEN** the Spark job provides `spark.easydblab.s3.endpoint=https://custom.s3.endpoint` configuration
- **THEN** the extension configures S3 operations to use the custom endpoint instead of the default AWS S3 endpoint

### Requirement: AWS region auto-detection
The StorageTransportExtension SHALL automatically detect the AWS region from EC2 instance metadata using AWS SDK DefaultAwsRegionProviderChain.

#### Scenario: Successful region detection
- **WHEN** the extension initializes on an EC2 instance with valid metadata
- **THEN** the extension detects the correct AWS region (e.g., us-west-2) without requiring explicit configuration

#### Scenario: Region detection fallback
- **WHEN** the extension runs in an environment where EC2 metadata is unavailable
- **THEN** the AWS SDK region provider chain falls back to environment variables or system properties

### Requirement: Multi-datacenter coordinated write
The StorageTransportExtension SHALL support writing to multiple Cassandra clusters (one per DC) simultaneously using cassandra-analytics coordinated write mode. Each DC is a separate easy-db-lab cluster with its own S3 bucket. S3 replication copies SSTable bundles from the primary write bucket to each DC's read bucket. The extension MUST configure `StorageTransportConfiguration` with one read bucket per DC so each cluster's Sidecar can import from its own bucket.

This is the primary use case for the bulk-writer-s3 module.

#### Scenario: Multi-DC coordinated write configuration
- **WHEN** the Spark job provides `spark.easydblab.s3.readBuckets=dc1:bucket-dc1,dc2:bucket-dc2`
- **THEN** the extension uses the coordinated-write `StorageTransportConfiguration` constructor
- **AND** configures one `StorageAccessConfiguration` per DC entry, keyed by cluster/DC ID
- **AND** each DC's read bucket name is taken from the config value

#### Scenario: Missing read buckets config fails fast
- **WHEN** the Spark job does not provide `spark.easydblab.s3.readBuckets`
- **THEN** the extension throws IllegalArgumentException during initialization with a message indicating the missing property and its expected format

#### Scenario: Malformed read buckets config fails fast
- **WHEN** `spark.easydblab.s3.readBuckets` contains an entry without a `:` separator (e.g., `dc1bucket1`)
- **THEN** the extension throws IllegalArgumentException during initialization with a clear error describing the expected format

#### Scenario: Single-entry read buckets is valid
- **WHEN** `spark.easydblab.s3.readBuckets=dc1:bucket-dc1` contains exactly one DC
- **THEN** the extension configures a coordinated write with one read configuration keyed by `dc1`

#### Scenario: Storage configuration includes object prefix
- **WHEN** the extension builds storage configuration
- **THEN** the configuration includes the key prefix `bulkwrite` for organizing S3 objects

### Requirement: Lifecycle event logging
The StorageTransportExtension SHALL log lifecycle events to track upload progress, completion, and failures.

#### Scenario: Transport start logging
- **WHEN** the bulk write transport starts
- **THEN** the extension logs an INFO message with elapsed time

#### Scenario: Object upload logging
- **WHEN** an SSTable bundle is uploaded to S3
- **THEN** the extension logs a DEBUG message with bucket, key, and size in bytes

#### Scenario: Upload completion logging
- **WHEN** all SSTable bundles are uploaded to S3
- **THEN** the extension logs an INFO message with total object count, row count, and elapsed time

#### Scenario: Import success logging
- **WHEN** a cluster successfully completes the import stage
- **THEN** the extension logs an INFO message with cluster ID and elapsed time

#### Scenario: Import failure logging
- **WHEN** a cluster fails during the import stage
- **THEN** the extension logs an ERROR message with cluster ID and exception details

#### Scenario: Job completion logging
- **WHEN** the bulk write job succeeds
- **THEN** the extension logs an INFO message with total elapsed time

#### Scenario: Job failure logging
- **WHEN** the bulk write job fails
- **THEN** the extension logs an ERROR message with total elapsed time and exception details

### Requirement: Integration with S3BulkWriter
The S3BulkWriter Spark job SHALL configure the data_transport_extension_class option to reference the EasyDbLabStorageExtension class.

#### Scenario: Extension class configuration
- **WHEN** S3BulkWriter builds write options for cassandra-analytics
- **THEN** the write options include `data_transport_extension_class=com.rustyrazorblade.easydblab.spark.EasyDbLabStorageExtension`

#### Scenario: S3_COMPAT transport mode
- **WHEN** S3BulkWriter builds write options for cassandra-analytics
- **THEN** the write options include `data_transport=S3_COMPAT`
