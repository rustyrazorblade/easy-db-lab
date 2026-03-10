package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * Writes data using the standard Spark Cassandra Connector.
 * Provides baseline comparison against cassandra-analytics bulk writers.
 *
 * Usage:
 *   spark-submit \
 *     --conf spark.easydblab.contactPoints=host1,host2 \
 *     --conf spark.easydblab.keyspace=bulk_test \
 *     --conf spark.easydblab.localDc=datacenter1 \
 *     --conf spark.easydblab.rowCount=1000000 \
 *     --class com.rustyrazorblade.easydblab.spark.StandardConnectorWriter \
 *     connector-writer.jar
 */
public class StandardConnectorWriter {

    public static void main(String[] args) {
        StandardConnectorWriter writer = new StandardConnectorWriter();
        try {
            writer.run();
        } catch (Exception e) {
            System.err.println("Error during Spark Connector write: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void run() {
        SparkConf conf = new SparkConf(true)
            .setAppName("StandardConnectorWriter")
            .set("spark.sql.extensions", "com.datastax.spark.connector.CassandraSparkExtensions")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");

        SparkSession spark = SparkSession.builder()
            .config(conf)
            .getOrCreate();

        try {
            SparkJobConfig config = SparkJobConfig.load(spark.sparkContext().conf());

            // Set Cassandra connector properties
            spark.conf().set("spark.cassandra.connection.host", config.getContactPoints());
            spark.conf().set("spark.cassandra.connection.localDC", config.getLocalDc());

            config.setupSchema();

            DataGenerator dataGenerator = new BulkTestDataGenerator();
            System.out.println("Generating " + config.getRowCount() + " rows across " +
                config.getPartitionCount() + " partitions with parallelism " + config.getParallelism());

            Dataset<Row> df = dataGenerator.generate(spark, config.getRowCount(),
                config.getParallelism(), config.getPartitionCount());

            System.out.println("Writing to " + config.getKeyspace() + "." + config.getTable() +
                " via Spark Cassandra Connector");

            df.write()
                .format("org.apache.spark.sql.cassandra")
                .option("keyspace", config.getKeyspace())
                .option("table", config.getTable())
                .mode("append")
                .save();

            System.out.println("Successfully wrote " + config.getRowCount() + " rows");
        } finally {
            spark.stop();
        }
    }
}
