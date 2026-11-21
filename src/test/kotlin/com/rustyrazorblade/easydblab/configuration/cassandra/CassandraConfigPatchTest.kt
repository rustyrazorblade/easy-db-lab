package com.rustyrazorblade.easydblab.configuration.cassandra

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two halves of a cassandra.yaml patch: [CassandraConfigPatch.common], which both paths share,
 * and [CassandraConfigPatch.placement], which holds only what differs between the VM path and the
 * cassandra-pods kit. Config parity (the kit's cassandra.yaml differs only in addresses, ports,
 * paths and the snitch) rests on every key landing in the right half.
 */
class CassandraConfigPatchTest {
    private val reader = ObjectMapper(YAMLFactory())

    private fun parse(patch: CassandraPatch): JsonNode = reader.readTree(patch.toYaml())

    private val placementKeys =
        setOf(
            "seed_provider",
            "hints_directory",
            "data_file_directories",
            "commitlog_directory",
            "saved_caches_directory",
            "endpoint_snitch",
            "native_transport_port",
        )

    @Test
    fun `common carries the cluster name, tokens, concurrency and trickle_fsync and nothing placement owns`() {
        val yaml = parse(CassandraConfigPatch.common(clusterName = "lab", tokens = 8))

        assertThat(yaml.fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("cluster_name", "num_tokens", "concurrent_reads", "concurrent_writes", "trickle_fsync")
        assertThat(yaml["cluster_name"].asText()).isEqualTo("lab")
        assertThat(yaml["num_tokens"].asInt()).isEqualTo(8)
        assertThat(yaml["concurrent_reads"].asInt()).isEqualTo(64)
        assertThat(yaml["concurrent_writes"].asInt()).isEqualTo(64)
        assertThat(yaml["trickle_fsync"].asBoolean()).isTrue()
    }

    @Test
    fun `the VM placement holds the seed IPs, the mnt db1 directories and Ec2Snitch, and leaves the native port alone`() {
        val yaml = parse(CassandraConfigPatch.placement(CassandraPlacement.Vm(seedIps = listOf("10.0.1.1", "10.0.1.2"))))

        assertThat(yaml.fieldNames().asSequence().toSet()).isSubsetOf(placementKeys).doesNotContain("native_transport_port")
        assertThat(yaml["seed_provider"]["class_name"].asText()).isEqualTo("org.apache.cassandra.locator.SimpleSeedProvider")
        assertThat(yaml["seed_provider"]["parameters"]["seeds"].asText()).isEqualTo("10.0.1.1,10.0.1.2")
        assertThat(yaml["data_file_directories"].map { it.asText() }).containsExactly("/mnt/db1/cassandra/data")
        assertThat(yaml["commitlog_directory"].asText()).isEqualTo("/mnt/db1/cassandra/commitlog")
        assertThat(yaml["hints_directory"].asText()).isEqualTo("/mnt/db1/cassandra/hints")
        assertThat(yaml["endpoint_snitch"].asText()).isEqualTo("Ec2Snitch")
    }

    @Test
    fun `the pods placement holds the pod-0 DNS seed, the var lib cassandra directories, the gossiping snitch and the NodePort`() {
        val seed = "cassandra-pods-0.cassandra-pods-gossip.default.svc.cluster.local"
        val yaml = parse(CassandraConfigPatch.placement(CassandraPlacement.Pods(seedDns = seed)))

        assertThat(yaml.fieldNames().asSequence().toSet()).isEqualTo(placementKeys)
        assertThat(yaml["seed_provider"]["parameters"]["seeds"].asText()).isEqualTo(seed)
        assertThat(yaml["data_file_directories"].map { it.asText() }).containsExactly("/var/lib/cassandra/data")
        assertThat(yaml["commitlog_directory"].asText()).isEqualTo("/var/lib/cassandra/commitlog")
        assertThat(yaml["hints_directory"].asText()).isEqualTo("/var/lib/cassandra/hints")
        assertThat(yaml["saved_caches_directory"].asText()).isEqualTo("/var/lib/cassandra/saved_caches")
        assertThat(yaml["endpoint_snitch"].asText()).isEqualTo("GossipingPropertyFileSnitch")
        assertThat(yaml["native_transport_port"].asInt()).isEqualTo(30942)
    }

    @Test
    fun `plus keeps both halves, with the right-hand patch winning a key both set`() {
        val merged =
            CassandraConfigPatch.common("lab", 4) +
                CassandraConfigPatch.placement(CassandraPlacement.Vm(seedIps = listOf("10.0.1.1")))
        val yaml = parse(merged)

        assertThat(yaml["cluster_name"].asText()).isEqualTo("lab")
        assertThat(yaml["endpoint_snitch"].asText()).isEqualTo("Ec2Snitch")

        val override = merged + CassandraPatch(endpointSnitch = "GossipingPropertyFileSnitch")
        assertThat(parse(override)["endpoint_snitch"].asText()).isEqualTo("GossipingPropertyFileSnitch")
        assertThat(parse(override)["cluster_name"].asText()).isEqualTo("lab")
    }
}
