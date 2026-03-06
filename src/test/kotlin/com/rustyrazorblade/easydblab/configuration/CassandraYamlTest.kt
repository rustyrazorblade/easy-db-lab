package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class CassandraYamlTest {
    val yaml: CassandraYaml

    init {
        val tmp = this.javaClass.getResourceAsStream("cassandra.yaml")
        yaml = CassandraYaml.create(tmp)
    }

    @Test
    fun `setSeeds writes comma-separated seed list into yaml`() {
        yaml.setSeeds(listOf("192.168.0.1", "192.168.0.2"))

        val seeds =
            yaml.parser
                .get("seed_provider")
                .first()
                .get("parameters")
                .first()
                .get("seeds")
                .asText()
        assertThat(seeds).isEqualTo("192.168.0.1,192.168.0.2")
    }

    @Test
    fun `setProperty writes value into yaml`() {
        yaml.setProperty("cluster_name", "test-cluster")

        assertThat(yaml.parser.get("cluster_name").asText()).isEqualTo("test-cluster")
    }
}
