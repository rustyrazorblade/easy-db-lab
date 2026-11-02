package com.rustyrazorblade.easydblab.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetricsStreamEventTest {
    @Test
    fun `a sample goes on the wire as an envelope whose event type is Metrics Sample`() {
        val envelope =
            EventEnvelope(
                event = Event.Metrics.Sample(name = "node.cpu", entity = "db0", value = 42.5),
                timestamp = "2026-11-01T10:00:00Z",
                commandName = "stream",
            )

        val wire = Json.parseToJsonElement(EventEnvelope.toJson(envelope)).jsonObject
        val event = wire.getValue("event").jsonObject

        assertThat(wire.getValue("timestamp").jsonPrimitive.content).isEqualTo("2026-11-01T10:00:00Z")
        assertThat(wire.getValue("commandName").jsonPrimitive.content).isEqualTo("stream")
        assertThat(event.keys).containsExactlyInAnyOrder("type", "name", "entity", "value")
        assertThat(event.string("type")).isEqualTo("Metrics.Sample")
        assertThat(event.string("name")).isEqualTo("node.cpu")
        assertThat(event.string("entity")).isEqualTo("db0")
        assertThat(event.getValue("value").jsonPrimitive.double).isEqualTo(42.5)
        assertThat(EventEnvelope.fromJson(EventEnvelope.toJson(envelope))).isEqualTo(envelope)
    }

    @Test
    fun `invalid names round-trip with every entry and its position`() {
        val event =
            Event.Metrics.InvalidNames(
                listOf(
                    InvalidMetricName(position = 2, value = "", reason = "empty name"),
                    InvalidMetricName(position = 3, value = "x", reason = "unknown name"),
                ),
            )
        val envelope = EventEnvelope(event = event, timestamp = "t", commandName = "stream")

        assertThat(EventEnvelope.fromJson(EventEnvelope.toJson(envelope))).isEqualTo(envelope)
        assertThat(event.toDisplayString()).contains("2").contains("3").contains("'x'").contains("metrics list")
        assertThat(event.isError()).isTrue()
    }

    @Test
    fun `failure and loss events are errors, recovery is not`() {
        assertThat(Event.Metrics.MetricFailing("node.cpu", "HTTP 500").isError()).isTrue()
        assertThat(Event.Metrics.RedisLost("redis://h:1/c", "refused").isError()).isTrue()
        assertThat(Event.Metrics.RedisNotConfigured("EASY_DB_LAB_REDIS_URL").isError()).isTrue()
        assertThat(Event.Metrics.NoRunningCluster("/w").isError()).isTrue()
        assertThat(Event.Metrics.MetricRecovered("node.cpu").isError()).isFalse()
    }

    @Test
    fun `the lost-Redis message names the URL and the not-configured message names the variable`() {
        assertThat(Event.Metrics.RedisLost("redis://h:1/c", "refused").toDisplayString()).contains("redis://h:1/c")
        assertThat(Event.Metrics.RedisNotConfigured("EASY_DB_LAB_REDIS_URL").toDisplayString())
            .contains("EASY_DB_LAB_REDIS_URL")
    }

    @Test
    fun `server ready names the status and swagger URLs and no sse URL`() {
        val text = Event.Server.Ready("http://localhost:8888/status", "http://localhost:8888/swagger").toDisplayString()

        assertThat(text).contains("http://localhost:8888/status").contains("http://localhost:8888/swagger")
        assertThat(text).doesNotContain("/sse")
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
