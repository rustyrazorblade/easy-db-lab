package com.rustyrazorblade.easydblab.spark;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CqlSetupIntegrationTest {

    private static final String LOCAL_DC = "datacenter1";

    @Container
    static final CassandraContainer<?> cassandra =
        new CassandraContainer<>(DockerImageName.parse("cassandra:5.0"))
            .withStartupTimeout(java.time.Duration.ofMinutes(3));

    private CqlSetup cqlSetup() {
        return new CqlSetup(cassandra.getHost(), LOCAL_DC, cassandra.getMappedPort(9042));
    }

    @Test
    void createKeyspace_createsNtsWithDiscoveredDc() {
        try (CqlSetup setup = cqlSetup()) {
            setup.createKeyspace("test_ks", 1);
        }

        try (CqlSession session = directSession()) {
            var row = session.execute(
                "SELECT replication FROM system_schema.keyspaces WHERE keyspace_name = 'test_ks'").one();

            assertThat(row).isNotNull();
            Map<String, String> replication = row.getMap("replication", String.class, String.class);
            assertThat(replication).containsEntry("class",
                "org.apache.cassandra.locator.NetworkTopologyStrategy");
            assertThat(replication).containsKey(LOCAL_DC);
            assertThat(replication.get(LOCAL_DC)).isEqualTo("1");
        }
    }

    @Test
    void createKeyspace_idempotent() {
        try (CqlSetup setup = cqlSetup()) {
            setup.createKeyspace("idempotent_ks", 1);
            setup.createKeyspace("idempotent_ks", 1);
        }
        // reaching here without exception is the assertion
    }

    @Test
    void discoverTopology_returnsLocalDc() {
        Map<String, List<String>> topology = CqlSetup.discoverTopology(
            cassandra.getHost(), LOCAL_DC, cassandra.getMappedPort(9042));

        assertThat(topology).containsKey(LOCAL_DC);
        assertThat(topology.get(LOCAL_DC)).isNotEmpty();
        assertThat(topology.get(LOCAL_DC).get(0)).endsWith(":" + CqlSetup.SIDECAR_PORT);
    }

    private CqlSession directSession() {
        return CqlSession.builder()
            .addContactPoint(new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042)))
            .withLocalDatacenter(LOCAL_DC)
            .build();
    }
}
