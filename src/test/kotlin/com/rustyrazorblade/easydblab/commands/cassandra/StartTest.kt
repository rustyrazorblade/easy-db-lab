package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.SidecarService
import com.rustyrazorblade.easydblab.services.cassandraversions.AxonOpsAgents
import com.rustyrazorblade.easydblab.services.cassandraversions.FakeNodes
import com.rustyrazorblade.easydblab.services.cassandraversions.RecordOnlySources
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
            nodes = mutableMapOf("db0" to NodeState("5.0", "17")),
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
                single<RemoteOperationsService> { nodes }
                single { AxonOpsAgents(get(), get(), RecordOnlySources()) }
            },
        )

    /** The db nodes; `install-axonops-agent` answers as each test sets. */
    private val nodes = FakeNodes()

    private fun enableAxonOps() {
        getKoin().declare(
            User(
                email = "test@example.com",
                region = "us-west-2",
                keyName = "test-key",
                awsProfile = "",
                awsAccessKey = "test-access-key",
                awsSecret = "test-secret",
                axonOpsOrg = "my-org",
                axonOpsKey = "my-key",
            ),
        )
    }

    private fun dropIns(alias: String) = nodes.commandsOn(alias).filter { it.contains(AxonOpsAgents.DROP_IN) }

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
        whenever(mockSidecarService.deploy(any())).thenReturn(Result.success(Unit))
    }

    @Test
    fun `execute starts cassandra and sidecar on all nodes`() {
        val command = Start()
        command.execute()

        verify(mockCassandraService).start(any(), eq(true))
        verify(mockSidecarService).deploy(any())
    }

    @Test
    fun `execute outputs starting message`() {
        val command = Start()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Starting")
    }

    @Test
    fun `emits the AxonOps Workbench info only when the workspace holds its file`() {
        Start().execute()
        assertThat(outputHandler.messages.joinToString("\n")).doesNotContain("axonops-workbench.json")

        java.io.File(context.workingDirectory, "axonops-workbench.json").writeText("{}")
        val before = outputHandler.messages.size
        Start().execute()
        assertThat(outputHandler.messages.drop(before).joinToString("\n")).contains("axonops-workbench.json")
    }

    /** With AxonOps off, or skipped, the start loads no agent and runs no axon-agent. */
    @ParameterizedTest(name = "axonops on={0}, --skip-axonops={1}")
    @CsvSource("false, false", "true, true")
    fun `a start without AxonOps names no agent in the drop-in and installs none`(
        axonOpsOn: Boolean,
        skip: Boolean,
    ) {
        if (axonOpsOn) enableAxonOps()

        Start().apply { skipAxonOps = skip }.execute()

        assertThat(dropIns("db0").single()).contains(" Environment=EDL_AXONOPS_AGENT= ")
        assertThat(nodes.commandsOn("db0")).noneMatch { it.startsWith("install-axonops-agent") || it.contains("start axon-agent") }
        verify(mockCassandraService).start(any(), eq(true))
    }

    @Test
    fun `with AxonOps on, the agent of the node's release and JDK is installed and named before Cassandra starts`() {
        enableAxonOps()
        testClusterState.nodes["db0"] = NodeState("5.0", "17")
        nodes.respond("db0", "install-axonops-agent", "5.0-agent-jdk17\nedl-exit:0")

        Start().execute()

        val commands = nodes.commandsOn("db0")
        assertThat(commands.first()).startsWith("install-axonops-agent 5.0 17 ")
        assertThat(dropIns("db0").single()).contains(" Environment=EDL_AXONOPS_AGENT=5.0-agent-jdk17 ")
        assertThat(commands.last()).isEqualTo("sudo systemctl start axon-agent")
        verify(mockCassandraService).start(any(), eq(true))
    }

    @Test
    fun `when AxonOps ships no agent for a node, Cassandra starts on no node and the error names --skip-axonops`() {
        enableAxonOps()
        val db1 = FakeNodes.host("db1")
        whenever(mockClusterStateManager.load()).thenReturn(
            testClusterState.copy(
                nodes = mutableMapOf("db0" to NodeState("5.0", "17"), "db1" to NodeState("5.0-rustyrazorblade-HEAD", "21")),
                hosts = testClusterState.hosts + (ServerType.Cassandra to listOf(testCassandraHost, db1)),
            ),
        )
        nodes.respond("db0", "install-axonops-agent", "5.0-agent-jdk17\nedl-exit:0")
        nodes.respond("db1", "install-axonops-agent", "4.1-agent\n5.0-agent-jdk17\nedl-exit:3")

        assertThatThrownBy { Start().execute() }.isInstanceOf(CommandFailedException::class.java)

        // db0 has an agent, yet neither node starts: the check of every node comes first.
        verify(mockCassandraService, never()).start(any(), any())
        assertThat(dropIns("db0") + dropIns("db1")).isEmpty()
        assertThat(outputHandler.errors.joinToString("\n") { it.first })
            .contains("Cassandra 5.0 on JDK 21", "4.1-agent, 5.0-agent-jdk17", "--skip-axonops")
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
                nodes = mutableMapOf("db0" to NodeState("5.0", "17")),
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
    fun `execute fails fast naming a node whose version has no JDK recorded`() {
        val stateNoJdk = testClusterState.copy(nodes = mutableMapOf("db0" to NodeState("5.0", "")))
        whenever(mockClusterStateManager.load()).thenReturn(stateNoJdk)

        assertThatThrownBy { Start().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContainingAll("db0", "JDK", "cassandra use")

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
                nodes = mutableMapOf("db0" to NodeState("5.0", "17"), "db1" to NodeState("5.0", "17")),
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
                nodes = mutableMapOf("db0" to NodeState("5.0", "17")),
                hosts = testClusterState.hosts + (ServerType.Cassandra to listOf(testCassandraHost, db1)),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)

        val command = Start()
        command.hosts.hostList = "db0"
        command.execute()

        verify(mockCassandraService).start(any(), eq(true))
    }
}
