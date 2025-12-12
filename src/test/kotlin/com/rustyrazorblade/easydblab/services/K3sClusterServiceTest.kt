package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.OutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path

/**
 * Tests for K3sClusterService.
 */
class K3sClusterServiceTest {
    private lateinit var k3sService: K3sService
    private lateinit var k3sAgentService: K3sAgentService
    private lateinit var outputHandler: OutputHandler
    private lateinit var clusterBackupService: ClusterBackupService
    private lateinit var service: K3sClusterService
    private lateinit var serviceWithBackup: K3sClusterService

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        k3sService = mock()
        k3sAgentService = mock()
        outputHandler = mock()
        clusterBackupService = mock()

        // Service without backup capability (for backward compatibility tests)
        service =
            DefaultK3sClusterService(
                k3sService = k3sService,
                k3sAgentService = k3sAgentService,
                outputHandler = outputHandler,
            )

        // Service with backup capability
        serviceWithBackup =
            DefaultK3sClusterService(
                k3sService = k3sService,
                k3sAgentService = k3sAgentService,
                outputHandler = outputHandler,
                clusterBackupService = clusterBackupService,
            )
    }

    @Nested
    inner class SetupCluster {
        @Test
        fun `should start server and retrieve token successfully`() {
            val config = createConfig()
            setupSuccessfulServerStart()

            val result = service.setupCluster(config)

            assertThat(result.serverStarted).isTrue()
            assertThat(result.nodeToken).isEqualTo("test-token-12345")
            assertThat(result.errors).isEmpty()
        }

        @Test
        fun `should return failure when server start fails`() {
            val config = createConfig()
            whenever(k3sService.start(any())).thenReturn(Result.failure(RuntimeException("Server start failed")))

            val result = service.setupCluster(config)

            assertThat(result.serverStarted).isFalse()
            assertThat(result.nodeToken).isNull()
            assertThat(result.errors).containsKey("K3s server start")
            verify(k3sService, never()).getNodeToken(any())
        }

        @Test
        fun `should return failure when token retrieval fails`() {
            val config = createConfig()
            whenever(k3sService.start(any())).thenReturn(Result.success(Unit))
            whenever(k3sService.getNodeToken(any()))
                .thenReturn(Result.failure(RuntimeException("Token retrieval failed")))

            val result = service.setupCluster(config)

            assertThat(result.serverStarted).isTrue()
            assertThat(result.nodeToken).isNull()
            assertThat(result.errors).containsKey("K3s node token retrieval")
        }

        @Test
        fun `should download kubeconfig successfully`() {
            val config = createConfig()
            setupSuccessfulServerStart()
            whenever(k3sService.downloadAndConfigureKubeconfig(any(), any()))
                .thenReturn(Result.success(Unit))

            val result = service.setupCluster(config)

            assertThat(result.kubeconfigWritten).isTrue()
            verify(k3sService).downloadAndConfigureKubeconfig(any(), eq(config.kubeconfigPath))
        }

        @Test
        fun `should continue even if kubeconfig download fails`() {
            val config = createConfig()
            setupSuccessfulServerStart()
            whenever(k3sService.downloadAndConfigureKubeconfig(any(), any()))
                .thenReturn(Result.failure(RuntimeException("Download failed")))

            val result = service.setupCluster(config)

            assertThat(result.serverStarted).isTrue()
            assertThat(result.kubeconfigWritten).isFalse()
            assertThat(result.errors).containsKey("Kubeconfig download")
        }

        @Test
        fun `should configure and start agents on Cassandra nodes`() {
            val cassandraHost = createClusterHost("db0", "10.0.0.1")
            val config =
                createConfig(
                    workerHosts =
                        mapOf(
                            ServerType.Cassandra to listOf(cassandraHost),
                        ),
                )
            setupSuccessfulServerStart()
            setupSuccessfulAgentSetup()

            val result = service.setupCluster(config)

            assertThat(result.agentResults).hasSize(1)
            assertThat(result.agentResults["db0"]?.success).isTrue()
            verify(k3sAgentService).configure(
                any(),
                eq("https://10.0.1.1:6443"),
                eq("test-token-12345"),
                eq(mapOf("role" to "cassandra", "type" to "db")),
            )
        }

        @Test
        fun `should configure and start agents on Stress nodes`() {
            val stressHost = createClusterHost("stress0", "10.0.0.2")
            val config =
                createConfig(
                    workerHosts =
                        mapOf(
                            ServerType.Stress to listOf(stressHost),
                        ),
                )
            setupSuccessfulServerStart()
            setupSuccessfulAgentSetup()

            val result = service.setupCluster(config)

            assertThat(result.agentResults).hasSize(1)
            assertThat(result.agentResults["stress0"]?.success).isTrue()
            verify(k3sAgentService).configure(
                any(),
                eq("https://10.0.1.1:6443"),
                eq("test-token-12345"),
                eq(mapOf("role" to "stress", "type" to "app")),
            )
        }

        @Test
        fun `should setup multiple agents in parallel`() {
            val cassandraHosts =
                listOf(
                    createClusterHost("db0", "10.0.0.1"),
                    createClusterHost("db1", "10.0.0.2"),
                )
            val stressHosts =
                listOf(
                    createClusterHost("stress0", "10.0.0.3"),
                )
            val config =
                createConfig(
                    workerHosts =
                        mapOf(
                            ServerType.Cassandra to cassandraHosts,
                            ServerType.Stress to stressHosts,
                        ),
                )
            setupSuccessfulServerStart()
            setupSuccessfulAgentSetup()

            val result = service.setupCluster(config)

            assertThat(result.agentResults).hasSize(3)
            assertThat(result.agentResults["db0"]?.success).isTrue()
            assertThat(result.agentResults["db1"]?.success).isTrue()
            assertThat(result.agentResults["stress0"]?.success).isTrue()
        }

        @Test
        fun `should collect agent errors without stopping other agents`() {
            val cassandraHosts =
                listOf(
                    createClusterHost("db0", "10.0.0.1"),
                    createClusterHost("db1", "10.0.0.2"),
                )
            val config =
                createConfig(
                    workerHosts =
                        mapOf(
                            ServerType.Cassandra to cassandraHosts,
                        ),
                )
            setupSuccessfulServerStart()

            // Both agents succeed, but one has an error recorded manually
            // This test verifies that agent failures are collected without stopping other agents
            whenever(k3sAgentService.configure(any(), any(), any(), any())).thenReturn(Result.success(Unit))
            whenever(k3sAgentService.start(any())).thenReturn(Result.failure(RuntimeException("Agent start failed")))

            val result = service.setupCluster(config)

            // Both agents should fail since start always fails
            val failureCount = result.agentResults.values.count { !it.success }
            assertThat(failureCount).isEqualTo(2)
            assertThat(result.agentResults).hasSize(2)
            // Errors should be collected for both
            assertThat(result.errors).hasSize(2)
        }

        @Test
        fun `should filter hosts by host filter`() {
            val cassandraHosts =
                listOf(
                    createClusterHost("db0", "10.0.0.1"),
                    createClusterHost("db1", "10.0.0.2"),
                    createClusterHost("db2", "10.0.0.3"),
                )
            val config =
                createConfig(
                    workerHosts =
                        mapOf(
                            ServerType.Cassandra to cassandraHosts,
                        ),
                    hostFilter = "db0,db2",
                )
            setupSuccessfulServerStart()
            setupSuccessfulAgentSetup()

            val result = service.setupCluster(config)

            // Only db0 and db2 should be configured (filtered out db1)
            assertThat(result.agentResults).hasSize(2)
            assertThat(result.agentResults).containsKeys("db0", "db2")
            assertThat(result.agentResults).doesNotContainKey("db1")
        }

        @Test
        fun `should report overall success only when all operations succeed`() {
            val config =
                createConfig(
                    workerHosts =
                        mapOf(
                            ServerType.Cassandra to listOf(createClusterHost("db0", "10.0.0.1")),
                        ),
                )
            setupSuccessfulServerStart()
            setupSuccessfulAgentSetup()

            val result = service.setupCluster(config)

            assertThat(result.isSuccessful).isTrue()
        }

        @Test
        fun `should report failure when agent setup fails`() {
            val config =
                createConfig(
                    workerHosts =
                        mapOf(
                            ServerType.Cassandra to listOf(createClusterHost("db0", "10.0.0.1")),
                        ),
                )
            setupSuccessfulServerStart()
            whenever(k3sAgentService.configure(any(), any(), any(), any()))
                .thenReturn(Result.failure(RuntimeException("Agent failed")))

            val result = service.setupCluster(config)

            assertThat(result.isSuccessful).isFalse()
        }

        @Test
        fun `should backup kubeconfig to S3 when ClusterState is provided`() {
            val clusterState = createClusterState()
            val config = createConfig(clusterState = clusterState)
            setupSuccessfulServerStart()
            whenever(clusterBackupService.backupKubeconfig(any(), eq(clusterState)))
                .thenReturn(Result.success(Unit))

            val result = serviceWithBackup.setupCluster(config)

            assertThat(result.kubeconfigBackedUp).isTrue()
            verify(clusterBackupService).backupKubeconfig(eq(config.kubeconfigPath), eq(clusterState))
        }

        @Test
        fun `should not attempt backup when ClusterState is not provided`() {
            val config = createConfig() // No clusterState
            setupSuccessfulServerStart()

            val result = serviceWithBackup.setupCluster(config)

            assertThat(result.kubeconfigBackedUp).isFalse()
            verify(clusterBackupService, never()).backupKubeconfig(any(), any())
        }

        @Test
        fun `should not attempt backup when S3 bucket is not configured`() {
            val clusterState =
                ClusterState(
                    name = "test",
                    versions = mutableMapOf(),
                    s3Bucket = null,
                )
            val config = createConfig(clusterState = clusterState)
            setupSuccessfulServerStart()

            val result = serviceWithBackup.setupCluster(config)

            assertThat(result.kubeconfigBackedUp).isFalse()
            verify(clusterBackupService, never()).backupKubeconfig(any(), any())
        }

        @Test
        fun `should handle backup failure gracefully`() {
            val clusterState = createClusterState()
            val config = createConfig(clusterState = clusterState)
            setupSuccessfulServerStart()
            whenever(clusterBackupService.backupKubeconfig(any(), any()))
                .thenReturn(Result.failure(RuntimeException("S3 upload failed")))

            val result = serviceWithBackup.setupCluster(config)

            // Setup should still succeed even if backup fails
            assertThat(result.isSuccessful).isTrue()
            assertThat(result.kubeconfigBackedUp).isFalse()
            // Backup failure should NOT be added to errors (it's non-critical)
            assertThat(result.errors).isEmpty()
        }

        @Test
        fun `should still succeed when backup service is not available`() {
            val clusterState = createClusterState()
            val config = createConfig(clusterState = clusterState)
            setupSuccessfulServerStart()

            // Use service without backup capability
            val result = service.setupCluster(config)

            assertThat(result.isSuccessful).isTrue()
            assertThat(result.kubeconfigBackedUp).isFalse()
        }
    }

    @Nested
    inner class GetNodeLabels {
        @Test
        fun `should return cassandra labels for Cassandra type`() {
            val labels = service.getNodeLabels(ServerType.Cassandra)

            assertThat(labels).isEqualTo(mapOf("role" to "cassandra", "type" to "db"))
        }

        @Test
        fun `should return stress labels for Stress type`() {
            val labels = service.getNodeLabels(ServerType.Stress)

            assertThat(labels).isEqualTo(mapOf("role" to "stress", "type" to "app"))
        }

        @Test
        fun `should return empty labels for Control type`() {
            val labels = service.getNodeLabels(ServerType.Control)

            assertThat(labels).isEmpty()
        }
    }

    // Helper methods

    private fun setupSuccessfulServerStart() {
        whenever(k3sService.start(any())).thenReturn(Result.success(Unit))
        whenever(k3sService.getNodeToken(any())).thenReturn(Result.success("test-token-12345"))
        whenever(k3sService.downloadAndConfigureKubeconfig(any(), any())).thenReturn(Result.success(Unit))
    }

    private fun setupSuccessfulAgentSetup() {
        whenever(k3sAgentService.configure(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(k3sAgentService.start(any())).thenReturn(Result.success(Unit))
    }

    private fun createConfig(
        workerHosts: Map<ServerType, List<ClusterHost>> = emptyMap(),
        hostFilter: String = "",
        clusterState: ClusterState? = null,
    ): K3sClusterConfig =
        K3sClusterConfig(
            controlHost = createClusterHost("control0", "10.0.1.1"),
            workerHosts = workerHosts,
            kubeconfigPath = tempDir.resolve("kubeconfig"),
            hostFilter = hostFilter,
            clusterState = clusterState,
        )

    private fun createClusterHost(
        alias: String,
        privateIp: String,
    ): ClusterHost =
        ClusterHost(
            publicIp = "1.1.1.1",
            privateIp = privateIp,
            alias = alias,
            availabilityZone = "us-west-2a",
            instanceId = "i-$alias",
        )

    private fun createClusterState(): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "easy-db-lab-test-abc12345",
        )
}
