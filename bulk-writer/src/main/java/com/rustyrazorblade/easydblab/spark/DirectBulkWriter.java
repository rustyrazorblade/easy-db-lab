package com.rustyrazorblade.easydblab.spark;

import java.util.HashMap;
import java.util.Map;

/**
 * Bulk writer that uses DIRECT transport mode.
 * Data is written directly to Cassandra via Sidecar.
 *
 * This mode is suitable for:
 * - Lower latency writes
 * - Smaller datasets
 * - When S3 is not available or not desired
 *
 * Usage:
 *   spark-submit \
 *     --conf spark.easydblab.sidecar.contactPoints=host1,host2,host3 \
 *     --conf spark.easydblab.keyspace=bulk_test \
 *     --conf spark.easydblab.table=data \
 *     --conf spark.easydblab.localDc=datacenter1 \
 *     --conf spark.easydblab.rowCount=1000000 \
 *     --class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
 *     bulk-writer.jar
 */
public class DirectBulkWriter extends AbstractBulkWriter {

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
        initSpark("DirectBulkWriter");
        loadConfig();
        setupSchema();

        try {
            writeData();
        } finally {
            cleanup();
        }
    }

    @Override
    protected Map<String, String> getTransportWriteOptions() {
        Map<String, String> options = new HashMap<>();
        options.put("data_transport", "DIRECT");
        return options;
    }
}
