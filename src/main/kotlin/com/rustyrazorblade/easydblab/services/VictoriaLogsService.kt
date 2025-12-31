package com.rustyrazorblade.easydblab.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Log entry returned from Victoria Logs query.
 */
data class LogEntry(
    val timestamp: String,
    val message: String,
    val source: String? = null,
    val host: String? = null,
    val unit: String? = null,
    val component: String? = null,
    val emrClusterId: String? = null,
    val stepId: String? = null,
    val logType: String? = null,
)

/**
 * Service interface for querying Victoria Logs.
 *
 * Victoria Logs runs on the control node and provides a query API
 * for searching logs from all sources (Cassandra, systemd, EMR, etc.).
 */
interface VictoriaLogsService {
    /**
     * Query logs from Victoria Logs.
     *
     * The service automatically connects to the control node where Victoria Logs runs.
     *
     * @param query The LogsQL query string (e.g., "source:cassandra AND host:db0")
     * @param timeRange Time range to query (e.g., "1h", "30m", "1d")
     * @param limit Maximum number of log entries to return
     * @return Result containing list of formatted log lines
     */
    fun query(
        query: String,
        timeRange: String,
        limit: Int,
    ): Result<List<String>>
}

/**
 * Default implementation of VictoriaLogsService that queries Victoria Logs via SOCKS proxy.
 *
 * This implementation connects to Victoria Logs through the SOCKS5 proxy using OkHttp,
 * which has native SOCKS5 proxy support (unlike Java's HttpClient).
 *
 * @property socksProxyService Service for managing the SOCKS5 proxy connection
 * @property httpClientFactory Factory for creating HTTP clients that use the proxy
 * @property clusterStateManager Manager for loading cluster state to find control node
 */
class DefaultVictoriaLogsService(
    private val socksProxyService: SocksProxyService,
    private val httpClientFactory: HttpClientFactory,
    private val clusterStateManager: ClusterStateManager,
) : VictoriaLogsService {
    private val log = KotlinLogging.logger {}
    private val mapper = jacksonObjectMapper()

    companion object {
        private const val VICTORIA_LOGS_PORT = 9428
        private const val QUERY_ENDPOINT = "/select/logsql/query"
        private const val HTTP_OK = 200
    }

    override fun query(
        query: String,
        timeRange: String,
        limit: Int,
    ): Result<List<String>> =
        runCatching {
            // Get control node from cluster state
            val clusterState = clusterStateManager.load()
            val controlHosts = clusterState.hosts[ServerType.Control]
            if (controlHosts.isNullOrEmpty()) {
                error("No control nodes found. Please ensure the environment is running.")
            }
            val controlHost = controlHosts.first()

            // Ensure SOCKS proxy is running
            log.info { "Starting SOCKS proxy to ${controlHost.alias} (${controlHost.publicIp})..." }
            socksProxyService.ensureRunning(controlHost)
            val proxyPort = socksProxyService.getLocalPort()
            log.info { "SOCKS proxy running on 127.0.0.1:$proxyPort" }

            // Create OkHttp client with SOCKS proxy
            val httpClient = httpClientFactory.createClient()

            // Build the request URL
            val url = buildQueryUrl(controlHost.privateIp, query, timeRange, limit)
            log.info { "Querying Victoria Logs: $url via SOCKS proxy on port $proxyPort" }

            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code != HTTP_OK) {
                    throw RuntimeException(
                        "Victoria Logs query failed with status ${response.code}: ${response.body?.string()}",
                    )
                }

                // Parse the NDJSON response (newline-delimited JSON)
                val body = response.body?.string() ?: ""
                parseNdjsonResponse(body)
            }
        }

    /**
     * Builds the Victoria Logs query URL.
     */
    private fun buildQueryUrl(
        targetHost: String,
        query: String,
        timeRange: String,
        limit: Int,
    ): String {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return "http://$targetHost:$VICTORIA_LOGS_PORT$QUERY_ENDPOINT" +
            "?query=$encodedQuery" +
            "&time=$timeRange" +
            "&limit=$limit"
    }

    /**
     * Parses NDJSON (newline-delimited JSON) response from Victoria Logs.
     * Each line is a JSON object representing a log entry.
     */
    private fun parseNdjsonResponse(output: String): List<String> {
        if (output.isBlank()) {
            return emptyList()
        }

        return output
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    val entry = parseLogEntry(line)
                    formatLogEntry(entry)
                } catch (e: Exception) {
                    log.warn { "Failed to parse log entry: $line" }
                    null
                }
            }
    }

    /**
     * Parses a single JSON log entry.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseLogEntry(json: String): LogEntry {
        val map: Map<String, Any?> = mapper.readValue(json)

        return LogEntry(
            timestamp = map["_time"]?.toString() ?: map["timestamp"]?.toString() ?: "",
            message = map["_msg"]?.toString() ?: map["message"]?.toString() ?: "",
            source = map["source"]?.toString(),
            host = map["host"]?.toString(),
            unit = map["unit"]?.toString(),
            component = map["component"]?.toString(),
            emrClusterId = map["emr_cluster_id"]?.toString(),
            stepId = map["step_id"]?.toString(),
            logType = map["log_type"]?.toString(),
        )
    }

    /**
     * Formats a log entry for display.
     */
    private fun formatLogEntry(entry: LogEntry): String =
        buildString {
            // Format: [timestamp] [source] [host] message
            if (entry.timestamp.isNotBlank()) {
                append("[${entry.timestamp}] ")
            }
            if (entry.source != null) {
                append("[${entry.source}] ")
            }
            if (entry.host != null) {
                append("[${entry.host}] ")
            }
            if (entry.unit != null) {
                append("[${entry.unit}] ")
            }
            append(entry.message)
        }
}
