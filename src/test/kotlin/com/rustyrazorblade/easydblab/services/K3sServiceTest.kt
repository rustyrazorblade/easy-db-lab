package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import io.fabric8.kubernetes.client.internal.KubeConfigUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test suite for K3sService following TDD principles.
 *
 * These tests verify K3s lifecycle operations (start, stop, restart)
 * and status checks using mocked SSH operations.
 */
class K3sServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var k3sService: K3sService

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
                single<ClusterStateManager> { mock() }
                factory<K3sService> { DefaultK3sService(get(), get(), get()) }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockRemoteOps = mock()
        k3sService = getKoin().get()
    }

    // ========== START OPERATION TESTS ==========

    @Test
    fun `start should execute k3s server startup script successfully`() {
        // Given
        val scriptCommand = "sudo /usr/local/bin/start-k3s-server.sh"
        val successResponse = Response(text = "", stderr = "")

        doNothing().whenever(mockRemoteOps).upload(eq(testHost), any(), any())
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = k3sService.start(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(scriptCommand), any(), any())
    }

    @Test
    fun `start should return failure when script execution fails`() {
        // Given
        val scriptCommand = "sudo /usr/local/bin/start-k3s-server.sh"
        val successResponse = Response(text = "", stderr = "")

        doNothing().whenever(mockRemoteOps).upload(eq(testHost), any(), any())
        // chmod succeeds but script execution fails
        whenever(mockRemoteOps.executeRemotely(eq(testHost), any(), any(), any()))
            .thenReturn(successResponse)
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(scriptCommand), any(), any()))
            .thenThrow(RuntimeException("Script execution failed"))

        // When
        val result = k3sService.start(testHost)

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
        val result = k3sService.start(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Connection refused")
    }

    // ========== STOP OPERATION TESTS ==========

    @Test
    fun `stop should execute systemctl stop command successfully`() {
        // Given
        val expectedCommand = "sudo systemctl stop k3s"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = k3sService.stop(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `stop should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl stop k3s"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Connection timeout"))

        // When
        val result = k3sService.stop(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Connection timeout")
    }

    // ========== RESTART OPERATION TESTS ==========

    @Test
    fun `restart should execute systemctl restart command successfully`() {
        // Given
        val expectedCommand = "sudo systemctl restart k3s"
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(successResponse)

        // When
        val result = k3sService.restart(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `restart should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl restart k3s"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Service restart timeout"))

        // When
        val result = k3sService.restart(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Service restart timeout")
    }

    // ========== STATUS CHECK TESTS ==========

    @Test
    fun `isRunning should return true when k3s is active`() {
        // Given
        val expectedCommand = "sudo systemctl is-active k3s"
        val activeResponse = Response(text = "active", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(activeResponse)

        // When
        val result = k3sService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return false when k3s is inactive`() {
        // Given
        val expectedCommand = "sudo systemctl is-active k3s"
        val inactiveResponse = Response(text = "inactive", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(inactiveResponse)

        // When
        val result = k3sService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return false when k3s is in failed state`() {
        // Given
        val expectedCommand = "sudo systemctl is-active k3s"
        val failedResponse = Response(text = "failed", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(failedResponse)

        // When
        val result = k3sService.isRunning(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `isRunning should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl is-active k3s"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("SSH connection lost"))

        // When
        val result = k3sService.isRunning(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("SSH connection lost")
    }

    // ========== GET STATUS TESTS ==========

    @Test
    fun `getStatus should return systemctl status output`() {
        // Given
        val expectedCommand = "sudo systemctl status k3s"
        val statusOutput = "● k3s.service - Lightweight Kubernetes\n   Active: active (running)"
        val statusResponse = Response(text = statusOutput, stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(statusResponse)

        // When
        val result = k3sService.getStatus(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(statusOutput)
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `getStatus should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo systemctl status k3s"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Unable to connect"))

        // When
        val result = k3sService.getStatus(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Unable to connect")
    }

    // ========== NODE TOKEN RETRIEVAL TESTS ==========

    @Test
    fun `getNodeToken should retrieve token successfully`() {
        // Given
        val expectedCommand = "sudo cat /var/lib/rancher/k3s/server/node-token"
        val tokenValue = "K10abcdef1234567890::server:1234567890abcdef"
        val tokenResponse = Response(text = "$tokenValue\n", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(tokenResponse)

        // When
        val result = k3sService.getNodeToken(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(tokenValue)
        verify(mockRemoteOps).executeRemotely(eq(testHost), eq(expectedCommand), any(), any())
    }

    @Test
    fun `getNodeToken should trim whitespace from token`() {
        // Given
        val expectedCommand = "sudo cat /var/lib/rancher/k3s/server/node-token"
        val tokenValue = "K10abcdef1234567890::server:1234567890abcdef"
        val tokenResponse = Response(text = "  $tokenValue  \n", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(tokenResponse)

        // When
        val result = k3sService.getNodeToken(testHost)

        // Then
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(tokenValue)
    }

    @Test
    fun `getNodeToken should return failure when token is empty`() {
        // Given
        val expectedCommand = "sudo cat /var/lib/rancher/k3s/server/node-token"
        val emptyResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(emptyResponse)

        // When
        val result = k3sService.getNodeToken(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("K3s node token is empty")
    }

    @Test
    fun `getNodeToken should return failure when token is whitespace only`() {
        // Given
        val expectedCommand = "sudo cat /var/lib/rancher/k3s/server/node-token"
        val whitespaceResponse = Response(text = "   \n  ", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenReturn(whitespaceResponse)

        // When
        val result = k3sService.getNodeToken(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("K3s node token is empty")
    }

    @Test
    fun `getNodeToken should return failure when SSH operation throws exception`() {
        // Given
        val expectedCommand = "sudo cat /var/lib/rancher/k3s/server/node-token"
        whenever(mockRemoteOps.executeRemotely(eq(testHost), eq(expectedCommand), any(), any()))
            .thenThrow(RuntimeException("Permission denied"))

        // When
        val result = k3sService.getNodeToken(testHost)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("Permission denied")
    }

    // ========== KUBECONFIG DOWNLOAD & REWRITE TESTS ==========

    /**
     * A valid k3s kubeconfig with the server pointing at loopback, exactly as
     * downloaded from /etc/rancher/k3s/k3s.yaml on the control node. The download
     * mock writes this to the temp file the service hands it, and the service is
     * expected to rewrite only the server URL while preserving everything else.
     */
    private val loopbackKubeconfigYaml =
        """
        apiVersion: v1
        kind: Config
        clusters:
        - cluster:
            certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0t
            server: https://127.0.0.1:6443
          name: default
        contexts:
        - context:
            cluster: default
            user: default
          name: default
        current-context: default
        users:
        - name: default
          user:
            client-certificate-data: LS0tLS1DRVJULS0tLS0=
            client-key-data: LS0tLS1LRVktLS0tLQ==
        """.trimIndent()

    private val kubeconfigWithoutClustersYaml =
        """
        apiVersion: v1
        kind: Config
        clusters: []
        contexts: []
        current-context: ""
        users: []
        """.trimIndent()

    @Test
    fun `downloadAndConfigureKubeconfig rewrites server URL to control node private IP`(
        @TempDir tempDir: Path,
    ) {
        // Given - download writes a loopback kubeconfig to the temp path the service creates.
        // We only supply the INPUT; every assertion below is on the service's real transform.
        val host =
            Host(
                public = "54.123.45.67",
                private = "10.0.1.42",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )
        doAnswer { invocation ->
            val downloadTarget = invocation.getArgument<Path>(2)
            Files.writeString(downloadTarget, loopbackKubeconfigYaml)
            null
        }.whenever(mockRemoteOps)
            .download(eq(host), eq(Constants.K3s.REMOTE_KUBECONFIG), any())

        val localPath = tempDir.resolve("kubeconfig")

        // When
        val result = k3sService.downloadAndConfigureKubeconfig(host, localPath)

        // Then - re-parse the ACTUAL written file with real fabric8 and inspect the transform.
        assertThat(result.isSuccess).isTrue()

        val expectedServerUrl = Constants.K3s.DEFAULT_SERVER_URL.replace("127.0.0.1", host.private)
        val writtenConfig = KubeConfigUtils.parseConfig(localPath.toFile())
        val writtenCluster = writtenConfig.clusters.first()

        assertThat(writtenCluster.cluster.server).isEqualTo(expectedServerUrl)
        // Sanity check the expected value really is derived from the loopback default.
        assertThat(expectedServerUrl).isEqualTo("https://10.0.1.42:6443")

        // Round-trip proof: unrelated fields survived the parse -> mutate -> serialize cycle.
        assertThat(writtenCluster.name).isEqualTo("default")
        assertThat(writtenCluster.cluster.certificateAuthorityData)
            .isEqualTo("LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0t")
        assertThat(writtenConfig.currentContext).isEqualTo("default")
        assertThat(writtenConfig.users.first().name).isEqualTo("default")
    }

    @Test
    fun `downloadAndConfigureKubeconfig fails cleanly when kubeconfig has no clusters`(
        @TempDir tempDir: Path,
    ) {
        // Given - download writes a kubeconfig with an empty cluster list.
        val host =
            Host(
                public = "54.123.45.67",
                private = "10.0.1.42",
                alias = "control0",
                availabilityZone = "us-west-2a",
            )
        doAnswer { invocation ->
            val downloadTarget = invocation.getArgument<Path>(2)
            Files.writeString(downloadTarget, kubeconfigWithoutClustersYaml)
            null
        }.whenever(mockRemoteOps)
            .download(eq(host), eq(Constants.K3s.REMOTE_KUBECONFIG), any())

        val localPath = tempDir.resolve("kubeconfig")

        // When
        val result = k3sService.downloadAndConfigureKubeconfig(host, localPath)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull())
            .hasMessageContaining("contains no clusters")
            .hasMessageContaining(host.alias)
    }
}
