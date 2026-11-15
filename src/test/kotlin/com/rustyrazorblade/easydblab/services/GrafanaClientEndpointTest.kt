package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.services.access.ClusterServices
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceEndpoint
import com.rustyrazorblade.easydblab.services.access.ServiceTarget
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * GrafanaClient builds every URL from the endpoint the seam opens for Grafana, asking again on each
 * call. The HTTP client stands in at the network boundary through an interceptor.
 */
class GrafanaClientEndpointTest {
    private val requests = mutableListOf<Request>()
    private val opened = mutableListOf<ServiceTarget>()
    private val endpoints =
        ArrayDeque(listOf(ServiceEndpoint.ViaSocks("10.0.1.5", 3000, 21000), ServiceEndpoint.Plain("127.0.0.1", 25001)))

    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests.add(chain.request())
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"id": 7}""".toResponseBody())
                        .build()
                },
            ).build()

    private val factory =
        object : HttpClientFactory {
            override fun createClient(): OkHttpClient = client

            override fun close() = Unit
        }

    private val access =
        ServiceAccess { target ->
            opened.add(target)
            endpoints.removeFirst()
        }

    private val grafana = DefaultGrafanaClient(EventBus(), access, factory)

    @Test
    fun `each call reaches Grafana at the endpoint the seam opens for it now`() {
        grafana.createAnnotation(GrafanaAnnotationRequest(text = "first", time = 1L))
        grafana.createAnnotation(GrafanaAnnotationRequest(text = "second", time = 1L))

        assertThat(opened).containsExactly(ClusterServices.GRAFANA, ClusterServices.GRAFANA)
        assertThat(requests.map { it.url.toString() })
            .containsExactly("http://10.0.1.5:3000/api/annotations", "http://127.0.0.1:25001/api/annotations")
    }
}
