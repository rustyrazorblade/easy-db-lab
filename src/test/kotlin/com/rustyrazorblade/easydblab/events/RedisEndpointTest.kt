package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RedisEndpointTest : BaseKoinTest() {
    @Test
    fun `parses host, port and channel from the URL`() {
        val endpoint = RedisEndpoint.parse("redis://redis.example:6390/camera-events")

        assertThat(endpoint.host).isEqualTo("redis.example")
        assertThat(endpoint.port).isEqualTo(6390)
        assertThat(endpoint.channel).isEqualTo("camera-events")
        assertThat(endpoint.url).isEqualTo("redis://redis.example:6390/camera-events")
    }

    @Test
    fun `uses the default channel when the URL has no path`() {
        val endpoint = RedisEndpoint.parse("redis://redis.example:6390")

        assertThat(endpoint.channel).isEqualTo(Constants.EventBus.DEFAULT_CHANNEL)
    }

    @Test
    fun `uses the default channel when the path is only a slash`() {
        val endpoint = RedisEndpoint.parse("redis://redis.example:6390/")

        assertThat(endpoint.channel).isEqualTo(Constants.EventBus.DEFAULT_CHANNEL)
    }

    @Test
    fun `uses the default Redis port when the URL has none`() {
        val endpoint = RedisEndpoint.parse("redis://redis.example/events")

        assertThat(endpoint.port).isEqualTo(Constants.EventBus.DEFAULT_REDIS_PORT)
    }

    @Test
    fun `the URL it reports never carries a password`() {
        val endpoint = RedisEndpoint.parse("redis://camera:s3cret@redis.example:6390/events")

        assertThat(endpoint.url).doesNotContain("s3cret").isEqualTo("redis://***@redis.example:6390/events")
        assertThat(endpoint.host).isEqualTo("redis.example")
    }

    @Test
    fun `a URL with no host is refused, naming the variable and the value`() {
        listOf("localhost:6379", "redis:/events", "redis:///events").forEach { url ->
            assertThatThrownBy { RedisEndpoint.parse(url) }
                .describedAs(url)
                .isInstanceOf(RedisConfigurationException::class.java)
                .hasMessageContaining(Constants.EventBus.REDIS_URL_ENV_VAR)
                .hasMessageContaining(url)
        }
    }

    @Test
    fun `a URL that is not a redis URL is refused`() {
        assertThatThrownBy { RedisEndpoint.parse("http://redis.example:6379/events") }
            .isInstanceOf(RedisConfigurationException::class.java)
            .hasMessageContaining("redis://")
    }

    @Test
    fun `a URL that does not parse is refused without leaking its password`() {
        assertThatThrownBy { RedisEndpoint.parse("redis://camera:s3cret@redis example/events") }
            .isInstanceOf(RedisConfigurationException::class.java)
            .hasMessageContaining(Constants.EventBus.REDIS_URL_ENV_VAR)
            .hasMessageNotContaining("s3cret")
    }
}
