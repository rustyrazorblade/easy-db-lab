package com.rustyrazorblade.easydblab.configuration.grafana

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GrafanaDatasourceConfigTest {
    @Test
    fun `createDatasourceConfig should include all six datasources`() {
        val config = GrafanaDatasourceConfig.create(region = "us-east-1")

        assertThat(config.datasources).hasSize(6)
        assertThat(config.datasources.map { it.name }).containsExactly(
            "VictoriaMetrics",
            "VictoriaLogs",
            "ClickHouse",
            "Tempo",
            "CloudWatch",
            "Pyroscope",
        )
    }

    @Test
    fun `createDatasourceConfig should set CloudWatch region`() {
        val config = GrafanaDatasourceConfig.create(region = "eu-west-1")

        val cw = config.datasources.first { it.name == "CloudWatch" }
        assertThat(cw.jsonData).containsEntry("defaultRegion", "eu-west-1")
        assertThat(cw.jsonData).containsEntry("authType", "default")
    }

    @Test
    fun `toYaml should produce valid YAML output`() {
        val config = GrafanaDatasourceConfig.create(region = "us-west-2")
        val yaml = config.toYaml()

        assertThat(yaml).contains("apiVersion: 1")
        assertThat(yaml).contains("defaultRegion")
        assertThat(yaml).contains("us-west-2")
    }
}
