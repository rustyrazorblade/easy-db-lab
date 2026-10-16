package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A cluster's logs, read from the reader's Loki and written to the writer's: forward pages with a
 * tie-break on the last timestamp, so no line is lost or written twice when lines share a
 * nanosecond; each line's structured metadata kept apart from the stream's index labels; every line
 * pushed in the cluster's tenant.
 */
class LogExportTest {
    /** One stored line. */
    private data class Line(
        val stream: Map<String, String>,
        val nanos: Long,
        val text: String,
        val metadata: Map<String, String> = emptyMap(),
    )

    private val start = Instant.parse("2026-10-10T00:00:00Z")
    private val range = TimeRange(start, start.plusSeconds(3600))
    private val base = start.epochSecond * 1_000_000_000L
    private val stream = mapOf("cluster" to "x-1", "service_name" to "cassandra")
    private var stored: List<Line> = emptyList()
    private val pushed = mutableListOf<Line>()
    private var pushStatus = 204

    /** Answers a forward query_range as Loki does: the first `limit` lines at or after start, before end. */
    private val backends =
        FakeBackends { call ->
            when (call.route) {
                "/loki/api/v1/query_range" -> page(call)
                "/loki/api/v1/push" -> {
                    pushed += parsePush(call.body)
                    ObservabilityResponse(pushStatus, "")
                }
                else -> ObservabilityResponse(404, "")
            }
        }

    private fun page(call: BackendCall): ObservabilityResponse {
        val from = call.param("start")!!.toLong()
        val to = call.param("end")!!.toLong()
        val limit = call.param("limit")!!.toInt()
        val lines = stored.filter { it.nanos in from until to }.sortedBy { it.nanos }.take(limit)
        val result =
            buildJsonArray {
                lines.groupBy { it.stream }.forEach { (labels, entries) ->
                    add(
                        buildJsonObject {
                            put("stream", buildJsonObject { labels.forEach { (k, v) -> put(k, v) } })
                            put(
                                "values",
                                buildJsonArray {
                                    entries.forEach { line ->
                                        add(
                                            buildJsonArray {
                                                add(JsonPrimitive(line.nanos.toString()))
                                                add(JsonPrimitive(line.text))
                                                add(
                                                    buildJsonObject {
                                                        put("structuredMetadata", buildJsonObject { line.metadata.forEach { (k, v) -> put(k, v) } })
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            }
        return ObservabilityResponse(200, """{"status":"success","data":{"resultType":"streams","result":$result}}""")
    }

    private fun parsePush(body: String): List<Line> =
        Json.parseToJsonElement(body).jsonObject.getValue("streams").jsonArray.flatMap { s ->
            val labels = s.jsonObject.getValue("stream").jsonObject.mapValues { it.value.jsonPrimitive.content }
            s.jsonObject.getValue("values").jsonArray.map { v ->
                val entry = v.jsonArray
                Line(
                    labels,
                    entry[0].jsonPrimitive.content.toLong(),
                    entry[1].jsonPrimitive.content,
                    (entry.getOrNull(2) as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                )
            }
        }

    private fun export(
        pageSize: Int = 2,
        burstLimit: Int = 10,
    ) = LogExport(backends::forTenant, backends::forTenant, pageSize, burstLimit).export("acme", "x-1", range)

    @Test
    fun `every line is copied once, in order, across pages, though several share a nanosecond at a page edge`() {
        stored =
            listOf(
                Line(stream, base + 1, "a"),
                Line(stream, base + 2, "b"),
                Line(stream, base + 2, "c"),
                Line(stream, base + 2, "d"),
                Line(stream, base + 3, "e"),
            )

        val counted = export(pageSize = 2)

        assertThat(pushed.map { it.text }).containsExactly("a", "b", "c", "d", "e")
        assertThat(counted).isEqualTo(SignalCount(items = 5, bytes = 5))
    }

    @Test
    fun `structured metadata is pushed as metadata, apart from the stream's index labels`() {
        stored = listOf(Line(stream, base + 1, "with metadata", mapOf("trace_id" to "abc", "detected_level" to "info")))

        export()

        assertThat(pushed.single().stream).isEqualTo(stream)
        assertThat(pushed.single().metadata).containsEntry("trace_id", "abc").containsEntry("detected_level", "info")
    }

    @Test
    fun `the reader is asked for one cluster's lines over its range, forward, with categorized labels`() {
        stored = listOf(Line(stream, base + 1, "a"))

        export()

        val query = backends.calls.first { it.route == "/loki/api/v1/query_range" }
        assertThat(query.tenant).isEqualTo("acme")
        assertThat(query.param("query")).isEqualTo("{cluster=\"x-1\"}")
        assertThat(query.param("direction")).isEqualTo("forward")
        assertThat(query.param("start")).isEqualTo(base.toString())
        assertThat(query.headers).containsEntry("X-Loki-Response-Encoding-Flags", "categorize-labels")
        assertThat(backends.calls.filter { it.route == "/loki/api/v1/push" }.map { it.tenant }).containsOnly("acme")
    }

    @Test
    fun `a cluster with no lines counts zero and pushes nothing`() {
        assertThat(export()).isEqualTo(SignalCount(0, 0))
        assertThat(backends.calls.filter { it.route == "/loki/api/v1/push" }).isEmpty()
    }

    @Test
    fun `lines in one nanosecond past a page are read in one query of that nanosecond`() {
        stored = (1..3).map { Line(stream, base + 5, "same-$it") } + Line(stream, base + 6, "after")

        export(pageSize = 2)

        assertThat(pushed.map { it.text }).containsExactly("same-1", "same-2", "same-3", "after")
    }

    @Test
    fun `more lines in one nanosecond than Loki returns at once fails rather than loop or drop lines`() {
        stored = (1..3).map { Line(stream, base + 5, "same-$it") }

        assertThatThrownBy { export(pageSize = 2, burstLimit = 2) }.hasMessageContaining("nanosecond")
    }

    @Test
    fun `a push the writer refuses fails with its status`() {
        stored = listOf(Line(stream, base + 1, "a"))
        pushStatus = 400

        assertThatThrownBy { export() }.hasMessageContaining("400")
    }
}
