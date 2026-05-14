package com.rustyrazorblade.easydblab.spark;

import com.datastax.oss.driver.api.core.metadata.Node;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Tests for CqlSetup.buildCreateKeyspaceCql and CqlSetup.buildTopologyFromNodes

class CqlSetupTopologyTest {

    private Node node(String dc, String rack, String ip) {
        Node n = mock(Node.class);
        when(n.getDatacenter()).thenReturn(dc);
        when(n.getRack()).thenReturn(rack);
        when(n.getBroadcastRpcAddress()).thenReturn(
            Optional.of(new InetSocketAddress(ip, 9042)));
        return n;
    }

    @Test
    void singleDcSingleRack_returnsOneSidecar() {
        Map<String, List<String>> topology = CqlSetup.buildTopologyFromNodes(
            List.of(node("dc1", "rack1", "10.0.0.1")));

        assertThat(topology).containsOnlyKeys("dc1");
        assertThat(topology.get("dc1")).containsExactly("10.0.0.1:9043");
    }

    @Test
    void duplicateRackInSameDc_onlyFirstKept() {
        Map<String, List<String>> topology = CqlSetup.buildTopologyFromNodes(List.of(
            node("dc1", "rack1", "10.0.0.1"),
            node("dc1", "rack1", "10.0.0.2"),
            node("dc1", "rack2", "10.0.0.3")));

        assertThat(topology.get("dc1")).containsExactlyInAnyOrder("10.0.0.1:9043", "10.0.0.3:9043");
    }

    @Test
    void multipleDcs_eachDcInResult() {
        Map<String, List<String>> topology = CqlSetup.buildTopologyFromNodes(List.of(
            node("us-west-2", "rack1", "10.0.0.1"),
            node("us-east-1", "rack1", "10.1.0.1")));

        assertThat(topology).containsOnlyKeys("us-west-2", "us-east-1");
        assertThat(topology.get("us-west-2")).containsExactly("10.0.0.1:9043");
        assertThat(topology.get("us-east-1")).containsExactly("10.1.0.1:9043");
    }

    @Test
    void nodeWithNullDc_isSkipped() {
        Map<String, List<String>> topology = CqlSetup.buildTopologyFromNodes(List.of(
            node(null, "rack1", "10.0.0.1"),
            node("dc1", "rack1", "10.0.0.2")));

        assertThat(topology).containsOnlyKeys("dc1");
    }

    @Test
    void nodeWithNoRpcAddress_isSkipped() {
        Node noAddr = mock(Node.class);
        when(noAddr.getDatacenter()).thenReturn("dc1");
        when(noAddr.getRack()).thenReturn("rack1");
        when(noAddr.getBroadcastRpcAddress()).thenReturn(Optional.empty());

        Map<String, List<String>> topology = CqlSetup.buildTopologyFromNodes(List.of(
            noAddr,
            node("dc1", "rack2", "10.0.0.1")));

        assertThat(topology.get("dc1")).containsExactly("10.0.0.1:9043");
    }

    @Test
    void emptyNodeList_returnsEmptyTopology() {
        Map<String, List<String>> topology = CqlSetup.buildTopologyFromNodes(List.of());

        assertThat(topology).isEmpty();
    }

    @Test
    void buildCreateKeyspaceCql_singleDc() {
        String cql = CqlSetup.buildCreateKeyspaceCql("my_ks", List.of("dc1"), 3);

        assertThat(cql).isEqualTo(
            "CREATE KEYSPACE IF NOT EXISTS my_ks WITH replication = " +
            "{'class': 'NetworkTopologyStrategy', 'dc1': 3}");
    }

    @Test
    void buildCreateKeyspaceCql_multiDc_allDcsIncluded() {
        String cql = CqlSetup.buildCreateKeyspaceCql("my_ks", List.of("us-west-2", "us-east-1"), 3);

        assertThat(cql).isEqualTo(
            "CREATE KEYSPACE IF NOT EXISTS my_ks WITH replication = " +
            "{'class': 'NetworkTopologyStrategy', 'us-west-2': 3, 'us-east-1': 3}");
    }

    @Test
    void buildCreateKeyspaceCql_replicationFactorAppliedToEachDc() {
        String cql = CqlSetup.buildCreateKeyspaceCql("ks", List.of("dc1", "dc2", "dc3"), 2);

        assertThat(cql).contains("'dc1': 2");
        assertThat(cql).contains("'dc2': 2");
        assertThat(cql).contains("'dc3': 2");
    }
}
