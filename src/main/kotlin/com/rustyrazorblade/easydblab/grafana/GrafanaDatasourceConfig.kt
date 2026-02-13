package com.rustyrazorblade.easydblab.grafana

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Grafana datasource provisioning configuration.
 * Serialized to YAML and applied as a ConfigMap for Grafana's provisioning system.
 */
@Serializable
data class GrafanaDatasourceConfig(
    val apiVersion: Int = 1,
    val datasources: List<GrafanaDatasource>,
) {
    /**
     * Serializes this config to YAML string for embedding in a K8s ConfigMap.
     */
    fun toYaml(): String {
        val yaml =
            Yaml(
                configuration =
                    YamlConfiguration(
                        encodeDefaults = true,
                    ),
            )
        return yaml.encodeToString(this)
    }

    companion object {
        /**
         * Creates the full Grafana datasource configuration with all datasources.
         *
         * @param region AWS region for the CloudWatch datasource
         * @return Complete datasource config ready for serialization
         */
        fun create(region: String): GrafanaDatasourceConfig =
            GrafanaDatasourceConfig(
                datasources =
                    listOf(
                        GrafanaDatasource(
                            name = "VictoriaMetrics",
                            type = "prometheus",
                            uid = "VictoriaMetrics",
                            url = "http://localhost:8428",
                            isDefault = true,
                        ),
                        GrafanaDatasource(
                            name = "VictoriaLogs",
                            type = "victoriametrics-logs-datasource",
                            uid = "victorialogs",
                            url = "http://localhost:9428",
                        ),
                        GrafanaDatasource(
                            name = "ClickHouse",
                            type = "grafana-clickhouse-datasource",
                            jsonData =
                                mapOf(
                                    "defaultDatabase" to "default",
                                    "port" to "9000",
                                    "protocol" to "native",
                                    "host" to "db0",
                                ),
                        ),
                        GrafanaDatasource(
                            name = "Tempo",
                            type = "tempo",
                            uid = "tempo",
                            url = "http://localhost:3200",
                        ),
                        GrafanaDatasource(
                            name = "CloudWatch",
                            type = "cloudwatch",
                            uid = "cloudwatch",
                            jsonData =
                                mapOf(
                                    "defaultRegion" to region,
                                    "authType" to "default",
                                ),
                        ),
                    ),
            )
    }
}

/**
 * A single Grafana datasource definition.
 */
@Serializable
data class GrafanaDatasource(
    val name: String,
    val type: String,
    val access: String = "proxy",
    val url: String? = null,
    val uid: String? = null,
    @SerialName("isDefault")
    val isDefault: Boolean? = null,
    val editable: Boolean = false,
    val jsonData: Map<String, String>? = null,
)
