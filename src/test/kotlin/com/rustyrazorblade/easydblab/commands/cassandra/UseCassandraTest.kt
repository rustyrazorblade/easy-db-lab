package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class UseCassandraTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var hostOperationsService: HostOperationsService
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
            versions = mutableMapOf(),
            initConfig = InitConfig(region = "us-west-2"),
            hosts =
                mapOf(
                    ServerType.Cassandra to listOf(testCassandraHost),
                ),
        )

    // No-op CommandExecutor — skips DownloadConfig and UpdateConfig sub-commands.
    // Used to isolate version state mutation from file I/O side effects.
    private val chained = mutableListOf<PicoCommand>()
    private var chainedExitCode = 0
    private val noopCommandExecutor =
        object : CommandExecutor {
            override fun <T : PicoCommand> execute(commandFactory: () -> T): Int {
                chained += commandFactory()
                return chainedExitCode
            }

            override fun <T : PicoCommand> schedule(commandFactory: () -> T) {}
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mockClusterStateManager }
                single { hostOperationsService }
                single<CommandExecutor> { noopCommandExecutor }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = mock()
        hostOperationsService = HostOperationsService(mockClusterStateManager)
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
        whenever(mockClusterStateManager.exists()).thenReturn(true)

        // Some tests chain to UpdateConfig — create a patch file for them.
        File(context.workingDirectory, "cassandra.patch.yaml").writeText(
            """
            cluster_name: test-cluster
            num_tokens: 4
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanup() {
        File(context.workingDirectory, "cassandra.patch.yaml").delete()
    }

    @Test
    fun `execute uses cassandra version and saves state`() {
        val command = UseCassandra()
        command.version = "4.1"
        command.execute()

        verify(mockClusterStateManager).save(testClusterState)
        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Using version 4.1")
    }

    /** The host filter is reported as typed, not as the internal mixin object. */
    @Test
    fun `the version announcement reports the host filter`() {
        val command = UseCassandra()
        command.version = "4.1"
        command.hosts.hostList = "db0"
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("Using version 4.1 on 1 hosts, filter: db0")
        assertThat(output).doesNotContain("HostsMixin", "Host(", "i-db0")
    }

    @Test
    fun `without a host filter the announcement says all hosts`() {
        val command = UseCassandra()
        command.version = "4.1"
        command.execute()

        assertThat(outputHandler.messages.joinToString("\n")).contains("Using version 4.1 on 1 hosts, filter: all hosts")
    }

    @Test
    fun `execute updates version in cluster state`() {
        val command = UseCassandra()
        command.version = "5.0"
        command.execute()

        assertThat(testClusterState.versions).containsEntry("db0", "5.0")
    }

    /** DownloadConfig used to ignore the filter and fetch the first host's version. */
    @Test
    fun `use hands its host filter to download-config as well as update-config`() {
        val command = UseCassandra()
        command.version = "4.1"
        command.hosts.hostList = "db0"
        command.execute()

        val download = chained.filterIsInstance<DownloadConfig>().single()
        assertThat(download.hosts.hostList).isEqualTo("db0")
        assertThat(
            chained
                .filterIsInstance<UpdateConfig>()
                .single()
                .hosts.hostList,
        ).isEqualTo("db0")
    }

    /** A chained command that fails used to be ignored, so `use` printed the failure and exited 0. */
    @Test
    fun `use fails when the config download or update it chains to fails`() {
        chainedExitCode = 1

        val command = UseCassandra()
        command.version = "4.1"

        assertThatThrownBy { command.execute() }.isInstanceOf(CommandFailedException::class.java)
    }

    /**
     * `use` ends by re-applying cassandra.patch.yaml to every targeted host, which discards any
     * per-host update-config done before it. The operator is told, with the hosts, before it happens.
     */
    @Test
    fun `use warns which hosts get cassandra patch yaml re-applied and that the JDK switches`() {
        val command = UseCassandra()
        command.version = "4.1"
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output)
            .contains("cassandra.patch.yaml", "db0", "replaces per-host", "update-config", "JDK")
    }

    @Test
    fun `the warning names only the targeted hosts and the JDK override`() {
        val second = testCassandraHost.copy(alias = "db1", privateIp = "10.0.1.2", instanceId = "i-db1")
        whenever(mockClusterStateManager.load()).thenReturn(
            testClusterState.copy(hosts = mapOf(ServerType.Cassandra to listOf(testCassandraHost, second))),
        )
        val command = UseCassandra()
        command.version = "4.1"
        command.hosts.hostList = "db1"
        command.javaVersion = "17"
        command.execute()

        val warning = outputHandler.messages.single { it.contains("re-applies") }
        assertThat(warning).contains("db1", "JDK 17").doesNotContain("db0")
    }
}
