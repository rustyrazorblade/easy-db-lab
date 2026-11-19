package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path

/**
 * Tests for K3sClusterService.
 */
class K3sClusterServiceTest {
    private lateinit var k3sService: K3sService
    private lateinit var k3sAgentService: K3sAgentService
    private lateinit var clusterBackupService: ClusterBackupService
    private lateinit var service: K3sClusterService
    private lateinit var serviceWithBackup: K3sClusterService

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        k3sService = mock()
        k3sAgentService = mock()
        clusterBackupService = mock()

        // Service without backup capability (for backward compatibility tests)
        service =
            DefaultK3sClusterService(
                k3sService = k3sService,
                k3sAgentService = k3sAgentService,
            )

        // Service with backup capability
        serviceWithBackup =
            DefaultK3sClusterService(
                k3sService = k3sService,
                k3sAgentService = k3sAgentService,
                clusterBackupService = clusterBackupService,
            )
    }

    /** The control pipeline's server step: server, token and kubeconfig, each a failure of the step. */
    @Nested
    inner class StartServer {
        private val control = createClusterHost("control0", "10.0.1.1")

        @Test
        fun `a started server gives its URL on the private IP and the node token, with the kubeconfig written`() {
            setupSuccessfulServerStart()

            val server = service.startServer(control, tempDir.resolve("kubeconfig"), clusterState = null, useCustomCni = true)

            assertThat(server).isEqualTo(K3sServer("https://10.0.1.1:6443", "test-token-12345"))
            verify(k3sService).startServer(any(), eq(true))
            verify(k3sService).downloadAndConfigureKubeconfig(any(), eq(tempDir.resolve("kubeconfig")))
        }

        /** Each of the three server calls failing fails the step, naming the part that failed. */
        @ParameterizedTest
        @CsvSource("server, K3s server start", "token, K3s node token", "kubeconfig, Kubeconfig download")
        fun `a failure of any part fails the step and names that part`(
            failing: String,
            named: String,
        ) {
            setupSuccessfulServerStart()
            val boom = Result.failure<Nothing>(RuntimeException("boom"))
            when (failing) {
                "server" -> whenever(k3sService.startServer(any(), any())).thenReturn(boom)
                "token" -> whenever(k3sService.getNodeToken(any())).thenReturn(boom)
                else -> whenever(k3sService.downloadAndConfigureKubeconfig(any(), any())).thenReturn(boom)
            }

            assertThatThrownBy { service.startServer(control, tempDir.resolve("kubeconfig"), null, useCustomCni = false) }
                .hasMessageContaining(named)
                .hasMessageContaining("boom")
        }

        /** The kubeconfig is backed up only with a backup service and a state that names a bucket. */
        @ParameterizedTest
        @CsvSource("true, bucket, true", "true, none, false", "true, nostate, false", "false, bucket, false")
        fun `the kubeconfig is backed up only when there is a service and a bucket`(
            withService: Boolean,
            state: String,
            backedUp: Boolean,
        ) {
            setupSuccessfulServerStart()
            whenever(clusterBackupService.backupKubeconfig(any(), any())).thenReturn(Result.success(Unit))
            val clusterState =
                when (state) {
                    "bucket" -> createClusterState()
                    "none" -> ClusterState(name = "test-cluster", s3Bucket = null)
                    else -> null
                }
            val target = if (withService) serviceWithBackup else service

            target.startServer(control, tempDir.resolve("kubeconfig"), clusterState, useCustomCni = false)

            verify(clusterBackupService, if (backedUp) times(1) else never())
                .backupKubeconfig(eq(tempDir.resolve("kubeconfig")), any())
        }

        @Test
        fun `a failed kubeconfig backup does not fail the step`() {
            setupSuccessfulServerStart()
            whenever(clusterBackupService.backupKubeconfig(any(), any())).thenReturn(Result.failure(RuntimeException("S3")))

            val server = serviceWithBackup.startServer(control, tempDir.resolve("kubeconfig"), createClusterState(), false)

            assertThat(server.token).isEqualTo("test-token-12345")
            verify(clusterBackupService).backupKubeconfig(any(), any())
        }
    }

    /** One node's join step: configure with its type's labels, then a non-blocking start. */
    @Nested
    inner class JoinAgent {
        private val server = K3sServer("https://10.0.1.1:6443", "tok")

        @ParameterizedTest
        @EnumSource(AgentStartMode::class)
        fun `a join configures the agent with the node's labels and starts it in the given mode`(mode: AgentStartMode) {
            setupSuccessfulAgentSetup()
            val host = createClusterHost("app3", "10.0.2.3")

            service.joinAgent(host, ServerType.Stress, server, mode)

            verify(k3sAgentService).configure(
                any(),
                eq("https://10.0.1.1:6443"),
                eq("tok"),
                eq(mapOf(Constants.NODE_TYPE_LABEL to "app", Constants.NODE_ORDINAL_LABEL to "3")),
            )
            verify(k3sAgentService).start(any(), eq(mode))
        }

        @Test
        fun `a failed configure fails the join and never starts the agent`() {
            whenever(k3sAgentService.configure(any(), any(), any(), any())).thenReturn(Result.failure(RuntimeException("disk")))

            assertThatThrownBy {
                service.joinAgent(
                    createClusterHost("db1", "10.0.1.11"),
                    ServerType.Cassandra,
                    server,
                    AgentStartMode.START,
                )
            }.hasMessageContaining("configure")
                .hasMessageContaining("disk")
            verify(k3sAgentService, never()).start(any(), any())
        }

        @Test
        fun `a failed start fails the join`() {
            setupSuccessfulAgentSetup()
            whenever(k3sAgentService.start(any(), any())).thenReturn(Result.failure(RuntimeException("ssh")))

            assertThatThrownBy {
                service.joinAgent(
                    createClusterHost("db1", "10.0.1.11"),
                    ServerType.Cassandra,
                    server,
                    AgentStartMode.RESTART,
                )
            }.hasMessageContaining("start")
                .hasMessageContaining("ssh")
        }
    }

    @Nested
    inner class GetNodeLabels {
        @Test
        fun `should return cassandra labels with node-ordinal for Cassandra type`() {
            val host = createClusterHost("db0", "10.0.0.1")
            val labels = service.getNodeLabels(ServerType.Cassandra, host)

            assertThat(labels).isEqualTo(mapOf(Constants.NODE_TYPE_LABEL to "db", Constants.NODE_ORDINAL_LABEL to "0"))
        }

        @Test
        fun `should parse ordinal from different aliases`() {
            val host1 = createClusterHost("db1", "10.0.0.1")
            val host5 = createClusterHost("db5", "10.0.0.2")
            val host99 = createClusterHost("db99", "10.0.0.3")

            assertThat(service.getNodeLabels(ServerType.Cassandra, host1))
                .isEqualTo(mapOf(Constants.NODE_TYPE_LABEL to "db", Constants.NODE_ORDINAL_LABEL to "1"))
            assertThat(service.getNodeLabels(ServerType.Cassandra, host5))
                .isEqualTo(mapOf(Constants.NODE_TYPE_LABEL to "db", Constants.NODE_ORDINAL_LABEL to "5"))
            assertThat(service.getNodeLabels(ServerType.Cassandra, host99))
                .isEqualTo(mapOf(Constants.NODE_TYPE_LABEL to "db", Constants.NODE_ORDINAL_LABEL to "99"))
        }

        @Test
        fun `should default to ordinal 0 for invalid alias format`() {
            val host = createClusterHost("invalid", "10.0.0.1")
            val labels = service.getNodeLabels(ServerType.Cassandra, host)

            assertThat(labels).isEqualTo(mapOf(Constants.NODE_TYPE_LABEL to "db", Constants.NODE_ORDINAL_LABEL to "0"))
        }

        @Test
        fun `should return app labels with the ordinal from the alias for Stress type`() {
            val host = createClusterHost("app3", "10.0.0.2")
            val labels = service.getNodeLabels(ServerType.Stress, host)

            assertThat(labels).isEqualTo(mapOf(Constants.NODE_TYPE_LABEL to "app", Constants.NODE_ORDINAL_LABEL to "3"))
        }

        @Test
        fun `should return empty labels for Control type`() {
            val host = createClusterHost("control0", "10.0.1.1")
            val labels = service.getNodeLabels(ServerType.Control, host)

            assertThat(labels).isEmpty()
        }
    }

    // Helper methods

    private fun setupSuccessfulServerStart() {
        whenever(k3sService.startServer(any(), any())).thenReturn(Result.success(Unit))
        whenever(k3sService.getNodeToken(any())).thenReturn(Result.success("test-token-12345"))
        whenever(k3sService.downloadAndConfigureKubeconfig(any(), any())).thenReturn(Result.success(Unit))
    }

    private fun setupSuccessfulAgentSetup() {
        whenever(k3sAgentService.configure(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(k3sAgentService.start(any(), any())).thenReturn(Result.success(Unit))
    }

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
            s3Bucket = "easy-db-lab-test-abc12345",
        )
}
