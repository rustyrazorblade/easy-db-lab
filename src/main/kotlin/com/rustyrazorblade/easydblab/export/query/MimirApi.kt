package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant

/**
 * One series of a range query: its labels, and the times (Unix seconds) it has a value at.
 *
 * @property labels the series' labels.
 * @property times the sample times, ascending.
 */
data class RangeSeries(
    val labels: Map<String, String>,
    val times: List<Long>,
)

/**
 * The Prometheus API of a query export's reader Mimir, in one or several tenants. Every request names
 * the tenants in `X-Scope-OrgID`, joined by `|` for a federated one, which Mimir answers with each
 * series' tenant in `__tenant_id__`; with one tenant that label is absent (spike S7). It only reads.
 *
 * @property http the reader's backends for a tenant header.
 * @property clock now, the end of an unbounded series or label lookup.
 */
class MimirApi(
    private val http: (String) -> ObservabilityHttp,
    private val clock: Clock = Clock.systemUTC(),
) {
    private companion object {
        const val PREFIX = "/prometheus/api/v1"
        val JSON = Json { ignoreUnknownKeys = true }
    }

    /** Every series matching [match] in [tenants], from the oldest block to now. */
    fun series(
        tenants: List<String>,
        match: String,
    ): List<Map<String, String>> =
        data(tenants, "$PREFIX/series?${query("match[]" to match, "start" to "0", "end" to now())}")
            .jsonArray
            .map { series -> series.jsonObject.mapValues { it.value.jsonPrimitive.content } }

    /** The values of [label] over every series matching one of [matches] in [tenants], from the oldest block on. */
    fun labelValues(
        tenants: List<String>,
        label: String,
        matches: List<String>,
    ): List<String> {
        val params = matches.map { "match[]" to it } + listOf("start" to "0", "end" to now())
        return data(tenants, "$PREFIX/label/$label/values?${query(params)}").jsonArray.map { it.jsonPrimitive.content }
    }

    /** The series of the range query [promql] in [tenants] from [start] to [end], every [stepSeconds]. */
    fun queryRange(
        tenants: List<String>,
        promql: String,
        start: Instant,
        end: Instant,
        stepSeconds: Long,
    ): List<RangeSeries> {
        val params =
            query(
                "query" to promql,
                "start" to start.epochSecond.toString(),
                "end" to end.epochSecond.toString(),
                "step" to stepSeconds.toString(),
            )
        return data(tenants, "$PREFIX/query_range?$params").jsonObject.getValue("result").jsonArray.map { series ->
            val fields = series.jsonObject
            RangeSeries(
                labels = fields.getValue("metric").jsonObject.mapValues { it.value.jsonPrimitive.content },
                times =
                    (fields["values"] as? JsonArray).orEmpty().map {
                        it.jsonArray[0]
                            .jsonPrimitive.content
                            .toDouble()
                            .toLong()
                    },
            )
        }
    }

    /** The `data` of a successful answer to [pathAndQuery] in [tenants]. */
    private fun data(
        tenants: List<String>,
        pathAndQuery: String,
    ) = run {
        require(tenants.isNotEmpty()) { "A Mimir request needs a tenant" }
        val header = tenants.joinToString(Constants.Export.Query.TENANT_SEPARATOR)
        val response = http(header).get(Constants.K8s.MIMIR_HTTP_PORT, pathAndQuery)
        check(response.code == Constants.HttpStatus.OK) {
            "Mimir answered ${pathAndQuery.substringBefore('?')} in tenant $header with status ${response.code}: ${response.body}"
        }
        val body = JSON.parseToJsonElement(response.body) as JsonObject
        check(
            body["status"]?.jsonPrimitive?.content == "success",
        ) { "Mimir refused ${pathAndQuery.substringBefore('?')}: ${response.body}" }
        body.getValue("data")
    }

    private fun now(): String = clock.instant().epochSecond.toString()
}

/** [params] as a URL query string, each name and value encoded. */
internal fun query(vararg params: Pair<String, String>): String = query(params.asList())

/** [params] as a URL query string, each name and value encoded. */
internal fun query(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (name, value) ->
        "${URLEncoder.encode(name, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }
