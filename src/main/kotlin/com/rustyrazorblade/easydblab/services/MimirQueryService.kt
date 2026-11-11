package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/** The body of a Prometheus HTTP API instant query response, as Mimir returns it. */
@Serializable
data class PromQueryResponse(
    val status: String,
    val data: PromQueryData? = null,
    val error: String? = null,
    val errorType: String? = null,
)

/** The `data` of a [PromQueryResponse]. */
@Serializable
data class PromQueryData(
    val resultType: String,
    val result: List<PromQueryResult>,
)

/** One series of an instant query: its labels and its `[timestamp, value]` pair. */
@Serializable
data class PromQueryResult(
    val metric: Map<String, String> = emptyMap(),
    val value: List<JsonElement> = emptyList(),
) {
    /** The sample's value, or null when it is missing, NaN or infinite. The API sends it as a string. */
    fun numericValue(): Double? =
        value
            .getOrNull(1)
            ?.let { element ->
                val str = element.toString().trim('"')
                if (str == "NaN" || str == "+Inf" || str == "-Inf") null else str.toDoubleOrNull()
            }
}

/**
 * Runs PromQL instant queries against the cluster's Mimir, in the cluster's tenant.
 *
 * Under decision D1 each Mimir holds only its own cluster's series, but callers still scope their
 * queries to the cluster, so a query means the same thing wherever it runs.
 */
interface MimirQueryService {
    /** Runs [promql] as an instant query and returns each series, or the failure. */
    fun query(promql: String): Result<List<PromQueryResult>>
}

/** [MimirQueryService] over [ObservabilityHttp] (control node, tunnel, tenant header). */
class DefaultMimirQueryService(
    private val http: ObservabilityHttp,
) : MimirQueryService {
    private val json = Json { ignoreUnknownKeys = true }

    override fun query(promql: String): Result<List<PromQueryResult>> =
        runCatching {
            log.debug { "PromQL query: $promql" }
            val encoded = URLEncoder.encode(promql, StandardCharsets.UTF_8)
            val response = http.get(Constants.K8s.MIMIR_HTTP_PORT, "$QUERY_PATH?query=$encoded")
            check(response.code == Constants.HttpStatus.OK) {
                "Mimir query failed with status ${response.code}: ${response.body}"
            }
            val parsed = json.decodeFromString<PromQueryResponse>(response.body)
            check(parsed.status == "success") { "Mimir query error: ${parsed.errorType}: ${parsed.error}" }
            parsed.data?.result.orEmpty()
        }

    private companion object {
        /** Mimir serves the Prometheus HTTP API under `/prometheus`. */
        const val QUERY_PATH = "/prometheus/api/v1/query"
    }
}
