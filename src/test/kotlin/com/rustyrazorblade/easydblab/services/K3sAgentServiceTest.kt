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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for K3sAgentService following TDD principles.
 *
 * These tests verify K3s agent lifecycle operations (configure, start, stop, restart)
 * and status checks using mocked SSH operations.
 */
class K3sAgentServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var k3sAgentService: K3sAgentService

    private val testHost =
        Host(
            public = "54.123.45.67",
            private = "10.0.1.10",
            alias = "db0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mockRemoteOps }
                factory<K3sAgentService> { DefaultK3sAgentService(get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockRemoteOps = mock()
        k3sAgentService = getKoin().get()
    }

    // ========== CONFIGURE OPERATION TESTS ==========

    @Test
    fun `configure should create config file with server URL and token`() {
        // Given
        val serverUrl = "https://10.0.1.5:6443"
        val token = "K10abcdef1234567890::server:1234567890abcdef"
        val labels = emptyMap<String, String>()
        val successResponse = Response(text = "", stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)
        doNothing().whenever(mockRemoteOps).upload(eq(testHost), any(), any())

        // When
        val result = k3sAgentService.configure(testHost, serverUrl, token, labels)

        // Then
        assertThat(result.isSuccess).isTrue()

        // Verify directory creation and file move
        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps, times(2)).executeRemotely(
            eq(testHost),
            commandCaptor.capture(),
            any(),
            any(),
        )
        assertThat(commandCaptor.firstValue).contains("sudo mkdir -p /etc/rancher/k3s")
        assertThat(commandCaptor.secondValue).contains("sudo mv /tmp/k3s-config.yaml /etc/rancher/k3s/config.yaml")
        assertThat(commandCaptor.secondValue).contains("sudo chmod 600 /etc/rancher/k3s/config.yaml")

        // Verify config file upload
        val destPathCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).upload(
            eq(testHost),
            any(),
            destPathCaptor.capture(),
        )
        assertThat(destPathCaptor.firstValue).isEqualTo("/tmp/k3s-config.yaml")
    }

    @Test
    fun `configure should create config file with node labels`() {
        // Given
        val serverUrl = "https://10.0.1.5:6443"
        val token = "K10abcdef1234567890::server:1234567890abcdef"
        val labels = mapOf("type" to "db")
        val successResponse = Response(text = "", stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)
        doNothing().whenever(mockRemoteOps).upload(eq(testHost), any(), any())

        // When
        val result = k3sAgentService.configure(testHost, serverUrl, token, labels)

        // Then
        assertThat(result.isSuccess).isTrue()

        // Verify config file was uploaded
        verify(mockRemoteOps).upload(
            eq(testHost),
            any(),
            eq("/tmp/k3s-config.yaml"),
        )
    }

    @Test
    fun `configure should return failure when directory creation fails`() {
        // Given
        val serverUrl = "https://10.0.1.5:6443"
        val token = "K10abcdef1234567890::server:1234567890abcdef"
        val labels = emptyMap<String, String>()

        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenThrow(RuntimeException("Permission denied"))

        // When
        val result = k3sAgentService.configure(testHost, serverUrl, token, labels)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Permission denied")
    }

    @Test
    fun `configure should return failure when upload fails`() {
        // Given
        val serverUrl = "https://10.0.1.5:6443"
        val token = "K10abcdef1234567890::server:1234567890abcdef"
        val labels = emptyMap<String, String>()
        val successResponse = Response(text = "", stderr = "")

        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)
        doThrow(RuntimeException("Upload failed")).whenever(mockRemoteOps).upload(eq(testHost), any(), any())

        // When
        val result = k3sAgentService.configure(testHost, serverUrl, token, labels)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Upload failed")
    }

    // ========== START OPERATION TESTS ==========

    @Test
    fun `start should execute k3s agent startup script successfully`() {
        // Given
        val serverUrl = "https://10.0.1.5:6443"
        val token = "K10abcdef1234567890::server:1234567890abcdef"
        val configContent =
            """
            server: $serverUrl
            token: $token
            """.trimIndent()

        val configCommand = "cat /etc/rancher/k3s/config.yaml"
        val configResponse = Response(text = configContent, stderr = "")
        val scriptCommand = "sudo /usr/local/bin/start-k3s-agent.sh '$serverUrl' '$token'"
        val scriptResponse = Response(text = "", stderr = "")
        val successResponse = Response(text = "", stderr = "")

        doNothing().whenever(mockRemoteOps).upload(eq(testHost), any(), any())
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(configCommand), any(), any()))
            .thenReturn(configResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(scriptCommand), any(), any()))
            .thenReturn(scriptResponse)

        // When
        val result = k3sAgentService.start(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(configCommand), any(), any())
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(scriptCommand), any(), any())
    }

    @Test
    fun `start should return failure when config file is missing server URL`() {
        // Given
        val configContent = "token: K10abcdef1234567890::server:1234567890abcdef"
        val successResponse = Response(text = "", stderr = "")

        val configCommand = "cat /etc/rancher/k3s/config.yaml"
        val configResponse = Response(text = configContent, stderr = "")

        doNothing().whenever(mockRemoteOps).upload(eq(testHost), any(), any())
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(configCommand), any(), any()))
            .thenReturn(configResponse)

        // When
        val result = k3sAgentService.start(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Failed to parse k3s config")
    }

    @Test
    fun `start should return failure when config file is missing token`() {
        // Given
        val configContent = "server: https://10.0.1.5:6443"
        val successResponse = Response(text = "", stderr = "")

        val configCommand = "cat /etc/rancher/k3s/config.yaml"
        val configResponse = Response(text = configContent, stderr = "")

        doNothing().whenever(mockRemoteOps).upload(eq(testHost), any(), any())
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(configCommand), any(), any()))
            .thenReturn(configResponse)

        // When
        val result = k3sAgentService.start(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Failed to parse k3s config")
    }

    @Test
    fun `start should return failure when script execution fails`() {
        // Given
        val serverUrl = "https://10.0.1.5:6443"
        val token = "K10abcdef1234567890::server:1234567890abcdef"
        val configContent =
            """
            server: $serverUrl
            token: $token
            """.trimIndent()
        val successResponse = Response(text = "", stderr = "")

        val configCommand = "cat /etc/rancher/k3s/config.yaml"
        val configResponse = Response(text = configContent, stderr = "")
        val scriptCommand = "sudo /usr/local/bin/start-k3s-agent.sh '$serverUrl' '$token'"

        doNothing().whenever(mockRemoteOps).upload(eq(testHost), any(), any())
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(configCommand), any(), any()))
            .thenReturn(configResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(scriptCommand), any(), any()))
            .thenThrow(RuntimeException("Script execution failed"))

        // When
        val result = k3sAgentService.start(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Script execution failed")
    }

    @Test
    fun `start should return failure when SSH operation throws exception`() {
        // Given - upload itself fails
        doNothing().whenever(mockRemoteOps).upload(eq(testHost), any(), any())

        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenThrow(RuntimeException("Connection refused"))

        // When
        val result = k3sAgentService.start(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Connection refused")
    }

    // ========== STOP OPERATION TESTS ==========

    @Test
    fun `stop should execute systemctl stop command successfully`() {
        // Given
        val expectedCommand = "sudo systemctl stop k3s-agent"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = k3sAgentService.stop(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `stop should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl stop k3s-agent"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Connection timeout"))

        // When
        val result = k3sAgentService.stop(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Connection timeout")
    }

    // ========== RESTART OPERATION TESTS ==========

    @Test
    fun `restart should execute systemctl restart command successfully`() {
        // Given
        val expectedCommand = "sudo systemctl restart k3s-agent"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = k3sAgentService.restart(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `restart should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl restart k3s-agent"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Service restart timeout"))

        // When
        val result = k3sAgentService.restart(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Service restart timeout")
    }

    // ========== STATUS CHECK TESTS ==========

    @Test
    fun `isRunning should return true when k3s-agent is active`() {
        // Given
        val expectedCommand = "sudo systemctl is-active k3s-agent"
        val activeResponse = Response(text = "active", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(activeResponse)

        // When
        val result = k3sAgentService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return false when k3s-agent is inactive`() {
        // Given
        val expectedCommand = "sudo systemctl is-active k3s-agent"
        val inactiveResponse = Response(text = "inactive", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(inactiveResponse)

        // When
        val result = k3sAgentService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return false when k3s-agent is in failed state`() {
        // Given
        val expectedCommand = "sudo systemctl is-active k3s-agent"
        val failedResponse = Response(text = "failed", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(failedResponse)

        // When
        val result = k3sAgentService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl is-active k3s-agent"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("SSH connection lost"))

        // When
        val result = k3sAgentService.isRunning(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("SSH connection lost")
    }

    // ========== GET STATUS TESTS ==========

    @Test
    fun `getStatus should return systemctl status output`() {
        // Given
        val expectedCommand = "sudo systemctl status k3s-agent"
        val statusOutput = "● k3s-agent.service - Lightweight Kubernetes Agent\n   Active: active (running)"
        val statusResponse = Response(text = statusOutput, stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(statusResponse)

        // When
        val result = k3sAgentService.getStatus(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(statusOutput)
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `getStatus should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl status k3s-agent"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Unable to connect"))

        // When
        val result = k3sAgentService.getStatus(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Unable to connect")
    }
}
