package com.rustyrazorblade.easydblab.configuration.grafana

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Grafana datasource provisioning configuration, built by [GrafanaDatasourceSet].
 * Serialized to YAML and applied as a ConfigMap for Grafana's provisioning system.
 */
@Serializable
data class GrafanaDatasourceConfig(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @OptIn(ExperimentalSerializationApi::class)
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
                        encodeDefaults = false,
                    ),
            )
        return yaml.encodeToString(this)
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
    val jsonData: GrafanaDatasourceJsonData? = null,
    /** Values Grafana stores encrypted, such as the tenant header value (`httpHeaderValue1`). */
    val secureJsonData: Map<String, String>? = null,
)

/** Union of all possible datasource jsonData fields across datasource types. */
@Serializable
data class GrafanaDatasourceJsonData(
    val httpMethod: String? = null,
    /** Name of the first custom HTTP header sent on every query; its value is in secureJsonData. */
    val httpHeaderName1: String? = null,
    val serviceMap: GrafanaServiceMapConfig? = null,
    val nodeGraph: GrafanaNodeGraphConfig? = null,
    val tracesToLogsV2: GrafanaTracesToLogsConfig? = null,
    val tracesToMetrics: GrafanaTracesToMetricsConfig? = null,
    val derivedFields: List<GrafanaDerivedField>? = null,
)

/** Links the service map view in Grafana Explore to a Prometheus-compatible datasource. */
@Serializable
data class GrafanaServiceMapConfig(
    val datasourceUid: String,
)

/** Enables the node graph visualization in Grafana Explore for this datasource. */
@Serializable
data class GrafanaNodeGraphConfig(
    val enabled: Boolean,
)

/** Configures trace-to-logs correlation — clicking a span opens a log query in the target datasource. */
@Serializable
data class GrafanaTracesToLogsConfig(
    val datasourceUid: String,
    val spanStartTimeShift: String,
    val spanEndTimeShift: String,
    val filterByTraceID: Boolean,
    val filterBySpanID: Boolean,
    // customQuery replaces Grafana's generated query, which filters on the span's service labels,
    // with a trace-id lookup across the selected clusters.
    val customQuery: Boolean = false,
    val query: String? = null,
)

/** Configures trace-to-metrics correlation — clicking a span shows metric panels from the target datasource. */
@Serializable
data class GrafanaTracesToMetricsConfig(
    val datasourceUid: String,
    val spanStartTimeShift: String,
    val spanEndTimeShift: String,
    val queries: List<GrafanaTraceMetricQuery>,
)

/** A single PromQL query shown in the trace-to-metrics panel. */
@Serializable
data class GrafanaTraceMetricQuery(
    val name: String,
    val query: String,
)

/**
 * A derived field of the Loki datasource: a value taken from each log line, rendered as a link.
 * With `matcherType: label`, [matcherRegex] names the label or structured-metadata key to read.
 */
@Serializable
data class GrafanaDerivedField(
    val name: String,
    val matcherType: String,
    val matcherRegex: String,
    val url: String,
    val datasourceUid: String,
    val urlDisplayLabel: String,
)
