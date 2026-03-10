package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.substring;

/**
 * Reads from a Cassandra table, transforms data, and writes results back.
 * Demonstrates the standard Spark Cassandra Connector read→transform→write pattern.
 *
 * Reads from keyvalue table, extracts 3-char prefix from values,
 * groups and counts frequencies, writes to keyvalue_prefix_count table.
 *
 * Uses shared SparkJobConfig for contactPoints and localDc.
 * The keyspace defaults to cassandra_easy_stress.
 *
 * Usage:
 *   spark-submit \
 *     --conf spark.easydblab.contactPoints=host1 \
 *     --conf spark.easydblab.keyspace=cassandra_easy_stress \
 *     --conf spark.easydblab.localDc=us-west-2 \
 *     --class com.rustyrazorblade.easydblab.spark.KeyValuePrefixCount \
 *     connector-read-write.jar
 */
public class KeyValuePrefixCount {
    public static void main(String[] args) {
        SparkConf conf = new SparkConf(true)
            .setAppName("KeyValuePrefixCount")
            .set("spark.sql.extensions", "com.datastax.spark.connector.CassandraSparkExtensions")
            .set("spark.sql.catalog.cassandra", "com.datastax.spark.connector.datasource.CassandraCatalog")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");

        SparkSession spark = SparkSession.builder()
            .config(conf)
            .getOrCreate();

        try {
            SparkJobConfig config = SparkJobConfig.load(spark.sparkContext().conf());

            // Set Cassandra connector properties
            spark.conf().set("spark.cassandra.connection.host", config.getContactPoints());
            spark.conf().set("spark.cassandra.connection.localDC", config.getLocalDc());

            String keyspace = config.getKeyspace();

            // Create the output table if it doesn't exist
            try (CqlSetup cqlSetup = new CqlSetup(config.getContactPoints(), config.getLocalDc())) {
                cqlSetup.createKeyspace(keyspace, config.getReplicationFactor());
                cqlSetup.execute(
                    "CREATE TABLE IF NOT EXISTS " + keyspace + ".keyvalue_prefix_count " +
                    "(prefix text PRIMARY KEY, frequency bigint)");
                cqlSetup.execute("TRUNCATE " + keyspace + ".keyvalue_prefix_count");
            }

            // Read from keyvalue table
            Dataset<Row> keyValueDF = spark.read()
                .format("org.apache.spark.sql.cassandra")
                .option("keyspace", keyspace)
                .option("table", "keyvalue")
                .load();

            // Extract first 3 characters of value and count frequencies
            Dataset<Row> prefixCounts = keyValueDF
                .select(substring(col("value"), 1, 3).as("prefix"))
                .groupBy("prefix")
                .count()
                .withColumnRenamed("count", "frequency");

            // Write results to keyvalue_prefix_count table
            prefixCounts.write()
                .format("org.apache.spark.sql.cassandra")
                .option("keyspace", keyspace)
                .option("table", "keyvalue_prefix_count")
                .mode("append")
                .save();

            System.out.println("Successfully wrote prefix counts");
        } finally {
            spark.stop();
        }
    }
}
