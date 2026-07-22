package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.SidecarService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class StartTest : BaseKoinTest() {
    private lateinit var mockCassandraService: CassandraService
    private lateinit var mockSidecarService: SidecarService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var hostOperationsService: HostOperationsService
    private lateinit var mockKitHookExecutor: KitHookExecutor
    private lateinit var outputHandler: BufferedOutputHandler

    private val testCassandraHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.1",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-db0",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf("db0" to "5.0"),
            initConfig = InitConfig(region = "us-west-2"),
            hosts =
                mapOf(
                    ServerType.Cassandra to listOf(testCassandraHost),
                    ServerType.Control to
                        listOf(
                            ClusterHost(
                                publicIp = "54.1.2.5",
                                privateIp = "10.0.1.3",
                                alias = "control0",
                                availabilityZone = "us-west-2a",
                                instanceId = "i-control0",
                            ),
                        ),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<CassandraService> { mockCassandraService }
                single<SidecarService> { mockSidecarService }
                single<ClusterStateManager> { mockClusterStateManager }
                single { hostOperationsService }
                single<KitHookExecutor> { mockKitHookExecutor }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockCassandraService = mock()
        mockSidecarService = mock()
        mockClusterStateManager = mock()
        mockKitHookExecutor = mock()
        hostOperationsService = HostOperationsService(mockClusterStateManager)
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(mockCassandraService.start(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockSidecarService.deploy(any(), any())).thenReturn(Result.success(Unit))
    }

    @Test
    fun `execute starts cassandra and sidecar on all nodes`() {
        val command = Start()
        command.execute()

        verify(mockCassandraService).start(any(), eq(true))
        verify(mockSidecarService).deploy(any(), any())
    }

    @Test
    fun `execute outputs starting message`() {
        val command = Start()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Starting")
    }

    @Test
    fun `execute does not start axon-agent when not configured`() {
        // User in core test modules has blank axonOpsOrg and axonOpsKey
        val command = Start()
        command.execute()

        // No axon-agent message should appear
        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).doesNotContain("axon-agent")
    }

    @Test
    fun `execute starts axon-agent when configured`() {
        // Override user config with axon ops settings
        val userWithAxon =
            User(
                email = "test@example.com",
                region = "us-west-2",
                keyName = "test-key",
                awsProfile = "",
                awsAccessKey = "test-access-key",
                awsSecret = "test-secret",
                axonOpsOrg = "my-org",
                axonOpsKey = "my-key",
            )
        getKoin().declare(userWithAxon)

        val command = Start()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("axon-agent")
    }

    @Test
    fun `execute fails fast naming nodes without a version`() {
        // db0 has a version, db1 does not — start must abort before touching any node.
        val db1 =
            ClusterHost(
                publicIp = "54.1.2.4",
                privateIp = "10.0.1.2",
                alias = "db1",
                availabilityZone = "us-west-2a",
                instanceId = "i-db1",
            )
        val stateMissingVersion =
            testClusterState.copy(
                versions = mutableMapOf("db0" to "5.0"),
                hosts = testClusterState.hosts + (ServerType.Cassandra to listOf(testCassandraHost, db1)),
            )
        whenever(mockClusterStateManager.load()).thenReturn(stateMissingVersion)

        val command = Start()

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("db1")
            .hasMessageContaining("cassandra use")

        // Fail-fast: no node was started.
        verify(mockCassandraService, never()).start(any(), any())
    }

    @Test
    fun `execute proceeds when every node has a version`() {
        val db1 =
            ClusterHost(
                publicIp = "54.1.2.4",
                privateIp = "10.0.1.2",
                alias = "db1",
                availabilityZone = "us-west-2a",
                instanceId = "i-db1",
            )
        val stateAllVersions =
            testClusterState.copy(
                versions = mutableMapOf("db0" to "5.0", "db1" to "5.0"),
                hosts = testClusterState.hosts + (ServerType.Cassandra to listOf(testCassandraHost, db1)),
            )
        whenever(mockClusterStateManager.load()).thenReturn(stateAllVersions)

        val command = Start()
        command.execute()

        // Both nodes started.
        verify(mockCassandraService, times(2)).start(any(), eq(true))
    }

    @Test
    fun `execute only requires versions for hosts targeted by the filter`() {
        // db1 has no version, but --hosts targets db0 only, so start must proceed.
        val db1 =
            ClusterHost(
                publicIp = "54.1.2.4",
                privateIp = "10.0.1.2",
                alias = "db1",
                availabilityZone = "us-west-2a",
                instanceId = "i-db1",
            )
        val state =
            testClusterState.copy(
                versions = mutableMapOf("db0" to "5.0"),
                hosts = testClusterState.hosts + (ServerType.Cassandra to listOf(testCassandraHost, db1)),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = Start()
        command.hosts.hostList = "db0"
        command.execute()

        verify(mockCassandraService).start(any(), eq(true))
    }
}
