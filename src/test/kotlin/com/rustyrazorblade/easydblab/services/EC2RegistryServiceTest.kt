package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * Test suite for EC2RegistryService.
 *
 * These tests verify TLS certificate generation, S3 upload, and containerd
 * configuration operations using mocked SSH operations.
 */
class EC2RegistryServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var registryService: RegistryService

    private val controlHost =
        Host(
            public = "54.123.45.67",
            private = "10.0.1.100",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    private val workerHost =
        Host(
            public = "54.123.45.68",
            private = "10.0.1.5",
            alias = "db0",
            availabilityZone = "us-west-2a",
        )

    private val testBucket = "easy-db-lab-test-abc12345"

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mockRemoteOps }
                factory<RegistryService> { EC2RegistryService(get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockRemoteOps = mock()
        registryService = getKoin().get()
    }

    // ========== CERTIFICATE GENERATION TESTS ==========

    @Test
    fun `generateAndUploadCert should upload script to remote host`() {
        // Given
        val successResponse = Response(text = "", stderr = "")
        org.mockito.kotlin
            .whenever(mockRemoteOps.executeRemotely(eq(controlHost), any<String>(), any(), any()))
            .thenReturn(successResponse)

        // When
        registryService.generateAndUploadCert(controlHost, testBucket)

        // Then - verify script was installed at its path
        verify(mockRemoteOps).executeRemotely(
            eq(controlHost),
            argThat { command: String -> command.contains(" /tmp/generate_registry_cert.sh || ") && command.contains("sudo chmod 0755 ") },
            any(),
            any(),
        )
    }

    @Test
    fun `generateAndUploadCert should execute script with correct arguments`() {
        // Given
        val successResponse = Response(text = "", stderr = "")
        org.mockito.kotlin
            .whenever(mockRemoteOps.executeRemotely(eq(controlHost), any<String>(), any(), any()))
            .thenReturn(successResponse)

        // When
        registryService.generateAndUploadCert(controlHost, testBucket)

        // Then - verify script execution with correct arguments
        verify(mockRemoteOps).executeRemotely(
            eq(controlHost),
            argThat { command: String ->
                command.contains("generate_registry_cert.sh") &&
                    command.contains("'${controlHost.private}'") &&
                    command.contains("'${Constants.Registry.CERT_DIR}'") &&
                    command.contains("'$testBucket'") &&
                    command.contains("'${Constants.Registry.S3_CERT_PATH}'")
            },
            any(),
            any(),
        )
    }

    @Test
    fun `generateAndUploadCert should propagate SSH exceptions`() {
        // Given
        org.mockito.kotlin
            .whenever(mockRemoteOps.upload(eq(controlHost), any(), any()))
            .thenThrow(RuntimeException("SSH connection refused"))

        // When/Then
        val exception =
            assertThrows<RuntimeException> {
                registryService.generateAndUploadCert(controlHost, testBucket)
            }
        assertThat(exception.message).contains("SSH connection refused")
    }

    // ========== CONTAINERD CONFIGURATION TESTS ==========

    @Test
    fun `configureTlsOnNode should upload script to remote host`() {
        // Given
        val successResponse = Response(text = "", stderr = "")
        org.mockito.kotlin
            .whenever(mockRemoteOps.executeRemotely(eq(workerHost), any<String>(), any(), any()))
            .thenReturn(successResponse)

        val registryHost = controlHost.private

        // When
        registryService.configureTlsOnNode(workerHost, registryHost, testBucket)

        // Then - verify script was installed at its path
        verify(mockRemoteOps).executeRemotely(
            eq(workerHost),
            argThat { command: String -> command.contains(" /tmp/configure_registry_tls.sh || ") && command.contains("sudo chmod 0755 ") },
            any(),
            any(),
        )
    }

    @Test
    fun `configureTlsOnNode should execute script with correct arguments`() {
        // Given
        val successResponse = Response(text = "", stderr = "")
        org.mockito.kotlin
            .whenever(mockRemoteOps.executeRemotely(eq(workerHost), any<String>(), any(), any()))
            .thenReturn(successResponse)

        val registryHost = controlHost.private

        // When
        registryService.configureTlsOnNode(workerHost, registryHost, testBucket)

        // Then - verify script execution with correct arguments
        verify(mockRemoteOps).executeRemotely(
            eq(workerHost),
            argThat { command: String ->
                command.contains("configure_registry_tls.sh") &&
                    command.contains("'$registryHost'") &&
                    command.contains("'${Constants.Registry.PORT}'") &&
                    command.contains("'$testBucket'") &&
                    command.contains("'${Constants.Registry.S3_CERT_PATH}'")
            },
            any(),
            any(),
        )
    }

    @Test
    fun `configureTlsOnNode should propagate SSH exceptions`() {
        // Given
        org.mockito.kotlin
            .whenever(mockRemoteOps.upload(eq(workerHost), any(), any()))
            .thenThrow(RuntimeException("S3 access denied"))

        val registryHost = controlHost.private

        // When/Then
        val exception =
            assertThrows<RuntimeException> {
                registryService.configureTlsOnNode(workerHost, registryHost, testBucket)
            }
        assertThat(exception.message).contains("S3 access denied")
    }

    // ========== SCRIPT CONTENT TESTS ==========

    @Test
    fun `generate_registry_cert script should exist in resources`() {
        val script =
            this::class.java.getResourceAsStream(
                "/com/rustyrazorblade/easydblab/commands/generate_registry_cert.sh",
            )
        assertThat(script).isNotNull
        val content = script!!.bufferedReader().readText()
        assertThat(content).contains("openssl req")
        assertThat(content).contains("aws s3 cp")
        assertThat(content).contains("chmod 600")
    }

    @Test
    fun `configure_registry_tls script should exist in resources`() {
        val script =
            this::class.java.getResourceAsStream(
                "/com/rustyrazorblade/easydblab/commands/configure_registry_tls.sh",
            )
        assertThat(script).isNotNull
        val content = script!!.bufferedReader().readText()
        assertThat(content).contains("aws s3 cp")
        assertThat(content).contains("hosts.toml")
        assertThat(content).contains("systemctl restart containerd")
    }
}
