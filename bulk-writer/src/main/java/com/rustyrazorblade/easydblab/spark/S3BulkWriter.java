package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.SparkConf;

import java.util.HashMap;
import java.util.Map;

/**
 * Bulk writer that uses S3_COMPAT transport mode.
 * Data is written to S3-compatible storage, then imported via Sidecar.
 *
 * This mode is suitable for:
 * - Large datasets
 * - When direct connection to all Cassandra nodes is not possible
 * - Leveraging S3 for intermediate storage
 *
 * Additional Spark properties required:
 * - spark.easydblab.s3.bucket: Target S3 bucket name (required)
 * - spark.easydblab.s3.endpoint: S3 endpoint URL (optional, defaults to AWS S3)
 *
 * Usage:
 *   spark-submit \
 *     --conf spark.easydblab.sidecar.contactPoints=host1,host2,host3 \
 *     --conf spark.easydblab.keyspace=bulk_test \
 *     --conf spark.easydblab.table=data \
 *     --conf spark.easydblab.localDc=datacenter1 \
 *     --conf spark.easydblab.s3.bucket=my-bucket \
 *     --conf spark.easydblab.rowCount=1000000 \
 *     --class com.rustyrazorblade.easydblab.spark.S3BulkWriter \
 *     bulk-writer.jar
 */
public class S3BulkWriter extends AbstractBulkWriter {

    // S3-specific property keys
    public static final String PROP_S3_BUCKET = "spark.easydblab.s3.bucket";
    public static final String PROP_S3_ENDPOINT = "spark.easydblab.s3.endpoint";

    private String s3Endpoint;
    private String s3Bucket;

    public static void main(String[] args) {
        S3BulkWriter writer = new S3BulkWriter();
        try {
            writer.run();
        } catch (Exception e) {
            System.err.println("Error during S3 bulk write: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void run() {
        initSpark("S3BulkWriter");
        loadConfig();
        loadS3Config();
        setupSchema();

        try {
            writeData();
        } finally {
            cleanup();
        }
    }

    /**
     * Load S3-specific configuration from Spark properties.
     */
    private void loadS3Config() {
        SparkConf conf = spark.sparkContext().conf();

        // S3 bucket is required
        if (!conf.contains(PROP_S3_BUCKET)) {
            System.err.println("ERROR: Required Spark property not set: " + PROP_S3_BUCKET);
            System.err.println("");
            System.err.println("S3-specific properties:");
            System.err.println("  --conf " + PROP_S3_BUCKET + "=<bucket> (required)");
            System.err.println("  --conf " + PROP_S3_ENDPOINT + "=<url> (optional, defaults to AWS S3)");
            System.exit(1);
        }
        s3Bucket = conf.get(PROP_S3_BUCKET);

        // S3 endpoint is optional
        if (conf.contains(PROP_S3_ENDPOINT)) {
            s3Endpoint = conf.get(PROP_S3_ENDPOINT);
            System.out.println("Using S3 endpoint: " + s3Endpoint);
        } else {
            System.out.println("Using default AWS S3 endpoint");
        }

        System.out.println("Using S3 bucket: " + s3Bucket);
    }

    @Override
    protected Map<String, String> getTransportWriteOptions() {
        Map<String, String> options = new HashMap<>();
        options.put("data_transport", "S3_COMPAT");

        if (s3Endpoint != null) {
            options.put("storage_client_endpoint_override", s3Endpoint);
        }

        // S3 transport tuning options
        // Chunk size for multipart uploads (default 100MB, can be tuned for performance)
        options.put("storage_client_max_chunk_size_in_bytes",
            String.valueOf(100 * 1024 * 1024)); // 100MB

        // Maximum SSTable bundle size for S3 transport (default 5GB)
        options.put("max_size_per_sstable_bundle_in_bytes_s3_transport",
            String.valueOf(5L * 1024 * 1024 * 1024)); // 5GB

        return options;
    }
}
