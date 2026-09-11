package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import java.net.URI

/**
 * Life-of-cluster telemetry redirect target.
 *
 * When present on [InitConfig], the cluster ships all four telemetry signals to an external
 * observability stack instead of standing up its own local backends (VictoriaMetrics,
 * VictoriaLogs, Tempo, the Pyroscope server, Grafana). A `null` value on [InitConfig] means
 * local mode — the unchanged default. Nullability of the whole value is what makes "all four
 * signals move together" and "redirect-only for life" structural rather than convention.
 *
 * The four endpoints are stored fully resolved so every downstream step reads a concrete
 * destination and never re-derives one. Use [fromBaseHost] to derive them from a single base
 * host and the known stack ports, with optional per-signal overrides for a non-easy-db-lab
 * target whose layout differs.
 *
 * This is a plain data class (not `@Serializable`) because it is persisted inside `state.json`
 * via the Jackson [ClusterStateManager], like the rest of [InitConfig].
 *
 * @property metrics VictoriaMetrics Prometheus remote-write URL (`http://<host>:8428/api/v1/write`).
 * @property logs VictoriaLogs OTLP ingest URL (`http://<host>:9428/insert/opentelemetry`).
 * @property traces Tempo OTLP gRPC endpoint (`<host>:4320`) — the OTLP receiver port, NOT 3200,
 *   Tempo's query port. No scheme: the OTLP exporter takes a bare host:port.
 * @property profiles Pyroscope ingest base URL (`http://<host>:4040`).
 */
data class TelemetryRedirect(
    val metrics: String = "",
    val logs: String = "",
    val traces: String = "",
    val profiles: String = "",
) {
    /**
     * Validates well-formedness of the four endpoints and returns the names of any that are
     * missing or malformed. An empty list means all four are well-formed.
     *
     * This checks structure only — it never probes live reachability. A well-formed but
     * unreachable endpoint surfaces later as a collector send failure, not a bring-up error.
     *
     * @return the offending signal names (`"metrics"`, `"logs"`, `"traces"`, `"profiles"`),
     *   in signal order; empty when every endpoint is well-formed.
     */
    fun validate(): List<String> =
        buildList {
            if (!isValidHttpUrl(metrics)) add(SIGNAL_METRICS)
            if (!isValidHttpUrl(logs)) add(SIGNAL_LOGS)
            if (!isValidHostPort(traces)) add(SIGNAL_TRACES)
            if (!isValidHttpUrl(profiles)) add(SIGNAL_PROFILES)
        }

    companion object {
        const val SIGNAL_METRICS = "metrics"
        const val SIGNAL_LOGS = "logs"
        const val SIGNAL_TRACES = "traces"
        const val SIGNAL_PROFILES = "profiles"

        /**
         * Derives the four endpoints from one base host and the known [Constants.K8s] ports,
         * honoring optional per-signal overrides. Deriving from the same ports the local stack
         * uses keeps local and redirect in lockstep; an override wins over the derived value so a
         * target whose layout differs is still reachable.
         *
         * Traces derive from [Constants.K8s.TEMPO_OTLP_GRPC_PORT] (4320), never Tempo's query port
         * (3200) — sending OTLP to the query port silently drops every span.
         *
         * @param baseHost external stack host (hostname or IP); overrides may replace any signal.
         */
        fun fromBaseHost(
            baseHost: String,
            metricsOverride: String? = null,
            logsOverride: String? = null,
            tracesOverride: String? = null,
            profilesOverride: String? = null,
        ): TelemetryRedirect =
            TelemetryRedirect(
                metrics = metricsOverride ?: "http://$baseHost:${Constants.K8s.VICTORIAMETRICS_PORT}/api/v1/write",
                logs = logsOverride ?: "http://$baseHost:${Constants.K8s.VICTORIALOGS_PORT}/insert/opentelemetry",
                traces = tracesOverride ?: "$baseHost:${Constants.K8s.TEMPO_OTLP_GRPC_PORT}",
                profiles = profilesOverride ?: "http://$baseHost:${Constants.K8s.PYROSCOPE_PORT}",
            )

        /** True when [value] is a non-blank absolute http/https URL with a host. */
        private fun isValidHttpUrl(value: String): Boolean {
            if (value.isBlank()) return false
            return runCatching {
                val uri = URI(value)
                uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }

        /** True when [value] is a non-blank `host:port` with a numeric port and no scheme. */
        private fun isValidHostPort(value: String): Boolean {
            if (value.isBlank()) return false
            if ("://" in value) return false
            val idx = value.lastIndexOf(':')
            if (idx <= 0 || idx == value.length - 1) return false
            val host = value.substring(0, idx)
            val port = value.substring(idx + 1).toIntOrNull() ?: return false
            return host.isNotBlank() && port in 1..MAX_PORT
        }

        private const val MAX_PORT = 65535
    }
}
