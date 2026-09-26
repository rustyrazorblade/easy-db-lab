package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Runs LogQL queries against the cluster's Loki, in the cluster's tenant, for the commands that
 * print logs (`logs query`, `spark logs`, the failed EMR step lookup). Build the query with [LogQl].
 */
interface LokiQueryService {
    /**
     * Runs [logql] over the last [since] (a duration such as `1h`, `30m`, `1d`) and returns up to
     * [limit] lines, newest first, formatted for display.
     */
    fun query(
        logql: String,
        since: String,
        limit: Int,
    ): Result<List<String>>
}

/** [LokiQueryService] over [ObservabilityHttp] (control node, tunnel, tenant header). */
class DefaultLokiQueryService(
    private val http: ObservabilityHttp,
) : LokiQueryService {
    private val json = Json { ignoreUnknownKeys = true }

    override fun query(
        logql: String,
        since: String,
        limit: Int,
    ): Result<List<String>> =
        runCatching {
            log.info { "Querying Loki: $logql" }
            val params =
                listOf("query" to logql, "since" to since, "limit" to limit.toString(), "direction" to "backward")
                    .joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
            val response = http.get(Constants.K8s.LOKI_HTTP_PORT, "$QUERY_PATH?$params")
            check(response.code == Constants.HttpStatus.OK) {
                "Loki query failed with status ${response.code}: ${response.body}"
            }
            val parsed = json.decodeFromString<LokiResponse>(response.body)
            check(parsed.status == "success") { "Loki query error: ${response.body}" }
            parsed.data
                ?.result
                .orEmpty()
                .flatMap { stream -> stream.values.map { (nanos, line) -> Entry(nanos.toLong(), stream.stream, line) } }
                .sortedByDescending { it.nanos }
                .take(limit)
                .map { it.format() }
        }

    /** One line and the labels of its stream. */
    private data class Entry(
        val nanos: Long,
        val labels: Map<String, String>,
        val line: String,
    ) {
        /** `[time] [source] [host] [unit] line`, leaving out whatever the stream does not carry. */
        fun format(): String =
            buildString {
                append("[").append(Instant.ofEpochSecond(0, nanos).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).append("] ")
                listOf("source", "host_name", "systemd_unit").mapNotNull { labels[it] }.forEach { append("[").append(it).append("] ") }
                append(line)
            }
    }

    @Serializable
    private data class LokiResponse(
        val status: String,
        val data: LokiData? = null,
    )

    @Serializable
    private data class LokiData(
        val result: List<LokiStream> = emptyList(),
    )

    @Serializable
    private data class LokiStream(
        val stream: Map<String, String> = emptyMap(),
        val values: List<List<String>> = emptyList(),
    )

    private companion object {
        const val QUERY_PATH = "/loki/api/v1/query_range"
    }
}
