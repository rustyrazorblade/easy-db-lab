package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * What a query export copied of one signal.
 *
 * @property items lines, traces or metric blocks.
 * @property bytes their size.
 */
data class SignalCount(
    val items: Long,
    val bytes: Long,
) {
    /** This and [other] together. */
    operator fun plus(other: SignalCount): SignalCount = SignalCount(items + other.items, bytes + other.bytes)

    companion object {
        /** Nothing copied. */
        val ZERO = SignalCount(0, 0)
    }
}

/**
 * Copies one cluster's logs from the reader's Loki to the writer's (design section 7).
 *
 * The reader is asked `{cluster="<c>"}` over the cluster's range, forward, in pages of [pageSize]
 * lines, with Loki's categorized labels, so each line's structured metadata comes apart from the
 * stream's index labels and is pushed back as metadata. A page ends on a nanosecond that may hold
 * more lines than it showed, so the next page starts on that nanosecond and skips the lines already
 * pushed (the tie-break). When a whole page shares the cursor's nanosecond, one query of that
 * nanosecond alone asks for up to [burstLimit] lines; more than that fails rather than drop lines.
 * Lines are pushed to the writer in the cluster's tenant, in time order per stream, as Loki needs.
 *
 * @property reader the reader's backends for a tenant.
 * @property writer the writer's backends for a tenant.
 * @property pageSize the lines one page asks for.
 * @property burstLimit the most lines one nanosecond may hold.
 */
class LogExport(
    private val reader: (String) -> ObservabilityHttp,
    private val writer: (String) -> ObservabilityHttp,
    private val pageSize: Int = Constants.Export.Query.LOG_PAGE_LINES,
    private val burstLimit: Int = Constants.Export.Query.LOG_MAX_LINES,
) {
    private companion object {
        const val QUERY_PATH = "/loki/api/v1/query_range"
        const val PUSH_PATH = "/loki/api/v1/push"
        const val NANOS_PER_SECOND = 1_000_000_000L
        val TIMEOUT: Duration = Duration.ofMinutes(5)
        val JSON = Json { ignoreUnknownKeys = true }
    }

    /** One line as Loki returned it. */
    private data class Entry(
        val stream: Map<String, String>,
        val nanos: Long,
        val line: String,
        val metadata: Map<String, String>,
    )

    /** Copies [cluster]'s lines of [tenant] in [range]; returns the lines and their bytes. */
    fun export(
        tenant: String,
        cluster: String,
        range: TimeRange,
    ): SignalCount {
        val query = "{cluster=\"$cluster\"}"
        val end = nanos(range.end) + 1
        var cursor = nanos(range.start)
        var seenAtCursor = emptySet<Entry>()
        var count = SignalCount.ZERO
        while (true) {
            val page = read(tenant, query, cursor, end, pageSize)
            val atCursorOnly = page.size >= pageSize && page.all { it.nanos == cursor }
            val entries = if (atCursorOnly) burst(tenant, query, cursor) else page
            val fresh = entries.filterNot { it.nanos == cursor && it in seenAtCursor }
            count += push(tenant, fresh)
            when {
                atCursorOnly -> {
                    cursor += 1
                    seenAtCursor = emptySet()
                }
                page.size < pageSize -> return count
                else -> {
                    cursor = page.maxOf { it.nanos }
                    seenAtCursor = page.filter { it.nanos == cursor }.toSet()
                }
            }
            if (cursor >= end) return count
        }
    }

    /** Every line of the one nanosecond [at]; fails when it holds [burstLimit] lines or more. */
    private fun burst(
        tenant: String,
        query: String,
        at: Long,
    ): List<Entry> {
        val lines = read(tenant, query, at, at + 1, burstLimit)
        check(lines.size < burstLimit) {
            "Cluster logs hold $burstLimit or more lines in the one nanosecond $at of tenant $tenant, more than Loki returns at once"
        }
        return lines
    }

    private fun read(
        tenant: String,
        query: String,
        start: Long,
        end: Long,
        limit: Int,
    ): List<Entry> {
        val params =
            query(
                "query" to query,
                "start" to start.toString(),
                "end" to end.toString(),
                "limit" to limit.toString(),
                "direction" to "forward",
            )
        val response =
            reader(tenant).get(
                Constants.K8s.LOKI_HTTP_PORT,
                "$QUERY_PATH?$params",
                TIMEOUT,
                mapOf(Constants.Export.Query.LOKI_ENCODING_FLAGS_HEADER to Constants.Export.Query.LOKI_CATEGORIZE_LABELS),
            )
        check(
            response.code == Constants.HttpStatus.OK,
        ) { "Loki answered $query in tenant $tenant with status ${response.code}: ${response.body}" }
        val result =
            JSON
                .parseToJsonElement(response.body)
                .jsonObject
                .getValue("data")
                .jsonObject["result"] as? JsonArray
        return result
            .orEmpty()
            .flatMap { stream ->
                val labels =
                    stream.jsonObject
                        .getValue("stream")
                        .jsonObject
                        .mapValues { it.value.jsonPrimitive.content }
                stream.jsonObject.getValue("values").jsonArray.map { value ->
                    val fields = value.jsonArray
                    val categories = fields.getOrNull(2) as? JsonObject
                    val metadata = categories?.get("structuredMetadata") as? JsonObject
                    Entry(
                        stream = labels,
                        nanos = fields[0].jsonPrimitive.content.toLong(),
                        line = fields[1].jsonPrimitive.content,
                        metadata = metadata?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                    )
                }
            }.sortedBy { it.nanos }
    }

    /** Pushes [entries] to the writer in [tenant], one stream per label set. */
    private fun push(
        tenant: String,
        entries: List<Entry>,
    ): SignalCount {
        if (entries.isEmpty()) return SignalCount.ZERO
        val body =
            buildJsonObject {
                put(
                    "streams",
                    buildJsonArray {
                        entries.groupBy { it.stream }.forEach { (labels, lines) ->
                            add(
                                buildJsonObject {
                                    put("stream", buildJsonObject { labels.forEach { (k, v) -> put(k, v) } })
                                    put("values", buildJsonArray { lines.forEach { add(value(it)) } })
                                },
                            )
                        }
                    },
                )
            }
        val response = writer(tenant).post(Constants.K8s.LOKI_HTTP_PORT, PUSH_PATH, body.toString(), timeout = TIMEOUT)
        check(response.code == Constants.HttpStatus.NO_CONTENT) {
            "The writer's Loki refused ${entries.size} lines of tenant $tenant with status ${response.code}: ${response.body}"
        }
        return SignalCount(
            entries.size.toLong(),
            entries.sumOf {
                it.line
                    .toByteArray()
                    .size
                    .toLong()
            },
        )
    }

    private fun value(entry: Entry): JsonArray =
        buildJsonArray {
            add(JsonPrimitive(entry.nanos.toString()))
            add(JsonPrimitive(entry.line))
            if (entry.metadata.isNotEmpty()) add(buildJsonObject { entry.metadata.forEach { (k, v) -> put(k, v) } })
        }

    private fun nanos(at: Instant): Long = at.epochSecond * NANOS_PER_SECOND + at.nano
}
