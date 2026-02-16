package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.SocksProxyState
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * Tests for VictoriaStreamService.
 *
 * Uses mocked OkHttp clients to verify URL construction and error handling.
 */
class VictoriaStreamServiceTest {
    private lateinit var mockSocksProxyService: SocksProxyService
    private lateinit var mockHttpClientFactory: HttpClientFactory
    private lateinit var mockProxiedClient: OkHttpClient
    private lateinit var service: DefaultVictoriaStreamService

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    @BeforeEach
    fun setup() {
        mockSocksProxyService = mock()
        mockHttpClientFactory = mock()
        mockProxiedClient = mock()

        val proxyState =
            SocksProxyState(
                localPort = 1080,
                gatewayHost = testControlHost,
                startTime = Instant.now(),
            )
        whenever(mockSocksProxyService.ensureRunning(any())).thenReturn(proxyState)
        whenever(mockHttpClientFactory.createClient()).thenReturn(mockProxiedClient)

        service = DefaultVictoriaStreamService(mockSocksProxyService, mockHttpClientFactory)
    }

    @Test
    fun `streamMetrics constructs correct source URL`() {
        val mockCall = mock<Call>()
        whenever(mockProxiedClient.newCall(any())).thenReturn(mockCall)

        // Source request fails — we just want to verify URL construction
        whenever(mockCall.execute()).thenThrow(RuntimeException("Connection refused"))

        val result = service.streamMetrics(testControlHost, "http://target:8428")

        assertThat(result.isFailure).isTrue()

        // Verify the proxied client was called with a request to the correct source URL
        verify(mockProxiedClient).newCall(
            argThat<Request> { request ->
                val url = request.url.toString()
                url.contains("10.0.1.5:${Constants.K8s.VICTORIAMETRICS_PORT}") &&
                    url.contains(Constants.Victoria.METRICS_EXPORT_PATH)
            },
        )
    }

    @Test
    fun `streamLogs constructs correct source URL`() {
        val mockCall = mock<Call>()
        whenever(mockProxiedClient.newCall(any())).thenReturn(mockCall)
        whenever(mockCall.execute()).thenThrow(RuntimeException("Connection refused"))

        val result = service.streamLogs(testControlHost, "http://target:9428")

        assertThat(result.isFailure).isTrue()

        verify(mockProxiedClient).newCall(
            argThat<Request> { request ->
                val url = request.url.toString()
                url.contains("10.0.1.5:${Constants.K8s.VICTORIALOGS_PORT}") &&
                    url.contains(Constants.Victoria.LOGS_EXPORT_PATH)
            },
        )
    }

    @Test
    fun `streamMetrics reports source error`() {
        val mockCall = mock<Call>()
        whenever(mockProxiedClient.newCall(any())).thenReturn(mockCall)

        val errorResponse =
            Response
                .Builder()
                .code(500)
                .message("Internal Server Error")
                .protocol(Protocol.HTTP_1_1)
                .request(Request.Builder().url("http://fake").build())
                .body("Server error".toResponseBody())
                .build()

        whenever(mockCall.execute()).thenReturn(errorResponse)

        val result = service.streamMetrics(testControlHost, "http://target:8428")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Source returned HTTP 500")
    }

    @Test
    fun `streamMetrics ensures socks proxy is running`() {
        val mockCall = mock<Call>()
        whenever(mockProxiedClient.newCall(any())).thenReturn(mockCall)
        whenever(mockCall.execute()).thenThrow(RuntimeException("Connection refused"))

        service.streamMetrics(testControlHost, "http://target:8428")

        verify(mockSocksProxyService).ensureRunning(testControlHost)
    }

    @Test
    fun `constants have expected values`() {
        assertThat(Constants.Victoria.METRICS_EXPORT_PATH).isEqualTo("/api/v1/export/native")
        assertThat(Constants.Victoria.METRICS_IMPORT_PATH).isEqualTo("/api/v1/import/native")
        assertThat(Constants.Victoria.LOGS_EXPORT_PATH).isEqualTo("/select/logsql/query")
        assertThat(Constants.Victoria.LOGS_IMPORT_PATH).isEqualTo("/insert/jsonline")
        assertThat(Constants.Victoria.DEFAULT_METRICS_MATCH).isEqualTo("""{__name__!=""}""")
        assertThat(Constants.Victoria.DEFAULT_LOGS_QUERY).isEqualTo("*")
    }
}
