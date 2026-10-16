package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

/**
 * Span and trace ids as OTLP/JSON takes them: hex. Tempo's JSON API writes them in base64, the
 * protobuf JSON form of bytes.
 */
object OtlpIds {
    private val HEX = Regex("^[0-9a-fA-F]+$")
    private val HEX_LENGTHS = setOf(TRACE_ID_HEX, SPAN_ID_HEX)
    private const val TRACE_ID_HEX = 32
    private const val SPAN_ID_HEX = 16

    /** [id] in hex: kept when it is a hex trace or span id already, else decoded from base64. */
    fun hex(id: String): String =
        if (id.length in HEX_LENGTHS && HEX.matches(id)) id.lowercase() else HexFormat.of().formatHex(Base64.getDecoder().decode(id))
}

/**
 * Copies one cluster's traces from the reader's Tempo to the writer's (design section 7, spike S3).
 *
 * The reader is searched for `{ resource.cluster = "<c>" }` over the cluster's range in windows of at
 * most 168 hours, Tempo's `query_frontend.search.max_duration`; a window that returns [limit] traces
 * may hold more, so it is split in half until none is full, and one still full at a second fails.
 * Each trace found is fetched once by id over the cluster's range, and only its resource spans of the
 * cluster are replayed, as OTLP/JSON with hex ids, to the writer in the cluster's tenant. The writer
 * must keep the spans' own times in its blocks; see [ExportWriterStack].
 *
 * @property reader the reader's backends for a tenant.
 * @property writer the writer's backends for a tenant.
 * @property limit the traces one search asks for.
 */
class TraceExport(
    private val reader: (String) -> ObservabilityHttp,
    private val writer: (String) -> ObservabilityHttp,
    private val limit: Int = Constants.Export.Query.TRACE_SEARCH_LIMIT,
) {
    private companion object {
        val MAX_WINDOW: Duration = Duration.ofHours(Constants.Export.Query.TRACE_SEARCH_MAX_HOURS)
        val TIMEOUT: Duration = Duration.ofMinutes(5)
        val JSON = Json { ignoreUnknownKeys = true }
        val ID_FIELDS = setOf("traceId", "spanId", "parentSpanId")
    }

    /** Copies [cluster]'s traces of [tenant] in [range]; returns the traces and the bytes replayed. */
    fun export(
        tenant: String,
        cluster: String,
        range: TimeRange,
    ): SignalCount {
        val query = "{ resource.cluster = \"$cluster\" }"
        val ids = LinkedHashSet<String>()
        var from = range.start.epochSecond
        val end = range.end.epochSecond
        do {
            val to = minOf(end, from + MAX_WINDOW.seconds)
            search(tenant, cluster, query, from, to, ids)
            from = to
        } while (from < end)
        return ids.fold(SignalCount.ZERO) { count, id -> count + replay(tenant, cluster, id, range) }
    }

    /** Adds every trace of [query] between [from] and [to] to [ids], splitting a full window. */
    private fun search(
        tenant: String,
        cluster: String,
        query: String,
        from: Long,
        to: Long,
        ids: MutableSet<String>,
    ) {
        val params = query("q" to query, "start" to from.toString(), "end" to to.toString(), "limit" to limit.toString())
        val response = reader(tenant).get(Constants.K8s.TEMPO_PORT, "/api/search?$params", TIMEOUT)
        check(response.code == Constants.HttpStatus.OK) {
            "Tempo answered the trace search of cluster $cluster in tenant $tenant with status ${response.code}: ${response.body}"
        }
        val found =
            (JSON.parseToJsonElement(response.body).jsonObject["traces"] as? JsonArray)
                .orEmpty()
                .map { it.jsonObject.getValue("traceID").jsonPrimitive.content }
        if (found.size < limit) {
            ids += found
            return
        }
        check(to - from > 1) {
            "Cluster $cluster of tenant $tenant has $limit or more traces starting within one second at $from; the export cannot list them all"
        }
        val middle = from + (to - from) / 2
        search(tenant, cluster, query, from, middle, ids)
        search(tenant, cluster, query, middle, to, ids)
    }

    /** Fetches trace [id] and replays its resource spans of [cluster] to the writer. */
    private fun replay(
        tenant: String,
        cluster: String,
        id: String,
        range: TimeRange,
    ): SignalCount {
        val params = query("start" to range.start.epochSecond.toString(), "end" to range.end.epochSecond.toString())
        val response = reader(tenant).get(Constants.K8s.TEMPO_PORT, "/api/v2/traces/$id?$params", TIMEOUT)
        check(response.code == Constants.HttpStatus.OK) { "Tempo answered trace $id of tenant $tenant with status ${response.code}: ${response.body}" }
        val resources =
            (JSON.parseToJsonElement(response.body).jsonObject["trace"]?.jsonObject?.get("resourceSpans") as? JsonArray)
                .orEmpty()
                .filter { clusterOf(it) == cluster }
        if (resources.isEmpty()) return SignalCount.ZERO
        val body = buildJsonObject { put("resourceSpans", JsonArray(resources.map(::withHexIds))) }.toString()
        val pushed = writer(tenant).post(Constants.K8s.TEMPO_OTLP_HTTP_PORT, "/v1/traces", body, timeout = TIMEOUT)
        check(pushed.code == Constants.HttpStatus.OK) {
            "The writer's Tempo refused trace $id of tenant $tenant with status ${pushed.code}: ${pushed.body}"
        }
        return SignalCount(1, body.toByteArray().size.toLong())
    }

    /** The `cluster` resource attribute of one resource's spans. */
    private fun clusterOf(resource: JsonElement): String? =
        (resource.jsonObject["resource"]?.jsonObject?.get("attributes") as? JsonArray)
            .orEmpty()
            .firstOrNull { it.jsonObject["key"]?.jsonPrimitive?.content == "cluster" }
            ?.jsonObject
            ?.get("value")
            ?.jsonObject
            ?.get("stringValue")
            ?.jsonPrimitive
            ?.content

    /** [element] with every trace, span and parent span id, links' included, in hex. */
    private fun withHexIds(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.mapValues { (key, value) ->
                        if (key in ID_FIELDS && value is JsonPrimitive && value.isString && value.content.isNotEmpty()) {
                            JsonPrimitive(OtlpIds.hex(value.content))
                        } else {
                            withHexIds(value)
                        }
                    },
                )
            is JsonArray -> JsonArray(element.map(::withHexIds))
            else -> element
        }
}
