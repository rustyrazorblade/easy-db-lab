package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class EventBusTest {
    private val eventBus = EventBus()

    @AfterEach
    fun cleanup() {
        eventBus.close()
        EventContext.clear()
    }

    @Test
    fun `dispatches to multiple listeners`() {
        val listener1 = RecordingListener()
        val listener2 = RecordingListener()
        eventBus.addListener(listener1)
        eventBus.addListener(listener2)

        eventBus.emit(Event.Message("hello"))

        assertThat(listener1.events).hasSize(1)
        assertThat(listener2.events).hasSize(1)
    }

    @Test
    fun `envelope includes timestamp`() {
        val listener = RecordingListener()
        eventBus.addListener(listener)

        eventBus.emit(Event.Message("hello"))

        assertThat(listener.events.first().timestamp).isNotEmpty()
    }

    @Test
    fun `envelope includes commandName from context`() {
        val listener = RecordingListener()
        eventBus.addListener(listener)

        EventContext.push("start")
        eventBus.emit(Event.Message("hello"))
        EventContext.pop()

        assertThat(listener.events.first().commandName).isEqualTo("start")
    }

    @Test
    fun `envelope has null commandName when no context`() {
        val listener = RecordingListener()
        eventBus.addListener(listener)

        eventBus.emit(Event.Message("hello"))

        assertThat(listener.events.first().commandName).isNull()
    }

    @Test
    fun `remove listener stops delivery`() {
        val listener = RecordingListener()
        eventBus.addListener(listener)
        eventBus.emit(Event.Message("first"))
        eventBus.removeListener(listener)
        eventBus.emit(Event.Message("second"))

        assertThat(listener.events).hasSize(1)
    }

    @Test
    fun `close propagates to all listeners`() {
        val listener1 = RecordingListener()
        val listener2 = RecordingListener()
        eventBus.addListener(listener1)
        eventBus.addListener(listener2)

        eventBus.close()

        assertThat(listener1.closed).isTrue()
        assertThat(listener2.closed).isTrue()
    }

    /** Listener that records all received envelopes for assertions. */
    private class RecordingListener : EventListener {
        val events = mutableListOf<EventEnvelope>()
        var closed = false

        override fun onEvent(envelope: EventEnvelope) {
            events.add(envelope)
        }

        override fun close() {
            closed = true
        }
    }
}
