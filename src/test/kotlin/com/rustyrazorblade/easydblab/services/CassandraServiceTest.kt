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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for CassandraService following TDD principles.
 *
 * These tests verify core Cassandra lifecycle operations (start, stop, restart)
 * and status checks using mocked SSH operations.
 */
class CassandraServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var cassandraService: CassandraService

    private val testHost =
        Host(
            public = "54.123.45.67",
            private = "10.0.1.5",
            alias = "db0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mockRemoteOps }
                factory<CassandraService> { DefaultCassandraService(get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockRemoteOps = mock()
        cassandraService = getKoin().get()
    }

    // ========== START OPERATION TESTS ==========

    @Test
    fun `start should execute systemctl start command successfully without waiting`() {
        // Given
        val expectedCommand = "sudo systemctl start cassandra"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = cassandraService.start(testHost, wait = false)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `start should execute systemctl start and wait for up normal when wait is true`() {
        // Given
        val startCommand = "sudo systemctl start cassandra"
        val waitCommand = "sudo wait-for-up-normal"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(startCommand), any(), any()))
            .thenReturn(successResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(waitCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = cassandraService.start(testHost, wait = true)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(startCommand), any(), any())
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(waitCommand), any(), any())
    }

    @Test
    fun `start should wait for up normal by default`() {
        // Given
        val startCommand = "sudo systemctl start cassandra"
        val waitCommand = "sudo wait-for-up-normal"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(startCommand), any(), any()))
            .thenReturn(successResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(waitCommand), any(), any()))
            .thenReturn(successResponse)

        // When - calling without explicit wait parameter (should default to true)
        val result = cassandraService.start(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(startCommand), any(), any())
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(waitCommand), any(), any())
    }

    @Test
    fun `start should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl start cassandra"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Connection refused"))

        // When
        val result = cassandraService.start(testHost, wait = false)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Connection refused")
    }

    @Test
    fun `start should return failure when wait-for-up-normal fails`() {
        // Given
        val startCommand = "sudo systemctl start cassandra"
        val waitCommand = "sudo wait-for-up-normal"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(startCommand), any(), any()))
            .thenReturn(successResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(waitCommand), any(), any()))
            .thenThrow(RuntimeException("Timeout waiting for node"))

        // When
        val result = cassandraService.start(testHost, wait = true)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Timeout waiting for node")
    }

    // ========== STOP OPERATION TESTS ==========

    @Test
    fun `stop should execute systemctl stop command successfully`() {
        // Given
        val expectedCommand = "sudo systemctl stop cassandra"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = cassandraService.stop(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `stop should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl stop cassandra"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Connection timeout"))

        // When
        val result = cassandraService.stop(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Connection timeout")
    }

    // ========== RESTART OPERATION TESTS ==========

    @Test
    fun `restart should execute restart script successfully`() {
        // Given
        val expectedCommand = "/usr/local/bin/restart-cassandra-and-wait"
        val successResponse = Response(text = "Cassandra restarted successfully", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = cassandraService.restart(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `restart should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "/usr/local/bin/restart-cassandra-and-wait"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Timeout waiting for Cassandra"))

        // When
        val result = cassandraService.restart(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Timeout waiting for Cassandra")
    }

    // ========== WAIT FOR UP NORMAL TESTS ==========

    @Test
    fun `waitForUpNormal should execute wait script successfully`() {
        // Given
        val expectedCommand = "sudo wait-for-up-normal"
        val successResponse = Response(text = "Node is up and normal", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = cassandraService.waitForUpNormal(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `waitForUpNormal should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo wait-for-up-normal"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Timeout waiting for node to become UP/NORMAL"))

        // When
        val result = cassandraService.waitForUpNormal(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Timeout waiting for node to become UP/NORMAL")
    }

    // ========== STATUS CHECK TESTS ==========

    @Test
    fun `isRunning should return true when cassandra is active`() {
        // Given
        val expectedCommand = "sudo systemctl is-active cassandra || true"
        val activeResponse = Response(text = "active", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(activeResponse)

        // When
        val result = cassandraService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return false when cassandra is inactive`() {
        // Given
        val expectedCommand = "sudo systemctl is-active cassandra || true"
        val inactiveResponse = Response(text = "inactive", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(inactiveResponse)

        // When
        val result = cassandraService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return false when cassandra is in failed state`() {
        // Given
        val expectedCommand = "sudo systemctl is-active cassandra || true"
        val failedResponse = Response(text = "failed", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(failedResponse)

        // When
        val result = cassandraService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl is-active cassandra || true"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("SSH connection lost"))

        // When
        val result = cassandraService.isRunning(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("SSH connection lost")
    }
}
