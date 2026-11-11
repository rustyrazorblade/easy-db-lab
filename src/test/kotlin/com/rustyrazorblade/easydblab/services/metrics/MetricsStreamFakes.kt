package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureStatus
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.ObservabilityHttp
import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import io.github.resilience4j.retry.RetryConfig
import java.io.IOException
import java.net.URLDecoder
import java.time.Duration

/** A cluster in the UP state with a control node, as `metrics stream` needs it. */
internal fun runningCluster(): ClusterState =
    ClusterState(
        name = "lab",
        versions = mutableMapOf(),
        hosts = mapOf(ServerType.Control to listOf(ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a", "i-1"))),
    ).apply { infrastructureStatus = InfrastructureStatus.UP }

/** Reconnect settings with the network config's three attempts and no real wait. */
internal fun fastReconnect(): RetryConfig =
    RetryConfig
        .custom<Unit>()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(1))
        .retryOnException { it is RuntimeException }
        .build()

/** An event bus that keeps every event it carries, in order. */
internal class RecordingBus {
    val events = mutableListOf<Event>()
    val bus =
        EventBus().apply {
            addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }
}

/**
 * Mimir's HTTP API as the real query service sees it: each query is answered by the first rule
 * whose metric name the PromQL contains.  A rule can answer with a body, a status, or no response.
 */
internal class FakeMimirHttp : ObservabilityHttp {
    sealed interface Answer {
        data class Body(
            val code: Int,
            val body: String,
        ) : Answer

        data object NoResponse : Answer
    }

    private val rules = mutableListOf<Pair<String, () -> Answer>>()
    val queries = mutableListOf<String>()

    fun on(
        metricName: String,
        answer: () -> Answer,
    ) {
        rules += metricName to answer
    }

    override fun get(
        port: Int,
        pathAndQuery: String,
        timeout: Duration,
    ): ObservabilityResponse {
        val promql = URLDecoder.decode(pathAndQuery.substringAfter("query="), Charsets.UTF_8)
        queries += promql
        val answer = rules.firstOrNull { (name, _) -> promql.contains(name) }?.second?.invoke() ?: success()
        return when (answer) {
            is Answer.Body -> ObservabilityResponse(answer.code, answer.body)
            Answer.NoResponse -> throw IOException("connection refused")
        }
    }

    override fun post(
        port: Int,
        path: String,
        body: String,
        contentType: String,
        timeout: Duration,
    ): ObservabilityResponse = error("the stream only queries")

    companion object {
        /** A successful vector of [series], each a label map and a value. */
        fun success(vararg series: Pair<Map<String, String>, String>): Answer.Body {
            val result =
                series.joinToString(",") { (labels, value) ->
                    val metric = labels.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
                    "{\"metric\":{$metric},\"value\":[1700000000,\"$value\"]}"
                }
            return Answer.Body(200, "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[$result]}}")
        }
    }
}

/** A Redis that takes samples until told to fail; each failure is one ping or publish. */
internal class FakeSamplePublisher : SamplePublisher {
    override val url: String = "redis://redis.test:6379/camera"
    val published = mutableListOf<Event.Metrics.Sample>()
    var pings = 0
    var failPings = 0
    var failPublishes = 0
    var onPublish: () -> Unit = {}
    var onPing: () -> Unit = {}

    override fun publish(sample: Event.Metrics.Sample) {
        onPublish()
        if (failPublishes > 0) {
            failPublishes--
            throw RedisPublishException("publish refused", IOException("refused"))
        }
        published += sample
    }

    override fun ping() {
        pings++
        onPing()
        if (failPings > 0) {
            failPings--
            throw RedisPublishException("ping refused", IOException("refused"))
        }
    }
}
