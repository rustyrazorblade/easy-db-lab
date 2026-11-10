package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class RedisEventListenerTest {
    @Test
    fun `publishes events to the Redis channel as JSON envelopes`() {
        RedisTestSupport.newContainer().use { redis ->
            redis.start()
            val listener = RedisEventListener(RedisPublisher(RedisEndpoint.parse(RedisTestSupport.url(redis, "test-events"))))
            val envelope =
                EventEnvelope(
                    event = Event.Cassandra.Starting("node0"),
                    timestamp = "2026-02-23T10:15:30.123Z",
                    commandName = "start",
                )

            val messages = RedisTestSupport.collect(redis, "test-events", expected = 1) { listener.onEvent(envelope) }

            assertThat(messages).hasSize(1)
            val deserialized = EventEnvelope.fromJson(messages[0])
            assertThat(deserialized.event).isEqualTo(Event.Cassandra.Starting("node0"))
            assertThat(deserialized.timestamp).isEqualTo("2026-02-23T10:15:30.123Z")
            assertThat(deserialized.commandName).isEqualTo("start")
            listener.close()
        }
    }

    @Test
    fun `drops the event without throwing after Redis stops, and the console still gets every event`() {
        RedisTestSupport.newContainer().use { redis ->
            redis.start()
            val listener = RedisEventListener(RedisPublisher(RedisEndpoint.parse(RedisTestSupport.url(redis, "test-events"))))
            val bus =
                EventBus().apply {
                    // Redis first, so a throwing listener would keep the console from the event.
                    addListener(listener)
                    addListener(ConsoleEventListener())
                }
            redis.stop()

            val originalOut = System.out
            val captured = ByteArrayOutputStream()
            System.setOut(PrintStream(captured))
            try {
                assertThatCode {
                    bus.emit(Event.Cassandra.Starting("node0"))
                    bus.emit(Event.Cassandra.Starting("node1"))
                }.doesNotThrowAnyException()
            } finally {
                System.setOut(originalOut)
            }

            assertThat(captured.toString().lines().filter { it.isNotBlank() })
                .containsExactly("Starting Cassandra on node0...", "Starting Cassandra on node1...")
            listener.close()
        }
    }
}
