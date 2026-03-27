## Why

The `spark/bulk-writer-s3` module exists to bulk write data simultaneously to multiple Cassandra clusters (one per DC) using S3 as a staging layer. Each DC is a separate easy-db-lab cluster with its own S3 data bucket. S3 replication copies SSTable bundles from the primary write bucket to each cluster's read bucket, allowing each cluster's Sidecar to import from its own bucket.

The module was missing the `StorageTransportExtension` implementation required by cassandra-analytics to perform coordinated S3-staged bulk writes across multiple clusters.

## What Changes

- `EasyDbLabStorageExtension.java` - implements `StorageTransportExtension` with multi-DC coordinated write support
- `S3BulkWriter.java` - configured with `data_transport_extension_class` option
- Extension uses AWS SDK `DefaultCredentialsProvider` to obtain credentials from EMR instance profile
- Extension reads write bucket from `spark.easydblab.s3.bucket`
- Extension reads per-DC read buckets from `spark.easydblab.s3.readBuckets` (format: `dc1:bucket1,dc2:bucket2`)
- Extension auto-detects AWS region from EC2 instance metadata
- Uses cassandra-analytics coordinated-write `StorageTransportConfiguration` constructor with one read config per DC

## Capabilities

### New Capabilities
- `s3-bulk-write`: Bulk write data to multiple Cassandra clusters simultaneously using S3 as a staging layer, with cassandra-analytics coordinating SSTable generation, upload, and per-cluster Sidecar-driven import

### Modified Capabilities
<!-- No existing capabilities are being modified -->

## Impact

**Code:**
- `spark/bulk-writer-s3/` - new extension class, updated writer

**Configuration:**
- New required property: `spark.easydblab.s3.readBuckets=dc1:bucket1,dc2:bucket2`
- Existing property: `spark.easydblab.s3.bucket` (write bucket, unchanged)

**Dependencies:**
- Add AWS SDK v2 dependencies: `auth`, `regions` (for credential and region auto-detection)
- Already have: `cassandra-analytics` (provides StorageTransportExtension interface)

**Runtime:**
- Requires EMR cluster with `EasyDBLabEMREC2Role` instance profile (already provisioned)
- Requires S3 write bucket with appropriate permissions (already provisioned via `clusterState.dataBucket`)
- Requires S3 replication configured from write bucket to each DC's read bucket (infrastructure setup)
- Requires Cassandra nodes running Sidecar on port 9043 (already deployed)

**Testing:**
- Integration test with EMR cluster, S3 buckets with replication, and multiple Cassandra clusters + Sidecar
- Verify coordinated write completes with data present in all DC clusters
