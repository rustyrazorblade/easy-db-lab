package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RedisPublisherIntegrationTest {
    @Test
    fun `publish reaches a subscriber on the channel`() {
        RedisTestSupport.newContainer().use { redis ->
            redis.start()
            val publisher = RedisPublisher(RedisEndpoint.parse(RedisTestSupport.url(redis, "camera")))

            val messages = RedisTestSupport.collect(redis, "camera", expected = 1) { publisher.publish("{\"x\":1}") }

            assertThat(messages).containsExactly("{\"x\":1}")
            publisher.close()
        }
    }

    @Test
    fun `the constructor fails naming the variable and the URL when Redis does not answer`() {
        val url = RedisTestSupport.deadUrl("camera")

        assertThatThrownBy { RedisPublisher(RedisEndpoint.parse(url)) }
            .isInstanceOf(RedisUnavailableException::class.java)
            .hasMessageContaining(Constants.EventBus.REDIS_URL_ENV_VAR)
            .hasMessageContaining(url)
    }

    @Test
    fun `ping and publish fail after Redis stops`() {
        RedisTestSupport.newContainer().use { redis ->
            redis.start()
            val publisher = RedisPublisher(RedisEndpoint.parse(RedisTestSupport.url(redis, "camera")))
            publisher.ping()

            redis.stop()

            assertThatThrownBy { publisher.ping() }.isInstanceOf(RuntimeException::class.java)
            assertThatThrownBy { publisher.publish("{}") }.isInstanceOf(RuntimeException::class.java)
            publisher.close()
        }
    }
}
