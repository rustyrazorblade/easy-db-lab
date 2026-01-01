package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * Writes data using the standard Spark Cassandra Connector.
 * Provides baseline comparison against cassandra-analytics bulk writers.
 *
 * <h2>Configuration via Spark Properties</h2>
 * All configuration is passed via Spark properties (--conf):
 * <ul>
 *   <li>{@code spark.easydblab.cassandra.host} - Comma-separated Cassandra hosts (required)</li>
 *   <li>{@code spark.easydblab.keyspace} - Target keyspace (required)</li>
 *   <li>{@code spark.easydblab.table} - Target table (required)</li>
 *   <li>{@code spark.easydblab.localDc} - Local datacenter name (required)</li>
 *   <li>{@code spark.easydblab.rowCount} - Number of rows to write (default: 1000000)</li>
 *   <li>{@code spark.easydblab.parallelism} - Number of partitions (default: 10)</li>
 *   <li>{@code spark.easydblab.replicationFactor} - Keyspace replication (default: 3)</li>
 *   <li>{@code spark.easydblab.skipDdl} - Skip DDL creation (default: false)</li>
 *   <li>{@code spark.easydblab.compaction} - Compaction strategy (e.g., LeveledCompactionStrategy, UnifiedCompactionStrategy)</li>
 * </ul>
 *
 * Usage:
 *   spark-submit --packages com.datastax.spark:spark-cassandra-connector_2.12:3.5.1 \
 *     --conf spark.easydblab.cassandra.host=host1,host2 \
 *     --conf spark.easydblab.keyspace=bulk_test \
 *     --conf spark.easydblab.table=data \
 *     --conf spark.easydblab.localDc=datacenter1 \
 *     --conf spark.easydblab.rowCount=1000000 \
 *     --conf spark.easydblab.compaction=LeveledCompactionStrategy \
 *     --class com.rustyrazorblade.easydblab.spark.StandardConnectorWriter \
 *     bulk-writer.jar
 */
public class StandardConnectorWriter {

    // Spark property keys (reuse common ones from AbstractBulkWriter)
    public static final String PROP_CASSANDRA_HOST = "spark.easydblab.cassandra.host";
    public static final String PROP_KEYSPACE = "spark.easydblab.keyspace";
    public static final String PROP_TABLE = "spark.easydblab.table";
    public static final String PROP_LOCAL_DC = "spark.easydblab.localDc";
    public static final String PROP_ROW_COUNT = "spark.easydblab.rowCount";
    public static final String PROP_PARALLELISM = "spark.easydblab.parallelism";
    public static final String PROP_PARTITION_COUNT = "spark.easydblab.partitionCount";
    public static final String PROP_REPLICATION_FACTOR = "spark.easydblab.replicationFactor";
    public static final String PROP_SKIP_DDL = "spark.easydblab.skipDdl";
    public static final String PROP_COMPACTION = "spark.easydblab.compaction";

    private SparkSession spark;
    private DataGenerator dataGenerator = new BulkTestDataGenerator();

    // Configuration loaded from SparkConf
    private String cassandraHost;
    private String keyspace;
    private String table;
    private String localDc;
    private long rowCount = 1000000L;
    private int parallelism = 10;
    private long partitionCount = 10000L;
    private int replicationFactor = 3;
    private boolean skipDdl = false;
    private String compaction;

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
        initSpark();
        loadConfig();
        setupSchema();

        try {
            writeData();
        } finally {
            cleanup();
        }
    }

    private void initSpark() {
        // Use SparkConf(true) to load --conf values passed via spark-submit
        // These are set as system properties before the app starts
        SparkConf conf = new SparkConf(true)
            .setAppName("StandardConnectorWriter")
            .set("spark.sql.extensions", "com.datastax.spark.connector.CassandraSparkExtensions")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");

        spark = SparkSession.builder()
            .config(conf)
            .getOrCreate();
    }

    /**
     * Load configuration from Spark properties.
     */
    private void loadConfig() {
        SparkConf conf = spark.sparkContext().conf();

        // Required properties
        cassandraHost = getRequiredProperty(conf, PROP_CASSANDRA_HOST);
        keyspace = getRequiredProperty(conf, PROP_KEYSPACE);
        localDc = getRequiredProperty(conf, PROP_LOCAL_DC);

        // Table name: use provided value or generate unique default with timestamp
        String providedTable = getOptionalProperty(conf, PROP_TABLE);
        if (providedTable != null) {
            table = providedTable;
        } else {
            long timestamp = System.currentTimeMillis() / 1000;
            table = "data_" + timestamp;
        }

        // Optional properties with defaults
        rowCount = getLongProperty(conf, PROP_ROW_COUNT, 1_000_000L);
        parallelism = getIntProperty(conf, PROP_PARALLELISM, 10);
        partitionCount = getLongProperty(conf, PROP_PARTITION_COUNT, 10_000L);
        replicationFactor = getIntProperty(conf, PROP_REPLICATION_FACTOR, 3);
        skipDdl = getBooleanProperty(conf, PROP_SKIP_DDL, false);
        compaction = getOptionalProperty(conf, PROP_COMPACTION);

        // Set Cassandra connector properties
        spark.conf().set("spark.cassandra.connection.host", cassandraHost);
        spark.conf().set("spark.cassandra.connection.localDC", localDc);

        System.out.println("Configuration loaded:");
        System.out.println("  cassandraHost: " + cassandraHost);
        System.out.println("  keyspace: " + keyspace);
        System.out.println("  table: " + table);
        System.out.println("  localDc: " + localDc);
        System.out.println("  rowCount: " + rowCount);
        System.out.println("  parallelism: " + parallelism);
        System.out.println("  partitionCount: " + partitionCount);
        System.out.println("  replicationFactor: " + replicationFactor);
        System.out.println("  skipDdl: " + skipDdl);
        System.out.println("  compaction: " + (compaction != null ? compaction : "(default)"));
    }

    private String getRequiredProperty(SparkConf conf, String key) {
        if (!conf.contains(key)) {
            System.err.println("ERROR: Required Spark property not set: " + key);
            System.err.println("");
            System.err.println("Required properties:");
            System.err.println("  --conf " + PROP_CASSANDRA_HOST + "=<hosts>");
            System.err.println("  --conf " + PROP_KEYSPACE + "=<keyspace>");
            System.err.println("  --conf " + PROP_LOCAL_DC + "=<datacenter>");
            System.err.println("");
            System.err.println("Optional properties:");
            System.err.println("  --conf " + PROP_TABLE + "=<table> (default: data_<timestamp>)");
            System.err.println("  --conf " + PROP_ROW_COUNT + "=<count> (default: 1000000)");
            System.err.println("  --conf " + PROP_PARALLELISM + "=<num> (default: 10)");
            System.err.println("  --conf " + PROP_PARTITION_COUNT + "=<count> (default: 10000)");
            System.err.println("  --conf " + PROP_REPLICATION_FACTOR + "=<rf> (default: 3)");
            System.err.println("  --conf " + PROP_SKIP_DDL + "=true|false (default: false)");
            System.err.println("  --conf " + PROP_COMPACTION + "=<strategy> (e.g., LeveledCompactionStrategy)");
            System.exit(1);
        }
        return conf.get(key);
    }

    private String getOptionalProperty(SparkConf conf, String key) {
        if (conf.contains(key)) {
            return conf.get(key);
        }
        return null;
    }

    private int getIntProperty(SparkConf conf, String key, int defaultValue) {
        if (conf.contains(key)) {
            return Integer.parseInt(conf.get(key));
        }
        return defaultValue;
    }

    private long getLongProperty(SparkConf conf, String key, long defaultValue) {
        if (conf.contains(key)) {
            return Long.parseLong(conf.get(key));
        }
        return defaultValue;
    }

    private boolean getBooleanProperty(SparkConf conf, String key, boolean defaultValue) {
        if (conf.contains(key)) {
            return Boolean.parseBoolean(conf.get(key));
        }
        return defaultValue;
    }

    private void setupSchema() {
        if (skipDdl) {
            System.out.println("Skipping DDL creation (skipDdl=true)");
            return;
        }
        try (CqlSetup cqlSetup = new CqlSetup(cassandraHost, localDc)) {
            cqlSetup.setupSchema(keyspace, table, replicationFactor, compaction);
        }
    }

    private void writeData() {
        System.out.println("Generating " + rowCount + " rows across " +
            partitionCount + " partitions with parallelism " + parallelism);

        Dataset<Row> df = dataGenerator.generate(spark, rowCount, parallelism, partitionCount);

        System.out.println("Writing to " + keyspace + "." + table + " via Spark Cassandra Connector");

        df.write()
            .format("org.apache.spark.sql.cassandra")
            .option("keyspace", keyspace)
            .option("table", table)
            .mode("append")
            .save();

        System.out.println("Successfully wrote " + rowCount + " rows");
    }

    private void cleanup() {
        if (spark != null) {
            spark.stop();
        }
    }
}
