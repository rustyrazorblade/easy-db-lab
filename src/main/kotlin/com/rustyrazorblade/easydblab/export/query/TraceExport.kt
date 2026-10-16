package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
 * may hold more, so it is split in half until none is full, and one still full at a second fails. A
 * search asks for one span per trace (`spss`), since only the ids are used. Each trace found is
 * fetched once by id over the cluster's range, and only its resource spans of the cluster are
 * replayed, as OTLP/JSON with hex ids, to the writer in the cluster's tenant. The writer must keep the
 * spans' own times in its blocks; see [ExportWriterStack].
 *
 * Searches and fetches run on a fixed pool of [parallelism] threads: the windows of one wave (the
 * first windows, then the halves of every full one) at once, then every fetch and replay. The first
 * failure fails the export with its own message.
 *
 * @property reader the reader's backends for a tenant.
 * @property writer the writer's backends for a tenant.
 * @property limit the traces one search asks for.
 * @property parallelism how many searches, or fetches and replays, run at once.
 */
class TraceExport(
    private val reader: (String) -> ObservabilityHttp,
    private val writer: (String) -> ObservabilityHttp,
    private val limit: Int = Constants.Export.Query.TRACE_SEARCH_LIMIT,
    private val parallelism: Int = Constants.Export.Query.TRACE_EXPORT_PARALLELISM,
) {
    private companion object {
        val MAX_WINDOW: Duration = Duration.ofHours(Constants.Export.Query.TRACE_SEARCH_MAX_HOURS)

        /**
         * Longer than the reader's Tempo may take for a cold read, so a slow read ends with Tempo's
         * own answer, which names the failure, rather than with the client giving up.
         */
        val TIMEOUT: Duration =
            Duration.ofSeconds(
                Constants.LocalStack.COLD_READ_TIMEOUT_SECONDS.toLong() + Constants.Export.Query.TEMPO_ANSWER_MARGIN_SECONDS,
            )
        val JSON = Json { ignoreUnknownKeys = true }
        val ID_FIELDS = setOf("traceId", "spanId", "parentSpanId")

        /** The spans a search answer carries per trace (`spss`); Tempo's default is 3. */
        const val SPANS_PER_TRACE = 1
    }

    /** Copies [cluster]'s traces of [tenant] in [range]; returns the traces and the bytes replayed. */
    fun export(
        tenant: String,
        cluster: String,
        range: TimeRange,
    ): SignalCount {
        val pool = Executors.newFixedThreadPool(parallelism)
        try {
            val ids = searchAll(pool, Search(tenant, cluster), windows(range))
            return inParallel(pool, ids) { replay(tenant, cluster, it, range) }.fold(SignalCount.ZERO, SignalCount::plus)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * One cluster's trace search.
     *
     * @property tenant the cluster's tenant.
     * @property cluster the cluster.
     */
    private class Search(
        val tenant: String,
        val cluster: String,
    ) {
        val query: String = "{ resource.cluster = \"$cluster\" }"
    }

    /** A search window, `[from, to]` in Unix seconds. */
    private data class Window(
        val from: Long,
        val to: Long,
    )

    /** What one window's search found: every trace in it, or a full answer, whose window is split in [halves]. */
    private sealed interface Searched {
        data class Complete(
            val ids: List<String>,
        ) : Searched

        data class Full(
            val halves: List<Window>,
        ) : Searched
    }

    /** [range] in consecutive windows of at most [MAX_WINDOW]. */
    private fun windows(range: TimeRange): List<Window> {
        val end = range.end.epochSecond
        return generateSequence(range.start.epochSecond) { from -> (from + MAX_WINDOW.seconds).takeIf { it < end } }
            .map { from -> Window(from, minOf(end, from + MAX_WINDOW.seconds)) }
            .toList()
    }

    /**
     * Every trace of [search] in [initial], in waves: each wave's windows are searched at once on
     * [pool], and the halves of every full window are the next wave. No task waits on another, so a
     * fixed pool cannot deadlock.
     */
    private fun searchAll(
        pool: ExecutorService,
        search: Search,
        initial: List<Window>,
    ): List<String> {
        val ids = LinkedHashSet<String>()
        var wave = initial
        while (wave.isNotEmpty()) {
            wave =
                inParallel(pool, wave) { search(search, it) }.flatMap { searched ->
                    when (searched) {
                        is Searched.Complete -> emptyList<Window>().also { ids += searched.ids }
                        is Searched.Full -> searched.halves
                    }
                }
        }
        return ids.toList()
    }

    /**
     * Searches [window]: every trace when the answer is not full; else its two halves, since a full
     * answer may leave traces out.
     */
    private fun search(
        search: Search,
        window: Window,
    ): Searched {
        val tenant = search.tenant
        val cluster = search.cluster
        val (from, to) = window
        val params =
            query(
                "q" to search.query,
                "start" to from.toString(),
                "end" to to.toString(),
                "limit" to limit.toString(),
                // Only the trace ids are used: one span per trace keeps the answer small.
                "spss" to SPANS_PER_TRACE.toString(),
            )
        val response = reader(tenant).get(Constants.K8s.TEMPO_PORT, "/api/search?$params", TIMEOUT)
        check(response.code == Constants.HttpStatus.OK) {
            "Tempo answered the trace search of cluster $cluster in tenant $tenant with status ${response.code}: ${response.body}"
        }
        val found =
            (JSON.parseToJsonElement(response.body).jsonObject["traces"] as? JsonArray)
                .orEmpty()
                .map {
                    it.jsonObject
                        .getValue("traceID")
                        .jsonPrimitive.content
                }
        if (found.size < limit) return Searched.Complete(found)
        check(to - from > 1) {
            "Cluster $cluster of tenant $tenant has $limit or more traces starting within one second at $from; the export cannot list them all"
        }
        val middle = from + (to - from) / 2
        return Searched.Full(listOf(Window(from, middle), Window(middle, to)))
    }

    /**
     * [task] of every item on [pool], the results in [items]' order. The first failure is thrown as
     * itself, and the tasks not yet done are cancelled.
     */
    private fun <T, R> inParallel(
        pool: ExecutorService,
        items: List<T>,
        task: (T) -> R,
    ): List<R> {
        val futures = items.map { item -> pool.submit(Callable { task(item) }) }
        return futures.map { future ->
            try {
                future.get()
            } catch (e: ExecutionException) {
                futures.forEach { it.cancel(true) }
                throw e.cause ?: e
            }
        }
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
        check(response.code == Constants.HttpStatus.OK) {
            "Tempo answered trace $id of tenant $tenant with status ${response.code}: ${response.body}"
        }
        val resources =
            (
                JSON
                    .parseToJsonElement(response.body)
                    .jsonObject["trace"]
                    ?.jsonObject
                    ?.get("resourceSpans") as? JsonArray
            ).orEmpty()
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

    /** Whether [value] holds an id: a string that is not empty. */
    private fun isId(value: JsonElement): Boolean = value is JsonPrimitive && value.isString && value.content.isNotEmpty()

    /** [element] with every trace, span and parent span id, links' included, in hex. */
    private fun withHexIds(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.mapValues { (key, value) ->
                        if (key in ID_FIELDS && isId(value)) {
                            JsonPrimitive(OtlpIds.hex(value.jsonPrimitive.content))
                        } else {
                            withHexIds(value)
                        }
                    },
                )
            is JsonArray -> JsonArray(element.map(::withHexIds))
            else -> element
        }
}
