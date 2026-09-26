package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AnnotationMirrorTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val stateManager =
        mock<ClusterStateManager>().also {
            whenever(it.load()).thenReturn(ClusterState(name = "lab", clusterId = "c1", versions = mutableMapOf()))
        }
    private val now = Instant.parse("2026-09-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val events = mutableListOf<Event>()
    private val eventBus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }

    /** Grafana at the HTTP boundary: answers the annotation listing with a fixed body. */
    private class GrafanaListing(
        private val body: String,
    ) : GrafanaAnnotationSource {
        override fun fetchAnnotations(controlHost: ClusterHost): String = body
    }

    private fun mirror(
        http: RecordingObservabilityHttp,
        grafana: String = "[]",
    ) = DefaultAnnotationMirror(
        LokiPushClient(http),
        GrafanaListing(grafana),
        stateManager,
        eventBus,
        clock,
        QueryableWait(looks = LOOKS, interval = Duration.ZERO),
    )

    /** Loki's `query_range` answer holding one entry: the annotation was found. */
    private val found = ObservabilityResponse(200, """{"status":"success","data":{"result":[{"stream":{},"values":[["1","x"]]}]}}""")

    /** Loki's `query_range` answer holding nothing: the annotation is not queryable yet. */
    private val notFound = ObservabilityResponse(200, """{"status":"success","data":{"result":[]}}""")

    private val accepted = ObservabilityResponse(204, "")

    private fun decoded(path: String): String = URLDecoder.decode(path, Charsets.UTF_8)

    private fun ago(duration: Duration): Long = now.minus(duration).toEpochMilli()

    private fun streams(call: RecordingObservabilityHttp.Call): List<JsonObject> =
        Json
            .parseToJsonElement(call.body)
            .jsonObject
            .getValue("streams")
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.labels() = getValue("stream").jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun JsonObject.entry(): JsonArray = getValue("values").jsonArray.single().jsonArray

    @Test
    fun `an annotation becomes its own stream in Loki, labelled with the cluster and its Grafana id`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(204, ""))

        mirror(http)
            .push(MirroredAnnotation(id = 42, text = "concurrent_reads 64->128", tags = listOf("ab", "easydblab"), time = ago(MINUTE)))
            .getOrThrow()

        val call = http.calls.single()
        assertThat(call.method).isEqualTo("POST")
        assertThat(call.port).isEqualTo(3100)
        assertThat(call.path).isEqualTo("/loki/api/v1/push")
        val stream = streams(call).single()
        assertThat(stream.labels()).containsExactlyInAnyOrderEntriesOf(
            mapOf("cluster" to "lab-c1", "source" to "annotation", "annotation_id" to "42"),
        )
        val entry = stream.entry()
        assertThat(entry[0].jsonPrimitive.content).isEqualTo("${ago(MINUTE)}000000")
        assertThat(entry[1].jsonPrimitive.content).isEqualTo("concurrent_reads 64->128")
        assertThat(
            entry[2].jsonObject.mapValues { it.value.jsonPrimitive.content },
        ).containsExactlyEntriesOf(mapOf("tags" to "ab,easydblab"))
    }

    @Test
    fun `a scoped region annotation carries its dashboard, panel and end time as metadata`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(204, ""))

        mirror(http)
            .push(
                MirroredAnnotation(
                    id = 7,
                    text = "load",
                    tags = emptyList(),
                    time = ago(MINUTE),
                    timeEnd = ago(MINUTE) + 4_000,
                    dashboardUid = "abc",
                    panelId = 3,
                ),
            ).getOrThrow()

        val metadata =
            streams(http.calls.single())
                .single()
                .entry()[2]
                .jsonObject
                .mapValues { it.value.jsonPrimitive.content }
        assertThat(metadata).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "dashboard_uid" to "abc",
                "panel_id" to "3",
                "time_end" to (ago(MINUTE) + 4_000).toString(),
            ),
        )
    }

    @Test
    fun `an annotation older than the ingester's query window is flushed to the store and read back before push returns`() {
        val time = ago(Duration.ofHours(3))
        val http = RecordingObservabilityHttp(accepted, accepted, notFound, found)

        mirror(http).push(MirroredAnnotation(id = 42, text = "backdated", tags = emptyList(), time = time)).getOrThrow()

        assertThat(http.calls.map { it.method to it.path.substringBefore('?') }).containsExactly(
            "POST" to "/loki/api/v1/push",
            "POST" to "/flush",
            "GET" to "/loki/api/v1/query_range",
            "GET" to "/loki/api/v1/query_range",
        )
        val query = decoded(http.calls.last().path)
        assertThat(query)
            .contains("""annotation_id="42"""")
            .contains("""cluster="lab-c1"""")
            .contains("""source="annotation"""")
            .contains("start=${time}000000")
    }

    @Test
    fun `an annotation inside the ingester's query window is answered by the ingester, so nothing is flushed`() {
        val http = RecordingObservabilityHttp(accepted)

        mirror(http).push(MirroredAnnotation(id = 1, text = "recent", tags = emptyList(), time = ago(Duration.ofMinutes(119)))).getOrThrow()

        assertThat(http.calls.map { it.path }).containsExactly("/loki/api/v1/push")
    }

    @Test
    fun `a refused flush fails the mirror with Loki's answer`() {
        val http = RecordingObservabilityHttp(accepted, ObservabilityResponse(500, "ingester not ready"))

        val result = mirror(http).push(MirroredAnnotation(id = 1, text = "x", tags = emptyList(), time = ago(Duration.ofHours(5))))

        assertThat(result.exceptionOrNull()).hasMessageContaining("500").hasMessageContaining("ingester not ready")
    }

    @Test
    fun `a backdated annotation Loki still does not answer after the wait fails the mirror, naming it`() {
        val http = RecordingObservabilityHttp(accepted, accepted, *Array(LOOKS) { notFound })

        val result = mirror(http).push(MirroredAnnotation(id = 77, text = "x", tags = emptyList(), time = ago(Duration.ofHours(5))))

        assertThat(result.exceptionOrNull()).hasMessageContaining("77")
        assertThat(http.calls.count { it.method == "GET" }).isEqualTo(LOOKS)
    }

    @Test
    fun `a refused push fails with Loki's answer`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(400, "entry too far behind"))

        val result = mirror(http).push(MirroredAnnotation(id = 1, text = "x", tags = emptyList(), time = 1))

        assertThat(result.exceptionOrNull()).hasMessageContaining("400").hasMessageContaining("entry too far behind")
    }

    @Test
    fun `syncAll mirrors every Grafana annotation in one push, point annotations without an end time`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(204, ""))
        val start = ago(HOUR)
        val end = ago(MINUTE)
        val grafana =
            """
            [{"id":1,"text":"ui marker","tags":[],"time":$start,"timeEnd":$start,"dashboardUID":"","panelId":0},
             {"id":2,"text":"region","tags":["ab"],"time":$start,"timeEnd":$end,"dashboardUID":"d1","panelId":4,"extra":"ignored"}]
            """.trimIndent()

        val count = mirror(http, grafana).syncAll(control).getOrThrow()

        assertThat(count).isEqualTo(2)
        val pushed = streams(http.calls.single()).associate { it.labels().getValue("annotation_id") to it.entry() }
        assertThat(pushed.getValue("1")[2].jsonObject).isEmpty()
        assertThat(pushed.getValue("2")[2].jsonObject.mapValues { it.value.jsonPrimitive.content })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "tags" to "ab",
                    "dashboard_uid" to "d1",
                    "panel_id" to "4",
                    "time_end" to end.toString(),
                ),
            )
        assertThat(events).isEmpty()
    }

    @Test
    fun `syncAll skips an annotation outside Loki's accepted window, names it in a warning, and mirrors the rest`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(204, ""))
        val grafana =
            """
            [{"id":1,"text":"last year","tags":[],"time":${ago(Duration.ofDays(366))}},
             {"id":2,"text":"today","tags":[],"time":${ago(HOUR)}},
             {"id":3,"text":"next week","tags":[],"time":${ago(Duration.ofDays(-7))}}]
            """.trimIndent()

        val count = mirror(http, grafana).syncAll(control).getOrThrow()

        assertThat(count).isEqualTo(1)
        assertThat(streams(http.calls.single()).map { it.labels().getValue("annotation_id") }).containsExactly("2")
        val warning = events.filterIsInstance<Event.Grafana.AnnotationsOutsideLokiWindow>().single()
        assertThat(warning.ids).containsExactly(1L, 3L)
        assertThat(warning.isError()).isFalse()
    }

    @Test
    fun `syncAll keeps an annotation just inside either edge of Loki's window`() {
        val http = RecordingObservabilityHttp(accepted, accepted, found)
        val grafana =
            """
            [{"id":1,"text":"old","tags":[],"time":${ago(Duration.ofHours(8759))}},
             {"id":2,"text":"ahead","tags":[],"time":${ago(Duration.ofHours(-23))}}]
            """.trimIndent()

        assertThat(mirror(http, grafana).syncAll(control).getOrThrow()).isEqualTo(2)
        assertThat(events).isEmpty()
    }

    @Test
    fun `syncAll with every annotation outside Loki's window pushes nothing and still succeeds`() {
        val http = RecordingObservabilityHttp()
        val grafana = """[{"id":9,"text":"ancient","tags":[],"time":1000}]"""

        assertThat(mirror(http, grafana).syncAll(control).getOrThrow()).isZero()
        assertThat(http.calls).isEmpty()
        assertThat(events.filterIsInstance<Event.Grafana.AnnotationsOutsideLokiWindow>().single().ids).containsExactly(9L)
    }

    @Test
    fun `syncAll with no annotations pushes nothing`() {
        val http = RecordingObservabilityHttp()

        assertThat(mirror(http, "[]").syncAll(control).getOrThrow()).isZero()
        assertThat(http.calls).isEmpty()
    }

    private companion object {
        val HOUR: Duration = Duration.ofHours(1)
        val MINUTE: Duration = Duration.ofMinutes(1)
        const val LOOKS = 3
    }
}
