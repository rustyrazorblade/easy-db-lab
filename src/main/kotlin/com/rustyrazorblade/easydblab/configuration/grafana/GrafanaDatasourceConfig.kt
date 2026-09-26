package com.rustyrazorblade.easydblab.configuration.grafana

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.LogQl
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Grafana datasource provisioning configuration.
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

    companion object {
        /** The custom header slot Grafana sends the tenant in (`httpHeaderName1`/`httpHeaderValue1`). */
        private const val TENANT_HEADER_VALUE_KEY = "httpHeaderValue1"

        private typealias Uid = Constants.Grafana.DatasourceUid

        /**
         * Creates the full Grafana datasource configuration with all datasources: Mimir (metrics),
         * Loki (logs), Tempo (traces) and Pyroscope (profiles).
         *
         * Every backend runs native multi-tenancy, so each datasource sends [tenant] in
         * `X-Scope-OrgID` on every query; without it they would read no data.
         *
         * @param tenant The cluster's observability tenant.
         * @return Complete datasource config ready for serialization
         */
        fun create(tenant: String): GrafanaDatasourceConfig {
            // Every backend runs native multi-tenancy: each datasource sends the tenant on every query.
            val tenantHeader = GrafanaDatasourceJsonData(httpHeaderName1 = Constants.Observability.TENANT_HEADER)
            val tenantValue = mapOf(TENANT_HEADER_VALUE_KEY to tenant)
            return GrafanaDatasourceConfig(
                datasources =
                    listOf(
                        GrafanaDatasource(
                            name = "Mimir",
                            type = "prometheus",
                            uid = Uid.MIMIR,
                            url = "http://localhost:${Constants.K8s.MIMIR_HTTP_PORT}/prometheus",
                            isDefault = true,
                            jsonData = tenantHeader.copy(httpMethod = "POST"),
                            secureJsonData = tenantValue,
                        ),
                        GrafanaDatasource(
                            name = "Loki",
                            type = "loki",
                            uid = Uid.LOKI,
                            url = "http://localhost:${Constants.K8s.LOKI_HTTP_PORT}",
                            secureJsonData = tenantValue,
                            // Loki marks each line's level itself (detected_level), so no level rules.
                            jsonData =
                                tenantHeader.copy(
                                    derivedFields =
                                        listOf(
                                            GrafanaDerivedField(
                                                name = "trace_id",
                                                // The collector keeps trace_id as structured metadata.
                                                matcherType = "label",
                                                matcherRegex = "trace_id",
                                                // `$$` escapes Grafana's provisioning-time env expansion.
                                                url = "\$\${__value.raw}",
                                                datasourceUid = Uid.TEMPO,
                                                urlDisplayLabel = "View Trace in Tempo",
                                            ),
                                        ),
                                ),
                        ),
                        GrafanaDatasource(
                            name = "Tempo",
                            type = "tempo",
                            uid = Uid.TEMPO,
                            url = "http://localhost:${Constants.K8s.TEMPO_PORT}",
                            secureJsonData = tenantValue,
                            jsonData =
                                tenantHeader.copy(
                                    serviceMap = GrafanaServiceMapConfig(datasourceUid = Uid.MIMIR),
                                    nodeGraph = GrafanaNodeGraphConfig(enabled = true),
                                    tracesToLogsV2 =
                                        GrafanaTracesToLogsConfig(
                                            datasourceUid = Uid.LOKI,
                                            spanStartTimeShift = "-1m",
                                            spanEndTimeShift = "1m",
                                            filterByTraceID = true,
                                            filterBySpanID = false,
                                            customQuery = true,
                                            // `$$` escapes Grafana's provisioning-time env expansion,
                                            // which would otherwise turn the macro into an empty string.
                                            query = LogQl.traceToLogs("\$\${__trace.traceId}"),
                                        ),
                                    tracesToMetrics =
                                        GrafanaTracesToMetricsConfig(
                                            datasourceUid = Uid.MIMIR,
                                            spanStartTimeShift = "-1m",
                                            spanEndTimeShift = "1m",
                                            queries =
                                                listOf(
                                                    GrafanaTraceMetricQuery(
                                                        name = "Request rate",
                                                        query = "rate(traces_spanmetrics_calls_total{\$\$__tags}[5m])",
                                                    ),
                                                    GrafanaTraceMetricQuery(
                                                        name = "p99 latency",
                                                        query =
                                                            "histogram_quantile(0.99, sum(rate(" +
                                                                "traces_spanmetrics_duration_milliseconds_bucket{\$\$__tags}[5m])) by (le))",
                                                    ),
                                                ),
                                        ),
                                ),
                        ),
                        GrafanaDatasource(
                            name = "Pyroscope",
                            type = "grafana-pyroscope-datasource",
                            uid = Uid.PYROSCOPE,
                            url = "http://localhost:${Constants.K8s.PYROSCOPE_PORT}",
                            jsonData = tenantHeader,
                            secureJsonData = tenantValue,
                        ),
                    ),
            )
        }
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
