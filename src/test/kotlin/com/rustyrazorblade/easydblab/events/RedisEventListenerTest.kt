package com.rustyrazorblade.easydblab.events

import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisEventListenerTest {
    companion object {
        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7-alpine").withExposedPorts(6379)
    }

    private lateinit var listener: RedisEventListener
    private lateinit var redisUrl: String

    @BeforeAll
    fun setup() {
        redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}/test-events"
        listener = RedisEventListener(redisUrl)
    }

    @AfterAll
    fun teardown() {
        listener.close()
    }

    @Test
    fun `publishes events to Redis channel and deserializes correctly`() {
        val receivedMessages = mutableListOf<String>()
        val latch = CountDownLatch(1)

        val subscriberClient =
            RedisClient.create(
                "redis://${redis.host}:${redis.getMappedPort(6379)}",
            )
        val subConnection = subscriberClient.connectPubSub()
        subConnection.addListener(
            object : RedisPubSubAdapter<String, String>() {
                override fun message(
                    channel: String,
                    message: String,
                ) {
                    receivedMessages.add(message)
                    latch.countDown()
                }
            },
        )
        subConnection.sync().subscribe("test-events")

        // Small delay to ensure subscription is active
        Thread.sleep(100)

        val envelope =
            EventEnvelope(
                event = Event.Cassandra.Starting("node0"),
                timestamp = "2026-02-23T10:15:30.123Z",
                commandName = "start",
            )
        listener.onEvent(envelope)

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(receivedMessages).hasSize(1)

        val deserialized = EventEnvelope.fromJson(receivedMessages[0])
        assertThat(deserialized.event).isInstanceOf(Event.Cassandra.Starting::class.java)
        assertThat((deserialized.event as Event.Cassandra.Starting).host).isEqualTo("node0")
        assertThat(deserialized.timestamp).isEqualTo("2026-02-23T10:15:30.123Z")
        assertThat(deserialized.commandName).isEqualTo("start")

        subConnection.close()
        subscriberClient.shutdown()
    }

    @Test
    fun `gracefully handles unavailable Redis`() {
        val badListener = RedisEventListener("redis://localhost:19999/test")

        val envelope =
            EventEnvelope(
                event = Event.Message("test"),
                timestamp = "2026-02-23T10:15:30.123Z",
            )
        // Should not throw
        badListener.onEvent(envelope)
        badListener.close()
    }

    @Test
    fun `uses default channel when no path specified`() {
        val defaultListener =
            RedisEventListener(
                "redis://${redis.host}:${redis.getMappedPort(6379)}",
            )
        defaultListener.close()
    }
}
