package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventContext
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.RedisEndpoint
import com.rustyrazorblade.easydblab.events.RedisPublisher
import com.rustyrazorblade.easydblab.events.RedisTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

class RedisSamplePublisherIntegrationTest {
    @AfterEach
    fun clearContext() {
        EventContext.clear()
    }

    @Test
    fun `a sample arrives as an envelope with the sample, a timestamp and the command name`() {
        RedisTestSupport.newContainer().use { redis ->
            redis.start()
            val url = RedisTestSupport.url(redis, "camera")
            val publisher = RedisSamplePublisher(RedisPublisher(RedisEndpoint.parse(url)))
            EventContext.push("stream")
            val before = Instant.now()

            val messages =
                RedisTestSupport.collect(redis, "camera", expected = 1) {
                    publisher.publish(Event.Metrics.Sample("node.cpu", "db0", 12.5))
                }

            assertThat(publisher.url).isEqualTo(url)
            val envelope = EventEnvelope.fromJson(messages.single())
            assertThat(envelope.event).isEqualTo(Event.Metrics.Sample("node.cpu", "db0", 12.5))
            assertThat(envelope.commandName).isEqualTo("stream")
            assertThat(Instant.parse(envelope.timestamp)).isAfterOrEqualTo(before).isBeforeOrEqualTo(Instant.now())
        }
    }

    @Test
    fun `ping and publish throw RedisPublishException after Redis stops`() {
        RedisTestSupport.newContainer().use { redis ->
            redis.start()
            val publisher = RedisSamplePublisher(RedisPublisher(RedisEndpoint.parse(RedisTestSupport.url(redis, "camera"))))
            publisher.ping()

            redis.stop()

            assertThatThrownBy { publisher.ping() }.isInstanceOf(RedisPublishException::class.java)
            assertThatThrownBy { publisher.publish(Event.Metrics.Sample("node.cpu", "db0", 1.0)) }
                .isInstanceOf(RedisPublishException::class.java)
        }
    }
}
