package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.LogQl
import java.security.MessageDigest

/**
 * The tenants Grafana gets datasources for: every tenant in the shared store, and the cluster's own.
 *
 * @property home the cluster's own tenant, which the stable datasources read.
 * @property all every tenant, sorted, [home] included.
 */
data class TenantSet(
    val home: String,
    val all: List<String>,
) {
    init {
        require(home in all) { "the home tenant $home is not among $all" }
        require(all == all.distinct().sorted()) { "tenants must be sorted and distinct: $all" }
    }

    companion object {
        /** The set that holds [home] and every name in [others]. */
        fun of(
            home: String,
            others: Collection<String> = emptyList(),
        ): TenantSet = TenantSet(home, (others + home).distinct().sorted())
    }
}

/**
 * The base URL of each backend, as Grafana reaches it. Only these differ between a cluster and
 * another host of the same datasource set.
 */
data class BackendUrls(
    val metrics: String,
    val logs: String,
    val traces: String,
    val profiles: String,
) {
    companion object {
        /** The backends on the control node, where Grafana runs with the host's network. */
        val CONTROL_NODE =
            BackendUrls(
                metrics = "http://localhost:${Constants.K8s.MIMIR_HTTP_PORT}/prometheus",
                logs = "http://localhost:${Constants.K8s.LOKI_HTTP_PORT}",
                traces = "http://localhost:${Constants.K8s.TEMPO_PORT}",
                profiles = "http://localhost:${Constants.K8s.PYROSCOPE_PORT}",
            )
    }
}

/**
 * Builds Grafana's datasources from the tenants in the shared store and the backends' base URLs.
 *
 * Metrics, logs and traces get three kinds of tenant view:
 * - the stable datasources `mimir`, `loki` and `tempo`, on the cluster's own tenant, so existing
 *   dashboards keep their view;
 * - one per tenant, UID `<signal>-<tenant>`;
 * - one for every tenant, UID `<signal>--all`, which sends the tenants joined with `|`.
 *
 * Each view's links to another signal stay within the view. Profiles have one datasource, on the
 * cluster's own tenant: Pyroscope reads only the blocks its own metastore knows.
 */
object GrafanaDatasourceSet {
    /** The custom header slot Grafana sends the tenant in (`httpHeaderName1`/`httpHeaderValue1`). */
    private const val TENANT_HEADER_VALUE_KEY = "httpHeaderValue1"
    private const val ALL_TENANTS_SUFFIX = "--all"
    private const val HASH_HEX_CHARS = 8

    private typealias Uid = Constants.Grafana.DatasourceUid

    /** The UIDs and display suffix of one tenant view. */
    private data class View(
        val metricsUid: String,
        val logsUid: String,
        val tracesUid: String,
        val nameSuffix: String,
        val orgId: String,
    )

    /**
     * The whole datasource set. Only the URLs depend on [urls].
     *
     * @param tenants every tenant, and the cluster's own.
     * @param urls the base URL of each backend.
     */
    fun build(
        tenants: TenantSet,
        urls: BackendUrls,
    ): GrafanaDatasourceConfig {
        val stable = View(Uid.MIMIR, Uid.LOKI, Uid.TEMPO, "", tenants.home)
        val perTenant =
            tenants.all.map { tenant ->
                View(uid(Uid.MIMIR, tenant), uid(Uid.LOKI, tenant), uid(Uid.TEMPO, tenant), " ($tenant)", tenant)
            }
        val all =
            View(
                Uid.MIMIR + ALL_TENANTS_SUFFIX,
                Uid.LOKI + ALL_TENANTS_SUFFIX,
                Uid.TEMPO + ALL_TENANTS_SUFFIX,
                " (all tenants)",
                tenants.all.joinToString("|"),
            )
        val views = listOf(stable) + perTenant + all
        return GrafanaDatasourceConfig(
            datasources =
                views.flatMap { view ->
                    listOf(metrics(view, urls, isDefault = view == stable), logs(view, urls), traces(view, urls))
                } + profiles(tenants.home, urls),
        )
    }

    /**
     * `<signal>-<tenant>`, or `<signal>-<prefix>-<first 8 hex of sha256(tenant)>` when that is longer
     * than Grafana's UID limit.
     */
    fun uid(
        signal: String,
        tenant: String,
    ): String {
        val plain = "$signal-$tenant"
        if (plain.length <= Constants.Grafana.MAX_UID_LENGTH) return plain
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(tenant.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(HASH_HEX_CHARS)
        val prefixLength = Constants.Grafana.MAX_UID_LENGTH - signal.length - HASH_HEX_CHARS - 2
        return "$signal-${tenant.take(prefixLength)}-$hash"
    }

    private fun header(orgId: String) =
        GrafanaDatasourceJsonData(httpHeaderName1 = Constants.Observability.TENANT_HEADER) to mapOf(TENANT_HEADER_VALUE_KEY to orgId)

    private fun metrics(
        view: View,
        urls: BackendUrls,
        isDefault: Boolean,
    ): GrafanaDatasource {
        val (jsonData, secure) = header(view.orgId)
        return GrafanaDatasource(
            name = "Mimir${view.nameSuffix}",
            type = "prometheus",
            uid = view.metricsUid,
            url = urls.metrics,
            isDefault = if (isDefault) true else null,
            jsonData = jsonData.copy(httpMethod = "POST"),
            secureJsonData = secure,
        )
    }

    private fun logs(
        view: View,
        urls: BackendUrls,
    ): GrafanaDatasource {
        val (jsonData, secure) = header(view.orgId)
        return GrafanaDatasource(
            name = "Loki${view.nameSuffix}",
            type = "loki",
            uid = view.logsUid,
            url = urls.logs,
            secureJsonData = secure,
            // Loki marks each line's level itself (detected_level), so no level rules.
            jsonData =
                jsonData.copy(
                    derivedFields =
                        listOf(
                            GrafanaDerivedField(
                                name = "trace_id",
                                // The collector keeps trace_id as structured metadata.
                                matcherType = "label",
                                matcherRegex = "trace_id",
                                // `$$` escapes Grafana's provisioning-time env expansion.
                                url = "\$\${__value.raw}",
                                datasourceUid = view.tracesUid,
                                urlDisplayLabel = "View Trace in Tempo",
                            ),
                        ),
                ),
        )
    }

    private fun traces(
        view: View,
        urls: BackendUrls,
    ): GrafanaDatasource {
        val (jsonData, secure) = header(view.orgId)
        return GrafanaDatasource(
            name = "Tempo${view.nameSuffix}",
            type = "tempo",
            uid = view.tracesUid,
            url = urls.traces,
            secureJsonData = secure,
            jsonData =
                jsonData.copy(
                    serviceMap = GrafanaServiceMapConfig(datasourceUid = view.metricsUid),
                    nodeGraph = GrafanaNodeGraphConfig(enabled = true),
                    tracesToLogsV2 =
                        GrafanaTracesToLogsConfig(
                            datasourceUid = view.logsUid,
                            spanStartTimeShift = "-1m",
                            spanEndTimeShift = "1m",
                            filterByTraceID = true,
                            filterBySpanID = false,
                            customQuery = true,
                            // `$$` escapes Grafana's provisioning-time env expansion, which would
                            // otherwise turn the macro into an empty string.
                            query = LogQl.traceToLogs("\$\${__trace.traceId}"),
                        ),
                    tracesToMetrics =
                        GrafanaTracesToMetricsConfig(
                            datasourceUid = view.metricsUid,
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
        )
    }

    private fun profiles(
        home: String,
        urls: BackendUrls,
    ): GrafanaDatasource {
        val (jsonData, secure) = header(home)
        return GrafanaDatasource(
            name = "Pyroscope",
            type = "grafana-pyroscope-datasource",
            uid = Uid.PYROSCOPE,
            url = urls.profiles,
            jsonData = jsonData,
            secureJsonData = secure,
        )
    }
}
