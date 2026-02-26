package com.rustyrazorblade.easydblab.configuration.grafana

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GrafanaDatasourceConfigTest {
    @Test
    fun `createDatasourceConfig should include all five datasources`() {
        val config = GrafanaDatasourceConfig.create()

        assertThat(config.datasources).hasSize(5)
        assertThat(config.datasources.map { it.name }).containsExactly(
            "VictoriaMetrics",
            "VictoriaLogs",
            "ClickHouse",
            "Tempo",
            "Pyroscope",
        )
    }

    @Test
    fun `toYaml should produce valid YAML output`() {
        val config = GrafanaDatasourceConfig.create()
        val yaml = config.toYaml()

        assertThat(yaml).contains("apiVersion: 1")
        assertThat(yaml).contains("VictoriaMetrics")
        assertThat(yaml).contains("prometheus")
    }
}
