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
            .set("spark.sql.extensions", SparkJobConfig.CONNECTOR_EXTENSIONS)
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");

        SparkSession spark = SparkSession.builder()
            .config(conf)
            .getOrCreate();

        try {
            SparkJobConfig config = SparkJobConfig.load(spark.sparkContext().conf());
            config.configureCassandraConnector(spark);
            config.setupSchema();

            Dataset<Row> df = config.generateTestData(spark);

            System.out.println("Writing to " + config.getKeyspace() + "." + config.getTable() +
                " via Spark Cassandra Connector");

            df.write()
                .format(SparkJobConfig.CASSANDRA_CONNECTOR_FORMAT)
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
