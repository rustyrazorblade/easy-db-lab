package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for AbstractSystemDServiceManager following TDD principles.
 *
 * These tests verify the common systemd service management operations
 * that are shared across all SystemD-based services.
 */
class SystemDServiceManagerTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var testService: TestSystemDService

    private val testHost =
        Host(
            public = "54.123.45.67",
            private = "10.0.1.5",
            alias = "test0",
            availabilityZone = "us-west-2a",
        )

    /**
     * Concrete implementation of AbstractSystemDServiceManager for testing.
     * Uses "test-service" as the systemd service name.
     */
    private class TestSystemDService(
        remoteOps: RemoteOperationsService,
        eventBus: com.rustyrazorblade.easydblab.events.EventBus,
    ) : AbstractSystemDServiceManager("test-service", remoteOps, eventBus) {
        override val log: KLogger = KotlinLogging.logger {}
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mockRemoteOps }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockRemoteOps = mock()
        testService =
            TestSystemDService(
                mockRemoteOps,
                com.rustyrazorblade.easydblab.events
                    .EventBus(),
            )
    }

    // ========== START OPERATION TESTS ==========

    @Test
    fun `start should execute systemctl start command successfully`() {
        // Given
        val expectedCommand = "sudo systemctl start test-service"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = testService.start(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `start should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl start test-service"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Connection refused"))

        // When
        val result = testService.start(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Connection refused")
    }

    // ========== STOP OPERATION TESTS ==========

    @Test
    fun `stop should execute systemctl stop command successfully`() {
        // Given
        val expectedCommand = "sudo systemctl stop test-service"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = testService.stop(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `stop should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl stop test-service"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Connection timeout"))

        // When
        val result = testService.stop(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Connection timeout")
    }

    // ========== RESTART OPERATION TESTS ==========

    @Test
    fun `restart should execute systemctl restart command successfully`() {
        // Given
        val expectedCommand = "sudo systemctl restart test-service"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = testService.restart(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `restart should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl restart test-service"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Timeout restarting service"))

        // When
        val result = testService.restart(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Timeout restarting service")
    }

    // ========== IS RUNNING TESTS ==========

    @Test
    fun `isRunning should return true when service is active`() {
        // Given
        val expectedCommand = "sudo systemctl is-active test-service"
        val activeResponse = Response(text = "active", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(activeResponse)

        // When
        val result = testService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return false when service is inactive`() {
        // Given
        val expectedCommand = "sudo systemctl is-active test-service"
        val inactiveResponse = Response(text = "inactive", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(inactiveResponse)

        // When
        val result = testService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return false when service is in failed state`() {
        // Given
        val expectedCommand = "sudo systemctl is-active test-service"
        val failedResponse = Response(text = "failed", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(failedResponse)

        // When
        val result = testService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should handle active status with whitespace`() {
        // Given
        val expectedCommand = "sudo systemctl is-active test-service"
        val activeResponse = Response(text = "active\n", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(activeResponse)

        // When
        val result = testService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
    }

    @Test
    fun `isRunning should be case insensitive for active status`() {
        // Given
        val expectedCommand = "sudo systemctl is-active test-service"
        val activeResponse = Response(text = "ACTIVE", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(activeResponse)

        // When
        val result = testService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
    }

    @Test
    fun `isRunning should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl is-active test-service"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("SSH connection lost"))

        // When
        val result = testService.isRunning(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("SSH connection lost")
    }

    // ========== GET STATUS TESTS ==========

    @Test
    fun `getStatus should return status output successfully`() {
        // Given
        val expectedCommand = "sudo systemctl status test-service"
        val statusOutput =
            """
            test-service.service - Test Service
               Loaded: loaded (/etc/systemd/system/test-service.service; enabled)
               Active: active (running) since Mon 2024-01-15 10:00:00 UTC; 2h ago
            """.trimIndent()
        val successResponse = Response(text = statusOutput, stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = testService.getStatus(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(statusOutput)
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `getStatus should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl status test-service"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Failed to get status"))

        // When
        val result = testService.getStatus(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Failed to get status")
    }

    // ========== METHOD OVERRIDE TESTS ==========

    @Test
    fun `restart should be overridable for custom implementations`() {
        // Given a service with custom restart logic
        val customService =
            object : AbstractSystemDServiceManager(
                "custom-service",
                mockRemoteOps,
                com.rustyrazorblade.easydblab.events
                    .EventBus(),
            ) {
                override val log: KLogger = KotlinLogging.logger {}

                override fun restart(host: Host): Result<Unit> =
                    runCatching {
                        remoteOps.executeRemotely(host, "/custom/restart-script")
                    }
            }

        val expectedCommand = "/custom/restart-script"
        val successResponse = Response(text = "Custom restart successful", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = customService.restart(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }
}
