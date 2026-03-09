package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * Response format from VictoriaMetrics /api/v1/query (Prometheus HTTP API).
 */
@Serializable
data class PromQueryResponse(
    val status: String,
    val data: PromQueryData? = null,
    val error: String? = null,
    val errorType: String? = null,
)

@Serializable
data class PromQueryData(
    val resultType: String,
    val result: List<PromQueryResult>,
)

@Serializable
data class PromQueryResult(
    val metric: Map<String, String> = emptyMap(),
    val value: List<kotlinx.serialization.json.JsonElement> = emptyList(),
) {
    /**
     * Returns the numeric value from the [timestamp, value] pair.
     * VictoriaMetrics returns value as a string in the second element.
     */
    fun numericValue(): Double? =
        value
            .getOrNull(1)
            ?.let { element ->
                val str = element.toString().trim('"')
                if (str == "NaN" || str == "+Inf" || str == "-Inf") null else str.toDoubleOrNull()
            }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Service for executing PromQL instant queries against VictoriaMetrics.
 *
 * Uses the existing SOCKS proxy infrastructure to reach the cluster-internal
 * VictoriaMetrics instance via the control node.
 */
interface VictoriaMetricsQueryService {
    /**
     * Execute a PromQL instant query.
     * Returns parsed results or a failure.
     */
    fun query(
        controlHost: ClusterHost,
        promql: String,
    ): Result<List<PromQueryResult>>
}

class DefaultVictoriaMetricsQueryService(
    private val socksProxyService: SocksProxyService,
    private val httpClientFactory: HttpClientFactory,
) : VictoriaMetricsQueryService {
    override fun query(
        controlHost: ClusterHost,
        promql: String,
    ): Result<List<PromQueryResult>> =
        runCatching {
            socksProxyService.ensureRunning(controlHost)
            val httpClient = httpClientFactory.createClient()

            val encodedQuery = URLEncoder.encode(promql, StandardCharsets.UTF_8)
            val url =
                "http://${controlHost.privateIp}:${Constants.K8s.VICTORIAMETRICS_PORT}" +
                    "${Constants.Victoria.METRICS_QUERY_PATH}?query=$encodedQuery"

            log.debug { "PromQL query: $promql" }

            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.code != HTTP_OK) {
                    throw RuntimeException(
                        "VictoriaMetrics query failed with status ${response.code}: $body",
                    )
                }

                val parsed = json.decodeFromString<PromQueryResponse>(body)
                if (parsed.status != "success") {
                    throw RuntimeException(
                        "VictoriaMetrics query error: ${parsed.errorType}: ${parsed.error}",
                    )
                }

                parsed.data?.result ?: emptyList()
            }
        }

    companion object {
        private const val HTTP_OK = 200
    }
}
