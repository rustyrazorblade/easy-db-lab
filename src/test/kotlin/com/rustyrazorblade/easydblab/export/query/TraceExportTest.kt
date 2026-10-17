package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A cluster's traces, searched in the reader's Tempo and replayed to the writer's (design section 7,
 * spike S3): search windows of at most 168 hours (Tempo refuses longer), a window that comes back
 * full split in half until it does not, each trace fetched once by id over the cluster's range, only
 * the cluster's own resource spans replayed, and Tempo's base64 ids turned into the hex OTLP takes.
 */
class TraceExportTest {
    private val start = Instant.parse("2026-09-01T00:00:00Z")

    /** Trace start times (Unix seconds), by trace id. */
    private var traces: Map<String, Long> = emptyMap()
    private val replayed = Collections.synchronizedList(mutableListOf<String>())
    private var replayStatus = 200

    /** Runs before each answer, on the caller's thread: a test holds calls here to see how many run at once. */
    private var beforeAnswer: (BackendCall) -> Unit = {}

    private val backends =
        FakeBackends { call ->
            beforeAnswer(call)
            when {
                call.route == "/api/search" -> search(call)
                call.route.startsWith("/api/v2/traces/") -> trace(call.route.removePrefix("/api/v2/traces/"))
                call.route == "/v1/traces" -> {
                    replayed += call.body
                    ObservabilityResponse(replayStatus, "{}")
                }
                else -> ObservabilityResponse(404, "")
            }
        }

    private fun search(call: BackendCall): ObservabilityResponse {
        val from = call.param("start")!!.toLong()
        val to = call.param("end")!!.toLong()
        val limit = call.param("limit")!!.toInt()
        val found = traces.filterValues { it in from..to }.keys.take(limit)
        return ObservabilityResponse(200, """{"traces":[${found.joinToString(",") { """{"traceID":"$it"}""" }}]}""")
    }

    private fun b64(hex: String): String = Base64.getEncoder().encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    /** A trace as Tempo's v2 API returns it: ids in base64, and a resource of another cluster beside the selected one. */
    private fun trace(id: String): ObservabilityResponse {
        val traceId = id.padStart(32, '0')

        fun resource(cluster: String) =
            """
            {"resource":{"attributes":[{"key":"cluster","value":{"stringValue":"$cluster"}}]},
             "scopeSpans":[{"spans":[{"traceId":"${b64(traceId)}","spanId":"${b64("0102030405060708")}",
               "parentSpanId":"${b64("1112131415161718")}","name":"op","kind":"SPAN_KIND_SERVER",
               "links":[{"traceId":"${b64(traceId)}","spanId":"${b64("2122232425262728")}"}]}]}]}
            """.trimIndent()
        return ObservabilityResponse(200, """{"trace":{"resourceSpans":[${resource("x-1")},${resource("z-9")}]}}""")
    }

    private val range = TimeRange(start, start.plus(Duration.ofDays(20)))

    private fun export(
        limit: Int = 10,
        parallelism: Int = 1,
    ) = TraceExport(backends::forTenant, backends::forTenant, limit, parallelism).export("acme", "x-1", range)

    /**
     * Holds each call [matching] until [expected] of them are in flight together (or a few seconds
     * pass), and returns the most that ever were.
     */
    private fun holdUntilConcurrent(
        expected: Int,
        matching: (BackendCall) -> Boolean,
    ): AtomicInteger {
        val arrived = CountDownLatch(expected)
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        beforeAnswer = { call ->
            if (matching(call)) {
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                arrived.countDown()
                arrived.await(HOLD_SECONDS, TimeUnit.SECONDS)
                inFlight.decrementAndGet()
            }
        }
        return most
    }

    private companion object {
        const val HOLD_SECONDS = 5L
    }

    @Test
    fun `the fetch phase reports how many of the cluster's traces were fetched, every so many, and once at the end`() {
        traces = (1..5).associate { "e%030x".format(it) to start.epochSecond + it * 60L }
        val reported = Collections.synchronizedList(mutableListOf<Event.Export.TracesFetched>())
        val every = ProgressPolicy(everyItems = 2, interval = Duration.ofHours(1), clock = Clock.systemUTC())

        TraceExport(backends::forTenant, backends::forTenant, progress = every, onProgress = { reported += it })
            .export("acme", "x-1", range)

        assertThat(reported).containsExactly(
            Event.Export.TracesFetched("x-1", "acme", 2, 5),
            Event.Export.TracesFetched("x-1", "acme", 4, 5),
            Event.Export.TracesFetched("x-1", "acme", 5, 5),
        )
    }

    @Test
    fun `a cluster with no traces reports no fetch progress`() {
        val reported = mutableListOf<Event.Export.TracesFetched>()

        TraceExport(backends::forTenant, backends::forTenant, onProgress = { reported += it }).export("acme", "x-1", range)

        assertThat(reported).isEmpty()
    }

    @Test
    fun `traces are fetched and replayed by a fixed pool, as many at once as the pool and never more`() {
        traces = (1..12).associate { "d%030x".format(it) to start.epochSecond + it * 60L }
        val most = holdUntilConcurrent(4) { it.route.startsWith("/api/v2/traces/") }

        val count = export(limit = 100, parallelism = 4)

        assertThat(most.get()).isEqualTo(4)
        assertThat(count.items).isEqualTo(12)
        assertThat(replayed).hasSize(12)
    }

    @Test
    fun `the search windows are searched by the same pool, the halves of a full window together`() {
        // Twenty days is three windows of at most 168 hours, searched at once.
        val most = holdUntilConcurrent(3) { it.route == "/api/search" }

        export(parallelism = 3)

        assertThat(most.get()).isEqualTo(3)
    }

    @Test
    fun `a search asks for one span per trace, since only the trace ids are used`() {
        export()

        assertThat(backends.calls.filter { it.route == "/api/search" }.map { it.param("spss") }).containsOnly("1")
    }

    @Test
    fun `a failure in one of the parallel fetches fails the export with its own message`() {
        traces = (1..6).associate { "e%030x".format(it) to start.epochSecond + it * 60L }
        replayStatus = 503

        assertThatThrownBy { export(parallelism = 3) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("503")
    }

    @Test
    fun `no search window is longer than 168 hours, and together they cover the range`() {
        export()

        val windows = backends.calls.filter { it.route == "/api/search" }.map { it.param("start")!!.toLong() to it.param("end")!!.toLong() }
        assertThat(windows).allSatisfy { (from, to) -> assertThat(to - from).isLessThanOrEqualTo(Duration.ofHours(168).seconds) }
        assertThat(windows.minOf { it.first }).isEqualTo(range.start.epochSecond)
        assertThat(windows.maxOf { it.second }).isEqualTo(range.end.epochSecond)
        assertThat(windows.zipWithNext()).allSatisfy { (a, b) -> assertThat(b.first).isEqualTo(a.second) }
        assertThat(backends.calls.first { it.route == "/api/search" }.param("q")).isEqualTo("{ resource.cluster = \"x-1\" }")
    }

    @Test
    fun `a window that comes back full is split until every trace is found, and each trace is replayed once`() {
        traces = (1..25).associate { "a%030x".format(it) to start.epochSecond + it * 3600L }

        val count = export(limit = 4)

        assertThat(count.items).isEqualTo(25)
        assertThat(replayed).hasSize(25)
        val searches = backends.calls.filter { it.route == "/api/search" }
        assertThat(searches.map { it.param("limit") }).containsOnly("4")
    }

    @Test
    fun `only the cluster's own resource spans are replayed, with hex ids, in the cluster's tenant`() {
        traces = mapOf("0abc0000000000000000000000000001" to start.epochSecond + 60)

        export()

        val body = Json.parseToJsonElement(replayed.single()).jsonObject
        val resources = body.getValue("resourceSpans").jsonArray
        assertThat(resources).hasSize(1)
        val span =
            resources[0]
                .jsonObject
                .getValue("scopeSpans")
                .jsonArray[0]
                .jsonObject
                .getValue("spans")
                .jsonArray[0]
                .jsonObject
        assertThat(span.getValue("traceId").jsonPrimitive.content).isEqualTo("0abc0000000000000000000000000001")
        assertThat(span.getValue("spanId").jsonPrimitive.content).isEqualTo("0102030405060708")
        assertThat(span.getValue("parentSpanId").jsonPrimitive.content).isEqualTo("1112131415161718")
        assertThat(
            span
                .getValue("links")
                .jsonArray[0]
                .jsonObject
                .getValue("spanId")
                .jsonPrimitive.content,
        ).isEqualTo("2122232425262728")
        val fetch = backends.calls.single { it.route.startsWith("/api/v2/traces/") }
        assertThat(fetch.param("start")).isEqualTo(range.start.epochSecond.toString())
        assertThat(fetch.param("end")).isEqualTo(range.end.epochSecond.toString())
        assertThat(backends.calls.filter { it.route == "/v1/traces" }.map { it.tenant }).containsOnly("acme")
    }

    @Test
    fun `the export waits for Tempo longer than Tempo waits for a cold read, so Tempo's own answer arrives`() {
        traces = mapOf("0abc0000000000000000000000000002" to start.epochSecond + 60)

        export()

        val tempoWait = Duration.ofSeconds(Constants.LocalStack.COLD_READ_TIMEOUT_SECONDS.toLong())
        val reads = backends.calls.filter { it.route == "/api/search" || it.route.startsWith("/api/v2/traces/") }
        assertThat(reads.map { it.route.substringAfter("/api/").substringBefore('/') }).contains("search", "v2")
        assertThat(reads).allSatisfy { assertThat(it.timeout).isGreaterThan(tempoWait) }
    }

    @Test
    fun `a cluster with no traces counts zero`() {
        assertThat(export()).isEqualTo(SignalCount.ZERO)
    }

    @Test
    fun `a one-second window still full fails rather than drop traces`() {
        traces = (1..5).associate { "b%030x".format(it) to start.epochSecond + 10 }

        assertThatThrownBy { export(limit = 2) }.hasMessageContaining("x-1")
    }

    @Test
    fun `a replay the writer refuses fails with its status`() {
        traces = mapOf("c0000000000000000000000000000001" to start.epochSecond + 60)
        replayStatus = 429

        assertThatThrownBy { export() }.hasMessageContaining("429")
    }

    @Test
    fun `an id already in hex is kept as it is`() {
        assertThat(OtlpIds.hex("0102030405060708")).isEqualTo("0102030405060708")
        assertThat(OtlpIds.hex(b64("00000000000000000000000000000001"))).isEqualTo("00000000000000000000000000000001")
    }
}
