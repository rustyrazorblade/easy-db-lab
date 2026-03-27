## 1. Add AWS SDK Dependencies

- [x] 1.1 Add AWS SDK v2 `auth` module to spark/bulk-writer-s3/build.gradle.kts for DefaultCredentialsProvider
- [x] 1.2 Add AWS SDK v2 `regions` module to spark/bulk-writer-s3/build.gradle.kts for DefaultAwsRegionProviderChain
- [x] 1.3 Verify dependencies resolve and don't conflict with existing cassandra-analytics transitive dependencies

## 2. Implement EasyDbLabStorageExtension (Multi-DC Coordinated Write)

- [x] 2.1 Create EasyDbLabStorageExtension.java in spark/bulk-writer-s3/src/main/java/com/rustyrazorblade/easydblab/spark/
- [x] 2.2 Implement initialize() to read spark.easydblab.s3.bucket (write bucket) from SparkConf
- [ ] 2.3 Implement initialize() to read and parse spark.easydblab.s3.readBuckets (format: dc1:bucket1,dc2:bucket2) from SparkConf
- [ ] 2.4 Implement initialize() to throw IllegalArgumentException if spark.easydblab.s3.readBuckets is missing or malformed
- [x] 2.5 Implement initialize() to auto-detect AWS region using DefaultAwsRegionProviderChain
- [x] 2.6 Implement initialize() to throw IllegalArgumentException if required spark.easydblab.s3.bucket property is missing
- [x] 2.7 Implement initialize() to cache DefaultCredentialsProvider and validate credentials are available at startup
- [ ] 2.8 Implement getStorageConfiguration() to build Map<String, StorageAccessConfiguration> from parsed readBuckets config
- [ ] 2.9 Implement getStorageConfiguration() to use coordinated-write StorageTransportConfiguration constructor
- [ ] 2.10 Implement getStorageConfiguration() to set write config from spark.easydblab.s3.bucket
- [x] 2.11 Implement getStorageConfiguration() to include "bulkwrite" as key prefix
- [x] 2.12 Implement getStorageConfiguration() to include custom endpoint in extraConfig if provided
- [x] 2.13 Implement onTransportStart() lifecycle hook with INFO logging
- [x] 2.14 Implement onObjectPersisted() lifecycle hook with DEBUG logging (bucket, key, size)
- [x] 2.15 Implement onAllObjectsPersisted() lifecycle hook with INFO logging (count, rows, elapsed)
- [x] 2.16 Implement onStageSucceeded() lifecycle hook with INFO logging (cluster ID, elapsed)
- [x] 2.17 Implement onStageFailed() lifecycle hook with ERROR logging (cluster ID, exception)
- [x] 2.18 Implement onImportSucceeded() lifecycle hook with INFO logging (cluster ID, elapsed)
- [x] 2.19 Implement onImportFailed() lifecycle hook with ERROR logging (cluster ID, exception)
- [x] 2.20 Implement onJobSucceeded() lifecycle hook with INFO logging (total elapsed)
- [x] 2.21 Implement onJobFailed() lifecycle hook with ERROR logging (total elapsed, exception)

## 3. Update S3BulkWriter

- [x] 3.1 Add data_transport_extension_class option to writeOptions map in S3BulkWriter.run()
- [x] 3.2 Verify existing S3_COMPAT transport mode configuration remains unchanged
- [x] 3.3 Verify existing storage_client_endpoint_override logic remains unchanged when custom endpoint is provided

## 4. Update SparkJobConfig

- [ ] 4.1 Add PROP_S3_READ_BUCKETS constant ("spark.easydblab.s3.readBuckets") to SparkJobConfig
- [ ] 4.2 Document the format (comma-separated clusterId:bucketName pairs) in SparkJobConfig javadoc

## 5. Build and Package

- [x] 5.1 Run ./gradlew :spark:bulk-writer-s3:shadowJar to build the shadow JAR
- [x] 5.2 Verify EasyDbLabStorageExtension class is included in the shadow JAR
- [x] 5.3 Verify AWS SDK dependencies are bundled in the shadow JAR

## 6. Unit Tests

- [ ] 6.1 Write unit test: valid readBuckets config parses correctly into Map with correct clusterId keys and bucket names
- [ ] 6.2 Write unit test: missing readBuckets config throws IllegalArgumentException with helpful message
- [ ] 6.3 Write unit test: malformed readBuckets entry (missing colon) throws IllegalArgumentException
- [ ] 6.4 Write unit test: single-entry readBuckets produces coordinated config with one read entry
- [ ] 6.5 Write unit test: multi-entry readBuckets produces coordinated config with correct entry per DC

## 7. Integration Testing

- [ ] 7.1 Provision test environment with EMR cluster and two separate Cassandra+Sidecar clusters (DC1, DC2)
- [ ] 7.2 Configure S3 replication from write bucket to each DC's read bucket
- [ ] 7.3 Submit S3BulkWriter job with spark.easydblab.s3.bucket and spark.easydblab.s3.readBuckets
- [ ] 7.4 Verify both clusters complete import and data is queryable in each DC
- [ ] 7.5 Verify onStageSucceeded and onImportSucceeded fire for each cluster ID

## 8. Documentation

- [ ] 8.1 Update module README with multi-DC usage example showing spark.easydblab.s3.readBuckets
- [ ] 8.2 Document S3 replication prerequisite (write bucket must replicate to each DC's read bucket)
- [ ] 8.3 Document that each Cassandra cluster is a separate easy-db-lab single-DC cluster
