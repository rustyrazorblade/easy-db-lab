package com.rustyrazorblade.easydblab.spark;

import org.apache.cassandra.spark.bulkwriter.BulkSparkConf;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.util.Map;

/**
 * Bulk writer that uses DIRECT transport mode.
 * Data is written directly to Cassandra via Sidecar.
 *
 * Usage:
 *   spark-submit \
 *     --conf spark.easydblab.contactPoints=host1,host2,host3 \
 *     --conf spark.easydblab.keyspace=bulk_test \
 *     --conf spark.easydblab.localDc=datacenter1 \
 *     --conf spark.easydblab.rowCount=1000000 \
 *     --class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
 *     bulk-writer-sidecar.jar
 */
public class DirectBulkWriter {

    public static void main(String[] args) {
        DirectBulkWriter writer = new DirectBulkWriter();
        try {
            writer.run();
        } catch (Exception e) {
            System.err.println("Error during bulk write: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void run() {
        SparkConf conf = new SparkConf(true)
            .setAppName("DirectBulkWriter")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .set("spark.cassandra_analytics.job.skip_clean", "true");

        BulkSparkConf.setupSparkConf(conf, true);

        SparkSession spark = SparkSession.builder()
            .config(conf)
            .getOrCreate();

        try {
            SparkJobConfig config = SparkJobConfig.load(spark.sparkContext().conf());
            config.setupSchema();

            Dataset<Row> df = config.generateTestData(spark);

            Map<String, String> writeOptions = config.buildBulkWriteOptions();
            writeOptions.put(SparkJobConfig.OPT_DATA_TRANSPORT, SparkJobConfig.TRANSPORT_DIRECT);

            System.out.println("Writing to " + config.getKeyspace() + "." + config.getTable() +
                " via " + SparkJobConfig.TRANSPORT_DIRECT + " transport");

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
