package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CleanupTest : BaseKoinTest() {
    private val mockClusterStateManager: ClusterStateManager = mock()
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var outputHandler: BufferedOutputHandler

    private val controlHost = ClusterHost("54.1.2.3", "10.0.0.1", "control0", "us-west-2a", "i-control")
    private val dbNode0 = ClusterHost("54.2.0.0", "10.0.2.0", "db0", "us-west-2a", "i-db0")
    private val dbNode1 = ClusterHost("54.2.0.1", "10.0.2.1", "db1", "us-west-2b", "i-db1")
    private val appNode0 = ClusterHost("54.3.0.0", "10.0.3.0", "app0", "us-west-2a", "i-app0")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single<RemoteOperationsService> { mock() }
            },
        )

    @BeforeEach
    fun setup() {
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
        mockRemoteOps = get()
        whenever(
            mockRemoteOps.executeRemotely(
                host = org.mockito.kotlin.any(),
                command = org.mockito.kotlin.any(),
                output = org.mockito.kotlin.any(),
                secret = org.mockito.kotlin.any(),
            ),
        ).thenReturn(Response("", ""))
    }

    private fun stateWith(vararg pairs: Pair<ServerType, List<ClusterHost>>): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "test-bucket",
            initConfig = InitConfig(region = "us-west-2", name = "test-cluster"),
            hosts = mapOf(ServerType.Control to listOf(controlHost)) + pairs.toMap(),
        )

    private fun runCleanup(
        kit: String = "myworkload",
        nodeType: ServerType = ServerType.Cassandra,
    ) {
        val cmd = Cleanup()
        cmd.kit = kit
        cmd.nodeType = nodeType
        cmd.execute()
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    @Test
    fun `workload name with path separator is rejected`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(ServerType.Cassandra to listOf(dbNode0)))
        assertThatThrownBy { runCleanup(kit = "../other") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `workload name with space is rejected`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(ServerType.Cassandra to listOf(dbNode0)))
        assertThatThrownBy { runCleanup(kit = "my workload") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `workload name starting with dash is rejected`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith(ServerType.Cassandra to listOf(dbNode0)))
        assertThatThrownBy { runCleanup(kit = "-bad") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // -------------------------------------------------------------------------
    // Node pool targeting
    // -------------------------------------------------------------------------

    @Test
    fun `Cassandra node-type issues rm command on db nodes`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            stateWith(ServerType.Cassandra to listOf(dbNode0, dbNode1)),
        )
        runCleanup(nodeType = ServerType.Cassandra)
        verify(
            mockRemoteOps,
            times(2),
        ).executeRemotely(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `Stress node-type issues rm command on app nodes`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            stateWith(ServerType.Stress to listOf(appNode0)),
        )
        runCleanup(nodeType = ServerType.Stress)
        verify(
            mockRemoteOps,
            times(1),
        ).executeRemotely(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `no SSH calls made when target node pool is absent`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWith())
        runCleanup()
        verify(
            mockRemoteOps,
            times(0),
        ).executeRemotely(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `one SSH call issued per node`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            stateWith(ServerType.Cassandra to listOf(dbNode0, dbNode1)),
        )
        runCleanup()
        verify(
            mockRemoteOps,
            times(2),
        ).executeRemotely(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `rm command targets kit-scoped data path`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            stateWith(ServerType.Cassandra to listOf(dbNode0)),
        )
        val commandCaptor = argumentCaptor<String>()
        runCleanup(kit = "clickhouse")
        verify(
            mockRemoteOps,
        ).executeRemotely(org.mockito.kotlin.any(), commandCaptor.capture(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        assertThat(commandCaptor.firstValue)
            .isEqualTo("sudo rm -rf ${Constants.K8s.DB_MOUNT_PATH}/clickhouse")
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @Test
    fun `complete event is emitted after all nodes are cleaned`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            stateWith(ServerType.Cassandra to listOf(dbNode0, dbNode1)),
        )
        runCleanup(kit = "presto")
        assertThat(outputHandler.messages.joinToString("\n")).contains("Cleanup complete for 'presto'")
    }

    @Test
    fun `node cleaned event is emitted per node`() {
        whenever(mockClusterStateManager.load()).thenReturn(
            stateWith(ServerType.Cassandra to listOf(dbNode0, dbNode1)),
        )
        runCleanup()
        assertThat(outputHandler.messages.filter { it.contains("Cleaned") }).hasSize(2)
    }
}
