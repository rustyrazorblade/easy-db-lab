package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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
                factory<TailscaleService> { DefaultTailscaleService(get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockRemoteOps = mock()
        tailscaleService = getKoin().get()
    }

    // ========== START TAILSCALE TESTS ==========

    @Test
    fun `startTailscale should start daemon and authenticate`() {
        // Given
        val authKey = "tskey-auth-xxx"
        val hostname = "control0"
        val cidr = "10.0.0.0/16"
        val successResponse = Response(text = "", stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo systemctl start tailscaled"), any(), any()))
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
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq("sudo systemctl start tailscaled"), any(), any())
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
    fun `startTailscale should return failure when daemon start fails`() {
        // Given
        val authKey = "tskey-auth-xxx"
        val hostname = "control0"
        val cidr = "10.0.0.0/16"

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo systemctl start tailscaled"), any(), any()))
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

        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq("sudo systemctl start tailscaled"), any(), any()))
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
}
