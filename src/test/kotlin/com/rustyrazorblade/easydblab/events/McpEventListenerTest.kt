package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class McpEventListenerTest {
    @Test
    fun `buffers events for retrieval`() {
        val listener = McpEventListener()
        val envelope =
            EventEnvelope(
                event = Event.Cassandra.Starting("node0"),
                timestamp = "2026-02-23T10:15:30.123Z",
                commandName = "start",
            )

        listener.onEvent(envelope)

        val envelopes = listener.getAndClearEnvelopes()
        assertThat(envelopes).hasSize(1)
        assertThat(envelopes[0].event).isInstanceOf(Event.Cassandra.Starting::class.java)
        assertThat((envelopes[0].event as Event.Cassandra.Starting).host).isEqualTo("node0")
    }

    @Test
    fun `getAndClearEnvelopes clears buffer`() {
        val listener = McpEventListener()
        listener.onEvent(
            EventEnvelope(
                event = Event.Message("test"),
                timestamp = "2026-02-23T10:15:30.123Z",
            ),
        )

        listener.getAndClearEnvelopes()
        assertThat(listener.getAndClearEnvelopes()).isEmpty()
    }

    @Test
    fun `close clears buffer`() {
        val listener = McpEventListener()
        listener.onEvent(
            EventEnvelope(
                event = Event.Message("test"),
                timestamp = "2026-02-23T10:15:30.123Z",
            ),
        )

        listener.close()
        assertThat(listener.getAndClearEnvelopes()).isEmpty()
    }

    @Test
    fun `buffers multiple events from different domains`() {
        val listener = McpEventListener()

        listener.onEvent(
            EventEnvelope(
                event = Event.Cassandra.Starting("node0"),
                timestamp = "2026-02-23T10:15:30.123Z",
                commandName = "start",
            ),
        )
        listener.onEvent(
            EventEnvelope(
                event = Event.K8s.ManifestsApplying,
                timestamp = "2026-02-23T10:15:31.456Z",
                commandName = "up",
            ),
        )

        val envelopes = listener.getAndClearEnvelopes()
        assertThat(envelopes).hasSize(2)
        assertThat(envelopes[0].event).isInstanceOf(Event.Cassandra.Starting::class.java)
        assertThat(envelopes[1].event).isInstanceOf(Event.K8s.ManifestsApplying::class.java)
    }
}
