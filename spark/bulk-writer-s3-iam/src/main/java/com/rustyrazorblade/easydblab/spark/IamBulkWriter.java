package com.rustyrazorblade.easydblab.spark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.cassandra.spark.bulkwriter.BulkSparkConf;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Bulk writer that uses S3_COMPAT transport with IAM instance profile credentials.
 *
 * No static AWS credentials are extracted or passed to the sidecar. Both the Spark
 * executor (for S3 uploads) and the Cassandra sidecar (for SSTable import) authenticate
 * independently via their attached IAM roles.
 *
 * Cluster topology (DCs and per-AZ sidecar contact points) is auto-discovered from
 * the Cassandra Java driver at job startup. No manual DC configuration is required.
 *
 * Required properties:
 *   spark.easydblab.contactPoints  - Cassandra hosts for driver connection and schema setup
 *   spark.easydblab.keyspace       - Target keyspace
 *   spark.easydblab.localDc        - DC for initial driver connection
 *   spark.easydblab.s3.bucket      - S3 write bucket
 *
 * Optional per-DC overrides (applied after topology discovery):
 *   spark.easydblab.dc.<dc>.s3.readBucket  - Read bucket for this DC (defaults to write bucket)
 *   spark.easydblab.dc.<dc>.s3.region      - AWS region for this DC's read bucket
 *
 * Usage:
 *   spark-submit \
 *     --conf spark.easydblab.contactPoints=host1,host2,host3 \
 *     --conf spark.easydblab.keyspace=bulk_test \
 *     --conf spark.easydblab.localDc=datacenter1 \
 *     --conf spark.easydblab.s3.bucket=my-bucket \
 *     --class com.rustyrazorblade.easydblab.spark.IamBulkWriter \
 *     bulk-writer-s3-iam.jar
 */
public class IamBulkWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(IamBulkWriter.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        IamBulkWriter writer = new IamBulkWriter();
        try {
            writer.run();
        } catch (Exception e) {
            LOGGER.error("IAM S3 bulk write failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    public void run() {
        LOGGER.info("============================================================");
        LOGGER.info("IAM Bulk Writer starting");
        LOGGER.info("Credential mode: IAM instance profile (no static AWS keys)");
        LOGGER.info("============================================================");

        SparkConf conf = new SparkConf(true)
            .setAppName("IamBulkWriter")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .set("spark.cassandra_analytics.job.skip_clean", "true");

        BulkSparkConf.setupSparkConf(conf, true);

        // Load config, discover topology, and set readBuckets on SparkConf BEFORE creating the
        // SparkSession. StorageTransportExtension.initialize() receives the SparkConf (not the
        // RuntimeConfig), so spark.conf().set() after session creation would not be visible to it.
        LOGGER.info("Step 1: Loading Spark configuration");
        SparkJobConfig config = SparkJobConfig.load(conf);

        if (config.getS3Bucket() == null) {
            throw new IllegalArgumentException(
                "Required Spark property not set: " + SparkJobConfig.PROP_S3_BUCKET + "\n" +
                "Set it with: --conf " + SparkJobConfig.PROP_S3_BUCKET + "=<bucket-name>");
        }

        validateS3Bucket(config.getS3Bucket());

        LOGGER.info("Step 2: Discovering cluster topology from Cassandra driver");
        Map<String, List<String>> topology =
            CqlSetup.discoverTopology(config.getContactPoints(), config.getLocalDc());

        if (topology.isEmpty()) {
            throw new IllegalStateException(
                "Topology discovery returned no DCs. " +
                "Check contactPoints=" + config.getContactPoints() +
                " and localDc=" + config.getLocalDc());
        }

        LOGGER.info("Step 3: Building S3 read bucket and coordinated write configuration");
        String readBuckets = buildReadBuckets(topology, config, conf);
        conf.set(SparkJobConfig.PROP_S3_READ_BUCKETS, readBuckets);
        LOGGER.info("  readBuckets: {}", readBuckets);

        String coordinatedWriteConfig = buildCoordinatedWriteConfig(topology);
        LOGGER.info("  coordinated_write_config: {}", coordinatedWriteConfig);

        SparkSession spark = SparkSession.builder()
            .config(conf)
            .getOrCreate();

        try {
            LOGGER.info("Step 4: Setting up schema (keyspace={}, table={})",
                config.getKeyspace(), config.getTable());
            config.setupSchema();

            LOGGER.info("Step 5: Generating {} test rows across {} partitions",
                config.getRowCount(), config.getPartitionCount());
            Dataset<Row> df = config.generateTestData(spark);

            Map<String, String> writeOptions = config.buildBulkWriteOptions();
            writeOptions.put(SparkJobConfig.OPT_DATA_TRANSPORT, SparkJobConfig.TRANSPORT_S3_COMPAT);
            writeOptions.put(SparkJobConfig.OPT_DATA_TRANSPORT_EXTENSION_CLASS,
                EasyDbLabIamStorageExtension.EXTENSION_CLASS_NAME);
            writeOptions.put("STORAGE_CREDENTIAL_TYPE", "IAM");
            if (config.getS3Endpoint() != null) {
                writeOptions.put(SparkJobConfig.OPT_STORAGE_CLIENT_ENDPOINT_OVERRIDE, config.getS3Endpoint());
            }
            writeOptions.put(SparkJobConfig.OPT_STORAGE_CLIENT_MAX_CHUNK_SIZE,
                String.valueOf(SparkJobConfig.S3_MAX_CHUNK_SIZE_BYTES));
            writeOptions.put(SparkJobConfig.OPT_MAX_SIZE_PER_SSTABLE_BUNDLE_S3,
                String.valueOf(SparkJobConfig.S3_MAX_SSTABLE_BUNDLE_BYTES));
            writeOptions.put(SparkJobConfig.OPT_COORDINATED_WRITE_CONFIG, coordinatedWriteConfig);

            LOGGER.info("Step 6: Starting bulk write to {}.{} via {} transport (IAM, multi-DC coordinated)",
                config.getKeyspace(), config.getTable(), SparkJobConfig.TRANSPORT_S3_COMPAT);

            df.write()
                .format(SparkJobConfig.CASSANDRA_DATA_SINK)
                .options(writeOptions)
                .mode("append")
                .save();

            LOGGER.info("============================================================");
            LOGGER.info("Bulk write COMPLETE: {} rows written to {}.{}",
                config.getRowCount(), config.getKeyspace(), config.getTable());
            LOGGER.info("============================================================");
        } finally {
            spark.stop();
        }
    }

    /**
     * Validates that the S3 bucket name does not contain a path component.
     * S3 bucket names must not contain '/' — if they do, the caller likely passed
     * a bucket+path (e.g. "my-bucket/spark") instead of just a bucket name.
     */
    static void validateS3Bucket(String bucket) {
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException(
                SparkJobConfig.PROP_S3_BUCKET + " must not be empty. " +
                "Set with: --conf " + SparkJobConfig.PROP_S3_BUCKET + "=<bucket-name>");
        }
        if (bucket.contains("/")) {
            String bucketOnly = bucket.substring(0, bucket.indexOf('/'));
            String pathPart = bucket.substring(bucket.indexOf('/'));
            LOGGER.error("============================================================");
            LOGGER.error("CONFIGURATION ERROR: {} contains a path separator '/'", SparkJobConfig.PROP_S3_BUCKET);
            LOGGER.error("  Provided value: '{}'", bucket);
            LOGGER.error("  S3 bucket names cannot contain '/'. You likely passed a bucket+path.");
            LOGGER.error("  Bucket name: '{}'  (path component '{}' is not part of a bucket name)", bucketOnly, pathPart);
            LOGGER.error("  Fix: --conf {}={}", SparkJobConfig.PROP_S3_BUCKET, bucketOnly);
            LOGGER.error("============================================================");
            throw new IllegalArgumentException(
                SparkJobConfig.PROP_S3_BUCKET + "='" + bucket + "' is not a valid S3 bucket name. " +
                "Remove the path component. Expected: --conf " + SparkJobConfig.PROP_S3_BUCKET + "=" + bucketOnly);
        }
    }

    /**
     * Build the {@code readBuckets} string for {@link EasyDbLabIamStorageExtension}.
     * For each discovered DC, uses {@code spark.easydblab.dc.<dc>.s3.readBucket} if set,
     * otherwise falls back to the write bucket.
     */
    static String buildReadBuckets(Map<String, List<String>> topology, SparkJobConfig config,
                                   SparkConf conf) {
        StringJoiner joiner = new StringJoiner(",");
        for (String dcName : topology.keySet()) {
            String bucketKey = String.format(SparkJobConfig.PROP_DC_S3_READ_BUCKET, dcName);
            String bucket = conf.contains(bucketKey) ? conf.get(bucketKey) : config.getS3Bucket();
            joiner.add(dcName + ":" + bucket);
        }
        return joiner.toString();
    }

    /**
     * Build the {@code coordinated_write_config} JSON from the discovered topology.
     * Each DC entry contains the per-AZ sidecar contact points, the DC name as {@code localDc},
     * and {@code writeToLocalDcOnly: true}.
     */
    static String buildCoordinatedWriteConfig(Map<String, List<String>> topology) {
        Map<String, Object> dcMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : topology.entrySet()) {
            String dcName = entry.getKey();
            Map<String, Object> dcConfig = new LinkedHashMap<>();
            dcConfig.put("sidecarContactPoints", entry.getValue());
            dcConfig.put("localDc", dcName);
            dcConfig.put("writeToLocalDcOnly", true);
            dcMap.put(dcName, dcConfig);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(dcMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize coordinated write config", e);
        }
    }
}
