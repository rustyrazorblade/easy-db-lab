package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoute
import com.rustyrazorblade.easydblab.proxy.RouteLookup
import io.github.resilience4j.core.functions.Either
import io.github.resilience4j.retry.RetryConfig
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get

/**
 * The stream's Redis reconnect is the network retry setting: three attempts, 1 s and then 2 s
 * apart.  The service the Koin module builds uses it.
 */
class MetricsStreamReconnectConfigTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            metricsStreamModule,
            module {
                factory<SamplePublisher> { FakeSamplePublisher() }
                factory {
                    CycleQueries(
                        loadState = { runningCluster() },
                        routes = { RouteLookup.Usable(ObservabilityRoute.Direct) },
                        httpClientFactory =
                            object : HttpClientFactory {
                                override fun createClient(): OkHttpClient = OkHttpClient()

                                override fun close() = Unit
                            },
                    )
                }
            },
        )

    private fun waitBefore(
        config: RetryConfig,
        attempt: Int,
    ): Long =
        config
            .getIntervalBiFunction<Unit>()
            .apply(attempt, Either.left<Throwable, Unit>(RedisPublishException("down", RuntimeException())))

    @Test
    fun `the service from the module reconnects 3 times, 1 s then 2 s apart`() {
        val config = get<MetricsStreamService>().reconnect

        assertThat(config.maxAttempts).isEqualTo(3)
        assertThat(waitBefore(config, 1)).isEqualTo(1000L)
        assertThat(waitBefore(config, 2)).isEqualTo(2000L)
        assertThat(config.exceptionPredicate.test(RedisPublishException("down", RuntimeException()))).isTrue()
    }
}
