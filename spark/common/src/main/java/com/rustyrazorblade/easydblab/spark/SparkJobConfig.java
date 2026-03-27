package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared configuration for all Spark job modules.
 * All configuration is passed via Spark properties (--conf):
 * <ul>
 *   <li>{@code spark.easydblab.contactPoints} - Comma-separated database hosts (required)</li>
 *   <li>{@code spark.easydblab.keyspace} - Target keyspace (required)</li>
 *   <li>{@code spark.easydblab.table} - Target table name (optional, default: data_&lt;timestamp&gt;)</li>
 *   <li>{@code spark.easydblab.localDc} - Local datacenter name (required)</li>
 *   <li>{@code spark.easydblab.rowCount} - Number of rows to write (default: 1000000)</li>
 *   <li>{@code spark.easydblab.parallelism} - Number of Spark partitions for generation (default: 10)</li>
 *   <li>{@code spark.easydblab.partitionCount} - Number of Cassandra partitions (default: 10000)</li>
 *   <li>{@code spark.easydblab.replicationFactor} - Keyspace replication (default: 3)</li>
 *   <li>{@code spark.easydblab.skipDdl} - Skip DDL creation (default: false)</li>
 *   <li>{@code spark.easydblab.compaction} - Compaction strategy (optional)</li>
 *   <li>{@code spark.easydblab.s3.bucket} - S3 write bucket for S3 transport (required for S3 bulk write)</li>
 *   <li>{@code spark.easydblab.s3.readBuckets} - Per-DC read buckets (required for S3 bulk write), format: dc1:bucket1,dc2:bucket2</li>
 *   <li>{@code spark.easydblab.s3.endpoint} - S3 endpoint URL (optional)</li>
 * </ul>
 */
public class SparkJobConfig {

    // Cassandra Analytics data sink format class
    public static final String CASSANDRA_DATA_SINK =
        "org.apache.cassandra.spark.sparksql.CassandraDataSink";

    // Spark Cassandra Connector format class
    public static final String CASSANDRA_CONNECTOR_FORMAT =
        "org.apache.spark.sql.cassandra";

    // Spark Cassandra Connector config keys
    public static final String CONNECTOR_HOST = "spark.cassandra.connection.host";
    public static final String CONNECTOR_LOCAL_DC = "spark.cassandra.connection.localDC";
    public static final String CONNECTOR_EXTENSIONS = "com.datastax.spark.connector.CassandraSparkExtensions";
    public static final String CONNECTOR_CATALOG = "com.datastax.spark.connector.datasource.CassandraCatalog";

    // Bulk writer option keys (cassandra-analytics SDK)
    public static final String OPT_SIDECAR_CONTACT_POINTS = "sidecar_contact_points";
    public static final String OPT_KEYSPACE = "keyspace";
    public static final String OPT_TABLE = "table";
    public static final String OPT_LOCAL_DC = "local_dc";
    public static final String OPT_BULK_WRITER_CL = "bulk_writer_cl";
    public static final String OPT_NUMBER_SPLITS = "number_splits";
    public static final String OPT_DATA_TRANSPORT = "data_transport";

    // Transport mode values
    public static final String TRANSPORT_S3_COMPAT = "S3_COMPAT";
    public static final String TRANSPORT_DIRECT = "DIRECT";

    // S3 transport-specific options
    public static final String OPT_DATA_TRANSPORT_EXTENSION_CLASS = "data_transport_extension_class";
    public static final String OPT_STORAGE_CLIENT_ENDPOINT_OVERRIDE = "storage_client_endpoint_override";
    public static final String OPT_STORAGE_CLIENT_MAX_CHUNK_SIZE = "storage_client_max_chunk_size_in_bytes";
    public static final String OPT_MAX_SIZE_PER_SSTABLE_BUNDLE_S3 = "max_size_per_sstable_bundle_in_bytes_s3_transport";

    /**
     * S3 transport size constants.
     *
     * <p>These values are chosen based on AWS S3 limits and cassandra-analytics best practices:
     * <ul>
     *   <li>Chunk size: AWS S3 multipart upload part size minimum is 5MB, maximum is 5GB.
     *       We use 100MB as a reasonable balance between upload parallelism and overhead.</li>
     *   <li>Bundle size: AWS S3 multipart upload maximum object size is 5TB with up to 10,000 parts.
     *       We use 5GB to keep bundles manageable for Sidecar download and import operations.</li>
     * </ul>
     */
    public static final long S3_MAX_CHUNK_SIZE_BYTES = 100L * 1024 * 1024;       // 100MB
    public static final long S3_MAX_SSTABLE_BUNDLE_BYTES = 5L * 1024 * 1024 * 1024; // 5GB

    // Spark property keys
    public static final String PROP_CONTACT_POINTS = "spark.easydblab.contactPoints";
    public static final String PROP_KEYSPACE = "spark.easydblab.keyspace";
    public static final String PROP_TABLE = "spark.easydblab.table";
    public static final String PROP_LOCAL_DC = "spark.easydblab.localDc";
    public static final String PROP_ROW_COUNT = "spark.easydblab.rowCount";
    public static final String PROP_PARALLELISM = "spark.easydblab.parallelism";
    public static final String PROP_PARTITION_COUNT = "spark.easydblab.partitionCount";
    public static final String PROP_REPLICATION_FACTOR = "spark.easydblab.replicationFactor";
    public static final String PROP_SKIP_DDL = "spark.easydblab.skipDdl";
    public static final String PROP_COMPACTION = "spark.easydblab.compaction";
    public static final String PROP_S3_BUCKET = "spark.easydblab.s3.bucket";
    /**
     * Per-DC read bucket configuration for coordinated multi-cluster S3 bulk writes.
     * Format: comma-separated {@code clusterId:bucketName} pairs.
     * Example: {@code dc1:easy-db-lab-data-cluster1,dc2:easy-db-lab-data-cluster2}
     *
     * <p>Each DC is a separate easy-db-lab cluster. S3 replication copies SSTable bundles
     * from the primary write bucket ({@link #PROP_S3_BUCKET}) to each DC's read bucket.
     */
    public static final String PROP_S3_READ_BUCKETS = "spark.easydblab.s3.readBuckets";
    public static final String PROP_S3_ENDPOINT = "spark.easydblab.s3.endpoint";

    private final String contactPoints;
    private final String keyspace;
    private final String table;
    private final String localDc;
    private final long rowCount;
    private final int parallelism;
    private final long partitionCount;
    private final int replicationFactor;
    private final boolean skipDdl;
    private final String compaction;
    private final String s3Bucket;
    private final String s3Endpoint;

    private SparkJobConfig(String contactPoints, String keyspace, String table, String localDc,
                           long rowCount, int parallelism, long partitionCount, int replicationFactor,
                           boolean skipDdl, String compaction, String s3Bucket, String s3Endpoint) {
        this.contactPoints = contactPoints;
        this.keyspace = keyspace;
        this.table = table;
        this.localDc = localDc;
        this.rowCount = rowCount;
        this.parallelism = parallelism;
        this.partitionCount = partitionCount;
        this.replicationFactor = replicationFactor;
        this.skipDdl = skipDdl;
        this.compaction = compaction;
        this.s3Bucket = s3Bucket;
        this.s3Endpoint = s3Endpoint;
    }

    /**
     * Load configuration from Spark properties.
     *
     * @param conf SparkConf with properties set via --conf flags
     * @return populated SparkJobConfig
     * @throws IllegalArgumentException if required properties are missing
     */
    public static SparkJobConfig load(SparkConf conf) {
        String contactPoints = getRequiredProperty(conf, PROP_CONTACT_POINTS);
        String keyspace = getRequiredProperty(conf, PROP_KEYSPACE);
        String localDc = getRequiredProperty(conf, PROP_LOCAL_DC);

        String table = getOptionalProperty(conf, PROP_TABLE);
        if (table == null) {
            long timestamp = System.currentTimeMillis() / 1000;
            table = "data_" + timestamp;
        }

        long rowCount = getLongProperty(conf, PROP_ROW_COUNT, 1_000_000L);
        int parallelism = getIntProperty(conf, PROP_PARALLELISM, 10);
        long partitionCount = getLongProperty(conf, PROP_PARTITION_COUNT, 10_000L);
        int replicationFactor = getIntProperty(conf, PROP_REPLICATION_FACTOR, 3);
        boolean skipDdl = getBooleanProperty(conf, PROP_SKIP_DDL, false);
        String compaction = getOptionalProperty(conf, PROP_COMPACTION);
        String s3Bucket = getOptionalProperty(conf, PROP_S3_BUCKET);
        String s3Endpoint = getOptionalProperty(conf, PROP_S3_ENDPOINT);

        SparkJobConfig config = new SparkJobConfig(contactPoints, keyspace, table, localDc,
            rowCount, parallelism, partitionCount, replicationFactor, skipDdl, compaction,
            s3Bucket, s3Endpoint);
        config.printConfig();
        return config;
    }

    /**
     * Set up the database schema (keyspace and table) before writing data.
     * When skipDdl is true, validates that the table already exists to fail fast
     * rather than discovering the missing schema during the write phase.
     */
    public void setupSchema() {
        if (skipDdl) {
            System.out.println("Skipping DDL creation (skipDdl=true), verifying table exists...");
            try (CqlSetup cqlSetup = new CqlSetup(contactPoints, localDc)) {
                cqlSetup.validateTableExists(keyspace, table);
            }
            return;
        }
        try (CqlSetup cqlSetup = new CqlSetup(contactPoints, localDc)) {
            cqlSetup.setupSchema(keyspace, table, replicationFactor, compaction);
        }
    }

    private void printConfig() {
        StringBuilder sb = new StringBuilder("Configuration loaded:\n");
        sb.append("  contactPoints: ").append(contactPoints).append('\n');
        sb.append("  keyspace: ").append(keyspace).append('\n');
        sb.append("  table: ").append(table).append('\n');
        sb.append("  localDc: ").append(localDc).append('\n');
        sb.append("  rowCount: ").append(rowCount).append('\n');
        sb.append("  parallelism: ").append(parallelism).append('\n');
        sb.append("  partitionCount: ").append(partitionCount).append('\n');
        sb.append("  replicationFactor: ").append(replicationFactor).append('\n');
        sb.append("  skipDdl: ").append(skipDdl).append('\n');
        sb.append("  compaction: ").append(compaction != null ? compaction : "(default)");
        if (s3Bucket != null) {
            sb.append('\n').append("  s3Bucket: ").append(s3Bucket);
        }
        if (s3Endpoint != null) {
            sb.append('\n').append("  s3Endpoint: ").append(s3Endpoint);
        }
        System.out.println(sb);
    }

    private static String getRequiredProperty(SparkConf conf, String key) {
        if (!conf.contains(key)) {
            String usage = "ERROR: Required Spark property not set: " + key + "\n\n" +
                "Required properties:\n" +
                "  --conf " + PROP_CONTACT_POINTS + "=<hosts>\n" +
                "  --conf " + PROP_KEYSPACE + "=<keyspace>\n" +
                "  --conf " + PROP_LOCAL_DC + "=<datacenter>\n\n" +
                "Optional properties:\n" +
                "  --conf " + PROP_TABLE + "=<table> (default: data_<timestamp>)\n" +
                "  --conf " + PROP_ROW_COUNT + "=<count> (default: 1000000)\n" +
                "  --conf " + PROP_PARALLELISM + "=<num> (default: 10)\n" +
                "  --conf " + PROP_PARTITION_COUNT + "=<count> (default: 10000)\n" +
                "  --conf " + PROP_REPLICATION_FACTOR + "=<rf> (default: 3)\n" +
                "  --conf " + PROP_SKIP_DDL + "=true|false (default: false)\n" +
                "  --conf " + PROP_COMPACTION + "=<strategy>\n" +
                "  --conf " + PROP_S3_BUCKET + "=<bucket> (S3 transport only)\n" +
                "  --conf " + PROP_S3_ENDPOINT + "=<url> (S3 transport only)";
            throw new IllegalArgumentException(usage);
        }
        return conf.get(key);
    }

    private static String getOptionalProperty(SparkConf conf, String key) {
        if (conf.contains(key)) {
            return conf.get(key);
        }
        return null;
    }

    /**
     * Generic property parser with error handling.
     *
     * @param conf SparkConf to read from
     * @param key property key
     * @param defaultValue value to return if property not set
     * @param parser function to parse string to desired type
     * @return parsed value or default
     * @throws IllegalArgumentException if value cannot be parsed
     */
    private static <T> T getProperty(SparkConf conf, String key, T defaultValue,
                                     java.util.function.Function<String, T> parser) {
        if (!conf.contains(key)) {
            return defaultValue;
        }

        String value = conf.get(key);
        try {
            return parser.apply(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid value for " + key + ": '" + value + "'. " +
                "Expected a valid " + defaultValue.getClass().getSimpleName().toLowerCase() + ".", e);
        }
    }

    private static int getIntProperty(SparkConf conf, String key, int defaultValue) {
        return getProperty(conf, key, defaultValue, Integer::parseInt);
    }

    private static long getLongProperty(SparkConf conf, String key, long defaultValue) {
        return getProperty(conf, key, defaultValue, Long::parseLong);
    }

    private static boolean getBooleanProperty(SparkConf conf, String key, boolean defaultValue) {
        if (!conf.contains(key)) {
            return defaultValue;
        }

        String value = conf.get(key);
        if ("true".equalsIgnoreCase(value)) {
            return true;
        } else if ("false".equalsIgnoreCase(value)) {
            return false;
        } else {
            throw new IllegalArgumentException(
                "Invalid value for " + key + ": '" + value + "'. " +
                "Expected 'true' or 'false'.");
        }
    }

    /**
     * Generate test data using the standard BulkTestDataGenerator.
     * Logs progress and returns the generated DataFrame.
     */
    public Dataset<Row> generateTestData(SparkSession spark) {
        DataGenerator dataGenerator = new BulkTestDataGenerator();
        System.out.println("Generating " + rowCount + " rows across " +
            partitionCount + " partitions with parallelism " + parallelism);
        return dataGenerator.generate(spark, rowCount, parallelism, partitionCount);
    }

    /**
     * Build the base write options map for cassandra-analytics bulk writers.
     * Includes sidecar contact points, keyspace, table, local DC, consistency level,
     * and number of splits. Callers add transport-specific options on top.
     */
    public Map<String, String> buildBulkWriteOptions() {
        Map<String, String> options = new HashMap<>();
        options.put(OPT_SIDECAR_CONTACT_POINTS, contactPoints);
        options.put(OPT_KEYSPACE, keyspace);
        options.put(OPT_TABLE, table);
        options.put(OPT_LOCAL_DC, localDc);
        options.put(OPT_BULK_WRITER_CL, "LOCAL_QUORUM");
        options.put(OPT_NUMBER_SPLITS, "-1");
        return options;
    }

    /**
     * Configure Spark Cassandra Connector host and datacenter on an existing session.
     */
    public void configureCassandraConnector(SparkSession spark) {
        spark.conf().set(CONNECTOR_HOST, contactPoints);
        spark.conf().set(CONNECTOR_LOCAL_DC, localDc);
    }

    // Getters
    public String getContactPoints() { return contactPoints; }
    public String getKeyspace() { return keyspace; }
    public String getTable() { return table; }
    public String getLocalDc() { return localDc; }
    public long getRowCount() { return rowCount; }
    public int getParallelism() { return parallelism; }
    public long getPartitionCount() { return partitionCount; }
    public int getReplicationFactor() { return replicationFactor; }
    public boolean isSkipDdl() { return skipDdl; }
    public String getCompaction() { return compaction; }
    public String getS3Bucket() { return s3Bucket; }
    public String getS3Endpoint() { return s3Endpoint; }
}
