package com.rustyrazorblade.easydblab.configuration.grafana

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
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
        /**
         * Creates the full Grafana datasource configuration with all datasources.
         *
         * @return Complete datasource config ready for serialization
         */
        fun create(): GrafanaDatasourceConfig =
            GrafanaDatasourceConfig(
                datasources =
                    listOf(
                        GrafanaDatasource(
                            name = "VictoriaMetrics",
                            type = "prometheus",
                            uid = "VictoriaMetrics",
                            url = "http://localhost:8428",
                            isDefault = true,
                            jsonData = GrafanaDatasourceJsonData(httpMethod = "POST"),
                        ),
                        GrafanaDatasource(
                            name = "VictoriaLogs",
                            type = "victoriametrics-logs-datasource",
                            uid = "victorialogs",
                            url = "http://localhost:9428",
                            jsonData =
                                GrafanaDatasourceJsonData(
                                    derivedFields =
                                        listOf(
                                            GrafanaDerivedField(
                                                name = "trace_id",
                                                field = "trace_id",
                                                matcherRegex = "(.*)",
                                                url = "",
                                                datasourceUid = "tempo",
                                                urlDisplayLabel = "View Trace in Tempo",
                                            ),
                                        ),
                                    logLevelRules =
                                        listOf(
                                            GrafanaLogLevelRule("severity", "caseInsensitiveEquals", "error", "error"),
                                            GrafanaLogLevelRule("severity", "caseInsensitiveEquals", "warn", "warning"),
                                            GrafanaLogLevelRule("severity", "caseInsensitiveEquals", "warning", "warning"),
                                            GrafanaLogLevelRule("severity", "caseInsensitiveEquals", "info", "info"),
                                            GrafanaLogLevelRule("severity", "caseInsensitiveEquals", "debug", "debug"),
                                            GrafanaLogLevelRule("severity", "caseInsensitiveEquals", "trace", "trace"),
                                        ),
                                ),
                        ),
                        GrafanaDatasource(
                            name = "Tempo",
                            type = "tempo",
                            uid = "tempo",
                            url = "http://localhost:3200",
                            jsonData =
                                GrafanaDatasourceJsonData(
                                    serviceMap = GrafanaServiceMapConfig(datasourceUid = "VictoriaMetrics"),
                                    nodeGraph = GrafanaNodeGraphConfig(enabled = true),
                                    tracesToLogsV2 =
                                        GrafanaTracesToLogsConfig(
                                            datasourceUid = "victorialogs",
                                            spanStartTimeShift = "-1m",
                                            spanEndTimeShift = "1m",
                                            filterByTraceID = true,
                                            filterBySpanID = false,
                                            customQuery = true,
                                            query = "trace_id:\"\${__trace.traceId}\"",
                                        ),
                                    tracesToMetrics =
                                        GrafanaTracesToMetricsConfig(
                                            datasourceUid = "VictoriaMetrics",
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
                            uid = "pyroscope",
                            url = "http://localhost:4040",
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
    val jsonData: GrafanaDatasourceJsonData? = null,
)

/** Union of all possible datasource jsonData fields across datasource types. */
@Serializable
data class GrafanaDatasourceJsonData(
    val httpMethod: String? = null,
    val serviceMap: GrafanaServiceMapConfig? = null,
    val nodeGraph: GrafanaNodeGraphConfig? = null,
    val tracesToLogsV2: GrafanaTracesToLogsConfig? = null,
    val tracesToMetrics: GrafanaTracesToMetricsConfig? = null,
    val derivedFields: List<GrafanaDerivedField>? = null,
    val logLevelRules: List<GrafanaLogLevelRule>? = null,
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
    // customQuery bypasses Grafana's default label generation which appends service_name (Loki-style
    // dot→underscore conversion) incompatible with VictoriaLogs' native service.name field naming.
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

/** Defines a derived field that extracts a value from a log record and renders it as a link or datasource reference. */
@Serializable
data class GrafanaDerivedField(
    val name: String,
    val field: String,
    val matcherRegex: String,
    val url: String,
    val datasourceUid: String,
    val urlDisplayLabel: String,
)

/** Maps a log field value to a Grafana log level, used to color the logs volume histogram in Explore. */
@Serializable
data class GrafanaLogLevelRule(
    val field: String,
    val operator: String,
    val value: String,
    val level: String,
)
