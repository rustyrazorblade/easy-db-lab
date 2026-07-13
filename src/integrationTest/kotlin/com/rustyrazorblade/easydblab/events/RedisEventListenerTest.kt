package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPubSub
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

        val subscriber = Jedis(redis.host, redis.getMappedPort(6379))
        val pubSub =
            object : JedisPubSub() {
                override fun onMessage(
                    channel: String,
                    message: String,
                ) {
                    receivedMessages.add(message)
                    latch.countDown()
                }
            }

        val subscribeThread =
            Thread {
                subscriber.subscribe(pubSub, "test-events")
            }
        subscribeThread.start()

        // Small delay to ensure subscription is active
        Thread.sleep(200)

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

        pubSub.unsubscribe()
        subscribeThread.join(2000)
        subscriber.close()
    }

    @Test
    fun `fails fast when Redis is unavailable`() {
        assertThatThrownBy {
            RedisEventListener("redis://localhost:19999/test")
        }.isInstanceOf(Exception::class.java)
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
