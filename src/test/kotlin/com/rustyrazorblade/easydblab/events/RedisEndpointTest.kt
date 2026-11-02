package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
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
}
