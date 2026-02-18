package com.rustyrazorblade.easydblab.grafana

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
    fun `createDatasourceConfig should set VictoriaMetrics as default with explicit uid`() {
        val config = GrafanaDatasourceConfig.create(region = "us-east-1")

        val vm = config.datasources.first { it.name == "VictoriaMetrics" }
        assertThat(vm.isDefault).isTrue()
        assertThat(vm.uid).isEqualTo("VictoriaMetrics")
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

    @Test
    fun `createDatasourceConfig should set Pyroscope datasource correctly`() {
        val config = GrafanaDatasourceConfig.create(region = "us-east-1")

        val pyroscope = config.datasources.first { it.name == "Pyroscope" }
        assertThat(pyroscope.type).isEqualTo("grafana-pyroscope-datasource")
        assertThat(pyroscope.uid).isEqualTo("pyroscope")
        assertThat(pyroscope.url).isEqualTo("http://localhost:4040")
    }

    @Test
    fun `createDatasourceConfig should set ClickHouse jsonData correctly`() {
        val config = GrafanaDatasourceConfig.create(region = "us-east-1")

        val ch = config.datasources.first { it.name == "ClickHouse" }
        assertThat(ch.jsonData).containsEntry("defaultDatabase", "default")
        assertThat(ch.jsonData).containsEntry("port", "9000")
        assertThat(ch.jsonData).containsEntry("protocol", "native")
        assertThat(ch.jsonData).containsEntry("host", "db0")
    }
}
