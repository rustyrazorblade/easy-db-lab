package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.RedisEndpoint
import com.rustyrazorblade.easydblab.events.RedisUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module

class StartupRedisCheckTest : BaseKoinTest() {
    private val endpoint = RedisEndpoint.parse("redis://127.0.0.1:1/events")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<EventBus> { throw RedisUnavailableException(endpoint, IllegalStateException("connection refused")) }
            },
        )

    @Test
    fun `a Redis that does not answer while the bus is built gives the message naming the variable and URL`() {
        val result = StartupRedisCheck.resolveEventBus(getKoin())

        assertThat(result).isInstanceOf(StartupRedisCheck.Result.RedisUnavailable::class.java)
        val message = (result as StartupRedisCheck.Result.RedisUnavailable).message
        assertThat(message).contains(Constants.EventBus.REDIS_URL_ENV_VAR).contains("redis://127.0.0.1:1/events")
    }

    @Test
    fun `finds the Redis failure anywhere in the cause chain`() {
        val wrapped = RuntimeException("koin", RuntimeException("inner", RedisUnavailableException(endpoint, IllegalStateException("x"))))

        assertThat(StartupRedisCheck.redisFailure(wrapped)).isInstanceOf(RedisUnavailableException::class.java)
    }

    @Test
    fun `another failure is not taken for a Redis failure`() {
        assertThat(StartupRedisCheck.redisFailure(RuntimeException("other"))).isNull()
    }
}
