package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import okhttp3.Response as OkHttpResponse

/**
 * Test suite for TailscaleService.
 *
 * These tests verify Tailscale VPN operations including starting/stopping
 * the daemon and checking connection status.
 *
 * Note: OAuth API tests require mocking HTTP calls, so this test suite
 * focuses on SSH-based operations that can be easily mocked.
 */
class TailscaleServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var tailscaleService: TailscaleService

    private val testHost =
        Host(
            public = "54.123.45.67",
            private = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mockRemoteOps }
                // Zero daemon startup delay so startTailscale tests do not sit through the
                // production pause; only timing changes, not the behavior under test.
                factory<TailscaleService> { DefaultTailscaleService(get(), get(), daemonStartupDelay = Duration.ZERO) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockRemoteOps = mock()
        tailscaleService = getKoin().get()
    }

    // ========== START TAILSCALE TESTS ==========

    @Test
    fun `startTailscale should enable daemon for boot and authenticate`() {
        // Given
        val authKey = "tskey-auth-xxx"
        val hostname = "control0"
        val cidr = "10.0.0.0/16"
        val successResponse = Response(text = "", stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo systemctl enable --now tailscaled"), any(), any()))
            .thenReturn(successResponse)
        whenever(
            mockRemoteOps.executeRemotely(
                eq(testHost),
                argThat { contains("tailscale up") && contains("--authkey=") },
                any(),
                any(),
            ),
        ).thenReturn(successResponse)

        // When
        val result = tailscaleService.startTailscale(testHost, authKey, hostname, cidr)

        // Then
        assertThat(result.isSuccess).isTrue()
        // `enable --now` (not plain `start`) so the daemon survives a node reboot.
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq("sudo systemctl enable --now tailscaled"), any(), any())
        verify(mockRemoteOps).executeRemotely(
            eq(testHost),
            argThat {
                contains("tailscale up") &&
                    contains("--authkey=$authKey") &&
                    contains("--hostname=$hostname") &&
                    contains("--advertise-routes=$cidr")
            },
            any(),
            eq(true), // secret=true
        )
    }

    @Test
    fun `startTailscale enables and persists IP forwarding before advertising routes`() {
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).thenReturn(successResponse)

        val result = tailscaleService.startTailscale(testHost, "tskey-auth-xxx", "control0", "10.0.0.0/16")

        assertThat(result.isSuccess).isTrue()
        val commands = argumentCaptor<String>()
        verify(mockRemoteOps, atLeastOnce()).executeRemotely(eq(testHost), commands.capture(), any(), any())
        val forwarding = commands.allValues.indexOfFirst { it.contains("net.ipv4.ip_forward") }
        val tailscaleUp = commands.allValues.indexOfFirst { it.contains("tailscale up") }
        assertThat(forwarding)
            .describedAs("IP forwarding must be enabled before tailscale up: %s", commands.allValues)
            .isNotNegative()
            .isLessThan(tailscaleUp)
        val forwardingCommand = commands.allValues[forwarding]
        // Persisted, so it survives a reboot, and applied now, so `tailscale up` sees it.
        assertThat(forwardingCommand)
            .contains("/etc/sysctl.d/")
            .contains("net.ipv4.ip_forward = 1")
            .contains("net.ipv6.conf.all.forwarding = 1")
            .contains("sysctl -p")
    }

    @Test
    fun `startTailscale should return failure when daemon start fails`() {
        // Given
        val authKey = "tskey-auth-xxx"
        val hostname = "control0"
        val cidr = "10.0.0.0/16"

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo systemctl enable --now tailscaled"), any(), any()))
            .thenThrow(RuntimeException("Failed to start tailscaled"))

        // When
        val result = tailscaleService.startTailscale(testHost, authKey, hostname, cidr)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Failed to start tailscaled")
    }

    @Test
    fun `startTailscale should return failure when authentication fails`() {
        // Given
        val authKey = "tskey-auth-xxx"
        val hostname = "control0"
        val cidr = "10.0.0.0/16"
        val successResponse = Response(text = "", stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo systemctl enable --now tailscaled"), any(), any()))
            .thenReturn(successResponse)
        whenever(
            mockRemoteOps.executeRemotely(
                eq(testHost),
                argThat { contains("tailscale up") },
                any(),
                any(),
            ),
        ).thenThrow(RuntimeException("Authentication failed"))

        // When
        val result = tailscaleService.startTailscale(testHost, authKey, hostname, cidr)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Authentication failed")
    }

    // ========== STOP TAILSCALE TESTS ==========

    @Test
    fun `stopTailscale should disconnect and stop daemon`() {
        // Given
        val successResponse = Response(text = "", stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo tailscale down"), any(), any()))
            .thenReturn(successResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo systemctl stop tailscaled"), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = tailscaleService.stopTailscale(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq("sudo tailscale down"), any(), any())
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq("sudo systemctl stop tailscaled"), any(), any())
    }

    @Test
    fun `stopTailscale should return failure when disconnect fails`() {
        // Given
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo tailscale down"), any(), any()))
            .thenThrow(RuntimeException("Failed to disconnect"))

        // When
        val result = tailscaleService.stopTailscale(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Failed to disconnect")
    }

    // ========== STATUS TESTS ==========

    @Test
    fun `getStatus should return status output`() {
        // Given
        val statusOutput =
            """
            # My devices
            100.64.0.1      control0         -
            """.trimIndent()
        val statusResponse = Response(text = statusOutput, stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo tailscale status"), any(), any()))
            .thenReturn(statusResponse)

        // When
        val result = tailscaleService.getStatus(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(statusOutput)
    }

    @Test
    fun `getStatus should return failure when command fails`() {
        // Given
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo tailscale status"), any(), any()))
            .thenThrow(RuntimeException("Tailscale not running"))

        // When
        val result = tailscaleService.getStatus(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Tailscale not running")
    }

    // ========== CONNECTION CHECK TESTS ==========

    @Test
    fun `isConnected should return true when backend state is Running`() {
        // Given
        val jsonResponse =
            """
            {"BackendState":"Running","Self":{"ID":"12345"}}
            """.trimIndent()
        val statusResponse = Response(text = jsonResponse, stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo tailscale status --json"), eq(false), any()))
            .thenReturn(statusResponse)

        // When
        val result = tailscaleService.isConnected(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
    }

    @Test
    fun `isConnected should return false when backend state is not Running`() {
        // Given
        val jsonResponse =
            """
            {"BackendState":"Stopped","Self":null}
            """.trimIndent()
        val statusResponse = Response(text = jsonResponse, stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo tailscale status --json"), eq(false), any()))
            .thenReturn(statusResponse)

        // When
        val result = tailscaleService.isConnected(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
    }

    @Test
    fun `isConnected should return false when response is blank`() {
        // Given
        val statusResponse = Response(text = "", stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo tailscale status --json"), eq(false), any()))
            .thenReturn(statusResponse)

        // When
        val result = tailscaleService.isConnected(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
    }

    @Test
    fun `isConnected should return failure when command fails`() {
        // Given
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo tailscale status --json"), eq(false), any()))
            .thenThrow(RuntimeException("SSH connection lost"))

        // When
        val result = tailscaleService.isConnected(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("SSH connection lost")
    }

    // ========== AUTH KEY MARK AS SECRET TESTS ==========

    @Test
    fun `startTailscale should mark authkey command as secret`() {
        // Given
        val authKey = "tskey-auth-secret-key"
        val hostname = "control0"
        val cidr = "10.0.0.0/16"
        val successResponse = Response(text = "", stderr = "")

        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .thenReturn(successResponse)

        // When
        tailscaleService.startTailscale(testHost, authKey, hostname, cidr)

        // Then - verify the tailscale up command is marked as secret
        verify(mockRemoteOps).executeRemotely(
            eq(testHost),
            argThat { contains("tailscale up") && contains("--authkey=") },
            any(),
            eq(true), // secret parameter should be true
        )
    }

    @Test
    fun `stopTailscale should not mark commands as secret`() {
        // Given
        val successResponse = Response(text = "", stderr = "")

        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .thenReturn(successResponse)

        // When
        tailscaleService.stopTailscale(testHost)

        // Then - verify commands are not marked as secret (default is false)
        verify(mockRemoteOps).executeRemotely(
            eq(testHost),
            eq("sudo tailscale down"),
            any(),
            eq(false),
        )
        verify(mockRemoteOps).executeRemotely(
            eq(testHost),
            eq("sudo systemctl stop tailscaled"),
            any(),
            eq(false),
        )
    }

    // ========== DEVICE IDENTITY AND REMOVAL ==========

    @Test
    fun `getDeviceId reads the stable node ID the control node reports for itself`() {
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo tailscale status --json"), eq(false), any()))
            .thenReturn(
                Response(
                    text =
                        """{"BackendState":"Running","Self":{"ID":"nSelf123CNTRL","HostName":"control0"},""" +
                            """"Peer":{"k":{"ID":"nPeer456CNTRL","HostName":"control0"}}}""",
                    stderr = "",
                ),
            )

        assertThat(tailscaleService.getDeviceId(testHost).getOrThrow()).isEqualTo("nSelf123CNTRL")
    }

    @Test
    fun `getDeviceId fails when the node reports no identity of its own`() {
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo tailscale status --json"), eq(false), any()))
            .thenReturn(Response(text = """{"BackendState":"NeedsLogin"}""", stderr = ""))

        assertThat(tailscaleService.getDeviceId(testHost).exceptionOrNull())
            .isInstanceOf(TailscaleApiException::class.java)
            .hasMessageContaining("control0")
    }

    @Test
    fun `deleteDevice deletes exactly the recorded device with the OAuth token`() {
        val api = FakeTailscaleApi(deleteStatus = 200)

        serviceWith(api).deleteDevice("client-id", "client-secret", "nSelf123CNTRL")

        val delete = api.requests.single { it.method == "DELETE" }
        assertThat(delete.url.toString()).isEqualTo("https://api.tailscale.com/api/v2/device/nSelf123CNTRL")
        assertThat(delete.header("Authorization")).isEqualTo("Bearer token-abc")
    }

    @Test
    fun `deleteDevice treats a device that is already gone as deleted`() {
        serviceWith(FakeTailscaleApi(deleteStatus = 404)).deleteDevice("client-id", "client-secret", "nGone")
    }

    @Test
    fun `deleteDevice without device-delete permission fails naming the missing scope`() {
        assertThatThrownBy {
            serviceWith(FakeTailscaleApi(deleteStatus = 403)).deleteDevice("client-id", "client-secret", "nSelf123CNTRL")
        }.isInstanceOf(TailscaleApiException::class.java)
            .hasMessageContaining("nSelf123CNTRL")
            .hasMessageContaining("devices:core")
    }

    @Test
    fun `deleteDevice fails on any other API error`() {
        assertThatThrownBy {
            serviceWith(FakeTailscaleApi(deleteStatus = 500)).deleteDevice("client-id", "client-secret", "nSelf123CNTRL")
        }.isInstanceOf(TailscaleApiException::class.java)
            .hasMessageContaining("500")
    }

    private fun serviceWith(api: FakeTailscaleApi) =
        DefaultTailscaleService(
            mockRemoteOps,
            getKoin().get(),
            daemonStartupDelay = Duration.ZERO,
            httpClient = OkHttpClient.Builder().addInterceptor(api).build(),
        )

    /**
     * Stands in for the Tailscale API at the OkHttp boundary: answers the OAuth token exchange with
     * a fixed token and every device DELETE with [deleteStatus], and records each request.
     */
    private class FakeTailscaleApi(
        private val deleteStatus: Int,
    ) : Interceptor {
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
            val request = chain.request()
            requests += request
            val (code, body) =
                when (request.method) {
                    "POST" -> 200 to """{"access_token":"token-abc"}"""
                    else -> deleteStatus to """{"message":"status $deleteStatus"}"""
                }
            return OkHttpResponse
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("status $code")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
