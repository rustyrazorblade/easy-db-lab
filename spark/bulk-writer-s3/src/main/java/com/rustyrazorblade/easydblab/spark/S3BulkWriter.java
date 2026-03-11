package com.rustyrazorblade.easydblab.spark;

import org.apache.cassandra.spark.bulkwriter.BulkSparkConf;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.util.Map;

/**
 * Bulk writer that uses S3_COMPAT transport mode.
 * Data is written to S3-compatible storage, then imported via Sidecar.
 *
 * Additional required property:
 *   spark.easydblab.s3.bucket - Target S3 bucket name
 *
 * Usage:
 *   spark-submit \
 *     --conf spark.easydblab.contactPoints=host1,host2,host3 \
 *     --conf spark.easydblab.keyspace=bulk_test \
 *     --conf spark.easydblab.localDc=datacenter1 \
 *     --conf spark.easydblab.s3.bucket=my-bucket \
 *     --conf spark.easydblab.rowCount=1000000 \
 *     --class com.rustyrazorblade.easydblab.spark.S3BulkWriter \
 *     bulk-writer-s3.jar
 */
public class S3BulkWriter {

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
        SparkConf conf = new SparkConf(true)
            .setAppName("S3BulkWriter")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .set("spark.cassandra_analytics.job.skip_clean", "true");

        BulkSparkConf.setupSparkConf(conf, true);

        SparkSession spark = SparkSession.builder()
            .config(conf)
            .getOrCreate();

        try {
            SparkJobConfig config = SparkJobConfig.load(spark.sparkContext().conf());

            // Validate S3-specific config
            if (config.getS3Bucket() == null) {
                throw new IllegalArgumentException(
                    "ERROR: Required Spark property not set: " + SparkJobConfig.PROP_S3_BUCKET + "\n" +
                    "  --conf " + SparkJobConfig.PROP_S3_BUCKET + "=<bucket> (required)\n" +
                    "  --conf " + SparkJobConfig.PROP_S3_ENDPOINT + "=<url> (optional)");
            }

            if (config.getS3Endpoint() != null) {
                System.out.println("Using S3 endpoint: " + config.getS3Endpoint());
            } else {
                System.out.println("Using default AWS S3 endpoint");
            }
            System.out.println("Using S3 bucket: " + config.getS3Bucket());

            config.setupSchema();

            Dataset<Row> df = config.generateTestData(spark);

            Map<String, String> writeOptions = config.buildBulkWriteOptions();
            writeOptions.put(SparkJobConfig.OPT_DATA_TRANSPORT, "S3_COMPAT");
            if (config.getS3Endpoint() != null) {
                writeOptions.put("storage_client_endpoint_override", config.getS3Endpoint());
            }
            writeOptions.put("storage_client_max_chunk_size_in_bytes",
                String.valueOf(SparkJobConfig.S3_MAX_CHUNK_SIZE_BYTES));
            writeOptions.put("max_size_per_sstable_bundle_in_bytes_s3_transport",
                String.valueOf(SparkJobConfig.S3_MAX_SSTABLE_BUNDLE_BYTES));

            System.out.println("Writing to " + config.getKeyspace() + "." + config.getTable() +
                " via S3_COMPAT transport");

            df.write()
                .format(SparkJobConfig.CASSANDRA_DATA_SINK)
                .options(writeOptions)
                .mode("append")
                .save();

            System.out.println("Successfully wrote " + config.getRowCount() + " rows");
        } finally {
            spark.stop();
        }
    }
}
