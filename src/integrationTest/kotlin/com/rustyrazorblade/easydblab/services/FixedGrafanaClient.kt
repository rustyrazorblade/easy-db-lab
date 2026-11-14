package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceEndpoint
import okhttp3.OkHttpClient

/**
 * A [DefaultGrafanaClient] for integration tests: Grafana is opened at a fixed placeholder address
 * with no proxy, and every request goes out on [client], whose interceptor redirects it to the test's
 * Grafana or mock server.
 */
fun fixedGrafanaClient(
    eventBus: EventBus,
    client: OkHttpClient,
): DefaultGrafanaClient =
    DefaultGrafanaClient(
        eventBus,
        ServiceAccess { target -> ServiceEndpoint.Plain("10.0.1.1", target.servicePort) },
        object : HttpClientFactory {
            override fun createClient(): OkHttpClient = client

            override fun close() = Unit
        },
    )
