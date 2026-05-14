package com.rustyrazorblade.easydblab.spark;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.metadata.Node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles CQL session creation and DDL execution for bulk writer setup.
 * Creates keyspace and table if they don't exist before bulk write begins.
 */
public class CqlSetup implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CqlSetup.class);

    private static final int CQL_PORT = 9042;
    static final int SIDECAR_PORT = 9043;
    private static final long CONNECTION_TIMEOUT_SECONDS = 30;
    private static final long REQUEST_TIMEOUT_SECONDS = 60;

    private final CqlSession session;
    private final String localDc;

    /**
     * Create a CQL session connected to Cassandra.
     *
     * @param contactPoints Comma-separated list of Cassandra host IPs
     * @param localDc Datacenter name for load balancing
     */
    public CqlSetup(String contactPoints, String localDc) {
        this(contactPoints, localDc, CQL_PORT);
    }

    // Package-private for testing with a non-standard port (e.g. TestContainers mapped port).
    CqlSetup(String contactPoints, String localDc, int port) {
        this.localDc = localDc;
        LOGGER.info("Connecting to Cassandra for DDL setup: {}:{} (DC: {})", contactPoints, port, localDc);

        try {
            this.session = buildSession(contactPoints, localDc, port);
            LOGGER.info("Connected to Cassandra cluster");
        } catch (Exception e) {
            LOGGER.error("Failed to connect to Cassandra");
            LOGGER.error("  Contact points: {}:{}", contactPoints, port);
            LOGGER.error("  Local DC: {}", localDc);
            LOGGER.error("  Tip: verify the DC name with 'nodetool status' on a Cassandra node");
            LOGGER.error("  Tip: with EC2Snitch the DC name is the AWS region (e.g. 'us-west-2')");
            throw new RuntimeException(
                "Failed to connect to Cassandra at " + contactPoints +
                " (datacenter: " + localDc + "): " + e.getMessage(), e);
        }
    }

    /**
     * Create keyspace with NetworkTopologyStrategy across every DC in the cluster.
     * Discovers all DCs from the active session's node metadata so every datacenter
     * gets the specified replication factor regardless of how many DCs exist.
     *
     * @param keyspace Keyspace name
     * @param replicationFactor Replication factor applied to every DC
     */
    public void createKeyspace(String keyspace, int replicationFactor) {
        Set<String> dcNames = new LinkedHashSet<>();
        for (Node node : session.getMetadata().getNodes().values()) {
            if (node.getDatacenter() != null) {
                dcNames.add(node.getDatacenter());
            }
        }
        if (dcNames.isEmpty()) {
            dcNames.add(localDc);
        }

        String cql = buildCreateKeyspaceCql(keyspace, dcNames, replicationFactor);
        LOGGER.info("Creating keyspace: {}", cql);
        try {
            session.execute(cql);
            LOGGER.info("Keyspace '{}' ready (RF={} in DCs: {})", keyspace, replicationFactor, dcNames);
        } catch (Exception e) {
            LOGGER.error("Failed to create keyspace '{}': {}", keyspace, e.getMessage());
            throw new RuntimeException("Failed to create keyspace: " + cql, e);
        }
    }

    // Package-private for testing: pure CQL generation, no session required.
    static String buildCreateKeyspaceCql(String keyspace, Collection<String> dcNames, int replicationFactor) {
        StringBuilder replication = new StringBuilder("{'class': 'NetworkTopologyStrategy'");
        for (String dc : dcNames) {
            replication.append(", '").append(dc).append("': ").append(replicationFactor);
        }
        replication.append("}");
        return "CREATE KEYSPACE IF NOT EXISTS " + keyspace + " WITH replication = " + replication;
    }

    /**
     * Create table with the bulk writer clustered schema if it doesn't exist.
     * Schema: partition_id bigint, sequence_id bigint, course blob, marks bigint
     *         PRIMARY KEY ((partition_id), sequence_id)
     *
     * @param keyspace Keyspace name
     * @param table Table name
     */
    public void createTable(String keyspace, String table) {
        createTable(keyspace, table, null);
    }

    /**
     * Create table with the bulk writer clustered schema and optional compaction strategy.
     * Schema: partition_id bigint, sequence_id bigint, course blob, marks bigint
     *         PRIMARY KEY ((partition_id), sequence_id)
     *
     * This schema supports TB-scale data with:
     * - partition_id: distributes data across configurable number of partitions
     * - sequence_id: clustering column for sequential ordering within partitions
     *
     * @param keyspace Keyspace name
     * @param table Table name
     * @param compaction Compaction strategy (e.g., "LeveledCompactionStrategy",
     *                   "SizeTieredCompactionStrategy", "UnifiedCompactionStrategy").
     *                   Can also be full map like "{'class': 'LeveledCompactionStrategy', 'sstable_size_in_mb': 160}".
     *                   If null, uses Cassandra default.
     */
    public void createTable(String keyspace, String table, String compaction) {
        StringBuilder cql = new StringBuilder();
        cql.append(String.format(
            "CREATE TABLE IF NOT EXISTS %s.%s (" +
            "partition_id bigint, " +
            "sequence_id bigint, " +
            "course blob, " +
            "marks bigint, " +
            "PRIMARY KEY ((partition_id), sequence_id))",
            keyspace, table));

        if (compaction != null && !compaction.isEmpty()) {
            // Check if it's already a full map or just a class name
            if (compaction.trim().startsWith("{")) {
                // Full compaction map provided
                cql.append(" WITH compaction = ").append(compaction);
            } else {
                // Just the class name - wrap it in a map
                cql.append(" WITH compaction = {'class': '").append(compaction).append("'}");
            }
        }

        LOGGER.info("Creating table: {}", cql);
        try {
            session.execute(cql.toString());
            LOGGER.info("Table '{}.{}' ready", keyspace, table);
        } catch (Exception e) {
            LOGGER.error("Failed to create table '{}.{}': {}", keyspace, table, e.getMessage());
            throw new RuntimeException("Failed to create table: " + cql, e);
        }
    }

    /**
     * Convenience method to set up both keyspace and table.
     *
     * @param keyspace Keyspace name
     * @param table Table name
     * @param replicationFactor Replication factor for the local datacenter
     */
    public void setupSchema(String keyspace, String table, int replicationFactor) {
        setupSchema(keyspace, table, replicationFactor, null);
    }

    /**
     * Convenience method to set up both keyspace and table with optional compaction.
     *
     * @param keyspace Keyspace name
     * @param table Table name
     * @param replicationFactor Replication factor for the local datacenter
     * @param compaction Compaction strategy (null for default)
     */
    public void setupSchema(String keyspace, String table, int replicationFactor, String compaction) {
        createKeyspace(keyspace, replicationFactor);
        createTable(keyspace, table, compaction);
    }

    /**
     * Validate that a table exists in the given keyspace.
     * Fails fast with a clear error if the table is missing, avoiding late failures
     * during the write phase which waste time and infrastructure cost.
     *
     * @param keyspace Keyspace name
     * @param table Table name
     * @throws RuntimeException if the keyspace or table does not exist
     */
    public void validateTableExists(String keyspace, String table) {
        var metadata = session.getMetadata();
        var ks = metadata.getKeyspace(keyspace).orElse(null);
        if (ks == null) {
            throw new RuntimeException("Keyspace '" + keyspace + "' does not exist. " +
                "Either create it manually or remove skipDdl=true.");
        }
        if (ks.getTable(table).isEmpty()) {
            throw new RuntimeException("Table '" + keyspace + "." + table + "' does not exist. " +
                "Either create it manually or remove skipDdl=true.");
        }
        LOGGER.info("Verified '{}.{}' exists", keyspace, table);
    }

    /**
     * Execute an arbitrary CQL statement.
     *
     * @param cql CQL statement to execute
     */
    public void execute(String cql) {
        session.execute(cql);
    }

    private static CqlSession buildSession(String contactPoints, String localDc, int port) {
        DriverConfigLoader configLoader = DriverConfigLoader.programmaticBuilder()
            .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT,
                Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT,
                Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .withString(DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER, localDc)
            .build();
        CqlSessionBuilder builder = CqlSession.builder().withConfigLoader(configLoader);
        for (String host : contactPoints.split(",")) {
            builder.addContactPoint(new InetSocketAddress(host.trim(), port));
        }
        return builder.build();
    }

    /**
     * Discover cluster topology: for each DC, return one sidecar contact point per rack (AZ).
     *
     * <p>Groups all nodes by DC, then by rack. Takes the first node per rack as the
     * representative sidecar host (port 9043). This gives one contact point per AZ per DC,
     * matching the Cassandra write path without redundant entries.
     *
     * @param contactPoints Comma-separated list of Cassandra host IPs for the initial connection
     * @param localDc Datacenter name for the driver's load-balancing policy
     * @return Map of DC name to list of "host:9043" sidecar addresses (one per AZ)
     */
    public static Map<String, List<String>> discoverTopology(String contactPoints, String localDc) {
        return discoverTopology(contactPoints, localDc, CQL_PORT);
    }

    // Package-private for testing with a non-standard port (e.g. TestContainers mapped port).
    static Map<String, List<String>> discoverTopology(String contactPoints, String localDc, int port) {
        LOGGER.info("Discovering cluster topology via {}:{} (DC: {})", contactPoints, port, localDc);

        try (CqlSession session = buildSession(contactPoints, localDc, port)) {
            Map<String, List<String>> topology = buildTopologyFromNodes(
                session.getMetadata().getNodes().values());

            if (topology.isEmpty()) {
                LOGGER.warn("No nodes discovered — check contactPoints='{}' and localDc='{}'",
                    contactPoints, localDc);
                LOGGER.warn("Tip: with EC2Snitch the DC name is the AWS region (e.g. 'us-west-2')");
            }

            LOGGER.info("Topology discovery complete: {} DC(s) found", topology.size());
            return topology;
        } catch (Exception e) {
            LOGGER.error("Failed to discover cluster topology");
            LOGGER.error("  Contact points: {}:{}", contactPoints, port);
            LOGGER.error("  Local DC: {}", localDc);
            LOGGER.error("  Tip: verify the DC name with 'nodetool status' on a Cassandra node");
            LOGGER.error("  Tip: with EC2Snitch the DC name is the AWS region (e.g. 'us-west-2')");
            throw new RuntimeException(
                "Failed to discover cluster topology from " + contactPoints +
                " (datacenter: " + localDc + "): " + e.getMessage(), e);
        }
    }

    // Package-private for testing: groups nodes by DC, deduplicates by rack, returns topology.
    static Map<String, List<String>> buildTopologyFromNodes(Collection<Node> nodes) {
        Map<String, Map<String, String>> dcRackHost = new LinkedHashMap<>();

        for (Node node : nodes) {
            String dc = node.getDatacenter();
            String rack = node.getRack();
            if (dc == null || rack == null) continue;

            String ip = node.getBroadcastRpcAddress()
                .map(addr -> addr.getHostString())
                .orElse(null);
            if (ip == null) {
                LOGGER.warn("Node in DC '{}' has no broadcastRpcAddress — skipping", dc);
                continue;
            }

            boolean added = dcRackHost
                .computeIfAbsent(dc, d -> new LinkedHashMap<>())
                .putIfAbsent(rack, ip) == null;
            if (added) {
                LOGGER.debug("  DC '{}' rack '{}' → sidecar contact: {}:{}", dc, rack, ip, SIDECAR_PORT);
            }
        }

        Map<String, List<String>> topology = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> dcEntry : dcRackHost.entrySet()) {
            List<String> sidecarPoints = new ArrayList<>();
            for (String ip : dcEntry.getValue().values()) {
                sidecarPoints.add(ip + ":" + SIDECAR_PORT);
            }
            topology.put(dcEntry.getKey(), sidecarPoints);
            LOGGER.info("  DC '{}': {} rack(s) → sidecar contact points: {}",
                dcEntry.getKey(), sidecarPoints.size(), sidecarPoints);
        }
        return topology;
    }

    @Override
    public void close() {
        if (session != null && !session.isClosed()) {
            session.close();
            LOGGER.info("Disconnected from Cassandra");
        }
    }
}
