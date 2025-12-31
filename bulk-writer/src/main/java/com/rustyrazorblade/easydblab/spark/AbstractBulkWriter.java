package com.rustyrazorblade.easydblab.spark;

import org.apache.cassandra.spark.bulkwriter.BulkSparkConf;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for Cassandra bulk writers using the Analytics library.
 * Provides common configuration and data generation logic.
 *
 * <h2>Configuration via Spark Properties</h2>
 * All configuration is passed via Spark properties (--conf):
 * <ul>
 *   <li>{@code spark.easydblab.sidecar.contactPoints} - Comma-separated sidecar hosts (required)</li>
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
 * <h2>Example Usage</h2>
 * <pre>
 * spark-submit \
 *   --conf spark.easydblab.sidecar.contactPoints=host1,host2 \
 *   --conf spark.easydblab.keyspace=bulk_test \
 *   --conf spark.easydblab.table=data \
 *   --conf spark.easydblab.localDc=datacenter1 \
 *   --conf spark.easydblab.rowCount=1000000 \
 *   --conf spark.easydblab.compaction=LeveledCompactionStrategy \
 *   --class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
 *   bulk-writer.jar
 * </pre>
 */
public abstract class AbstractBulkWriter {

    // Spark property keys
    public static final String PROP_SIDECAR_CONTACT_POINTS = "spark.easydblab.sidecar.contactPoints";
    public static final String PROP_KEYSPACE = "spark.easydblab.keyspace";
    public static final String PROP_TABLE = "spark.easydblab.table";
    public static final String PROP_LOCAL_DC = "spark.easydblab.localDc";
    public static final String PROP_ROW_COUNT = "spark.easydblab.rowCount";
    public static final String PROP_PARALLELISM = "spark.easydblab.parallelism";
    public static final String PROP_REPLICATION_FACTOR = "spark.easydblab.replicationFactor";
    public static final String PROP_SKIP_DDL = "spark.easydblab.skipDdl";
    public static final String PROP_COMPACTION = "spark.easydblab.compaction";

    protected static final String CASSANDRA_DATA_SINK =
        "org.apache.cassandra.spark.sparksql.CassandraDataSink";

    protected SparkSession spark;
    protected JavaSparkContext javaSparkContext;
    protected DataGenerator dataGenerator = new BulkTestDataGenerator();

    // Configuration loaded from SparkConf
    protected String sidecarContactPoints;
    protected String keyspace;
    protected String table;
    protected String localDc;
    protected int rowCount;
    protected int parallelism;
    protected int replicationFactor;
    protected boolean skipDdl;
    protected String compaction;

    /**
     * Initialize Spark session with the given app name.
     * Configures JDK11 options required for Cassandra SSTable generation.
     */
    protected void initSpark(String appName) {
        // Use SparkConf(true) to load --conf values passed via spark-submit
        // These are set as system properties before the app starts
        SparkConf conf = new SparkConf(true)
            .setAppName(appName)
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            // DEBUG: Keep staged files for inspection after job completes
            .set("spark.cassandra_analytics.job.skip_clean", "true");

        // Setup JDK11 options and Kryo registrator required for SSTable generation
        BulkSparkConf.setupSparkConf(conf, true);

        spark = SparkSession.builder()
            .config(conf)
            .getOrCreate();

        javaSparkContext = new JavaSparkContext(spark.sparkContext());
    }

    /**
     * Load configuration from Spark properties.
     * Must be called after initSpark().
     *
     * @throws IllegalArgumentException if required properties are missing
     */
    protected void loadConfig() {
        SparkConf conf = spark.sparkContext().conf();

        // Required properties
        sidecarContactPoints = getRequiredProperty(conf, PROP_SIDECAR_CONTACT_POINTS);
        keyspace = getRequiredProperty(conf, PROP_KEYSPACE);
        table = getRequiredProperty(conf, PROP_TABLE);
        localDc = getRequiredProperty(conf, PROP_LOCAL_DC);

        // Optional properties with defaults
        rowCount = getIntProperty(conf, PROP_ROW_COUNT, 1000000);
        parallelism = getIntProperty(conf, PROP_PARALLELISM, 10);
        replicationFactor = getIntProperty(conf, PROP_REPLICATION_FACTOR, 3);
        skipDdl = getBooleanProperty(conf, PROP_SKIP_DDL, false);
        compaction = getOptionalProperty(conf, PROP_COMPACTION);

        System.out.println("Configuration loaded:");
        System.out.println("  sidecarContactPoints: " + sidecarContactPoints);
        System.out.println("  keyspace: " + keyspace);
        System.out.println("  table: " + table);
        System.out.println("  localDc: " + localDc);
        System.out.println("  rowCount: " + rowCount);
        System.out.println("  parallelism: " + parallelism);
        System.out.println("  replicationFactor: " + replicationFactor);
        System.out.println("  skipDdl: " + skipDdl);
        System.out.println("  compaction: " + (compaction != null ? compaction : "(default)"));
    }

    private String getRequiredProperty(SparkConf conf, String key) {
        if (!conf.contains(key)) {
            System.err.println("ERROR: Required Spark property not set: " + key);
            System.err.println("");
            System.err.println("Required properties:");
            System.err.println("  --conf " + PROP_SIDECAR_CONTACT_POINTS + "=<hosts>");
            System.err.println("  --conf " + PROP_KEYSPACE + "=<keyspace>");
            System.err.println("  --conf " + PROP_TABLE + "=<table>");
            System.err.println("  --conf " + PROP_LOCAL_DC + "=<datacenter>");
            System.err.println("");
            System.err.println("Optional properties:");
            System.err.println("  --conf " + PROP_ROW_COUNT + "=<count> (default: 1000000)");
            System.err.println("  --conf " + PROP_PARALLELISM + "=<num> (default: 10)");
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

    private boolean getBooleanProperty(SparkConf conf, String key, boolean defaultValue) {
        if (conf.contains(key)) {
            return Boolean.parseBoolean(conf.get(key));
        }
        return defaultValue;
    }

    /**
     * Common write options for both transport modes.
     */
    protected Map<String, String> getBaseWriteOptions() {
        Map<String, String> options = new HashMap<>();
        options.put("sidecar_contact_points", sidecarContactPoints);
        options.put("keyspace", keyspace);
        options.put("table", table);
        options.put("local_dc", localDc);
        options.put("bulk_writer_cl", "LOCAL_QUORUM");
        options.put("number_splits", "-1"); // Auto-calculate
        return options;
    }

    /**
     * Get transport-specific write options. Subclasses implement this.
     */
    protected abstract Map<String, String> getTransportWriteOptions();

    /**
     * Write data to Cassandra using the bulk writer.
     */
    protected void writeData() {
        System.out.println("Generating " + rowCount + " rows with parallelism " + parallelism);

        JavaRDD<Row> rows = dataGenerator.generate(javaSparkContext, rowCount, parallelism);
        Dataset<Row> df = spark.createDataFrame(rows, dataGenerator.getSchema());

        Map<String, String> writeOptions = getBaseWriteOptions();
        writeOptions.putAll(getTransportWriteOptions());

        System.out.println("Writing to " + keyspace + "." + table + " via " +
            writeOptions.get("data_transport") + " transport");

        df.write()
            .format(CASSANDRA_DATA_SINK)
            .options(writeOptions)
            .mode("append")
            .save();

        System.out.println("Successfully wrote " + rowCount + " rows");
    }

    /**
     * Set up the schema (keyspace and table) before writing data.
     * Uses CqlSetup to create keyspace with NetworkTopologyStrategy and table with fixed schema.
     * Can be skipped with spark.easydblab.skipDdl=true property.
     */
    protected void setupSchema() {
        if (skipDdl) {
            System.out.println("Skipping DDL creation (skipDdl=true)");
            return;
        }
        try (CqlSetup cqlSetup = new CqlSetup(sidecarContactPoints, localDc)) {
            cqlSetup.setupSchema(keyspace, table, replicationFactor, compaction);
        }
    }

    /**
     * Clean up Spark resources.
     */
    protected void cleanup() {
        if (spark != null) {
            spark.stop();
        }
    }
}
