package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
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
import kotlin.reflect.KClass

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
    // Used to isolate version state mutation from file I/O side effects. It records each nested
    // command and answers with the exit code nestedExitCodes gives its class (0 by default).
    private val nestedRan = mutableListOf<KClass<*>>()
    private val nestedExitCodes = mutableMapOf<KClass<*>, Int>()
    private val noopCommandExecutor =
        object : CommandExecutor {
            override fun <T : PicoCommand> execute(commandFactory: () -> T): Int {
                val command = commandFactory()
                nestedRan.add(command::class)
                return nestedExitCodes[command::class] ?: 0
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
    fun `a failed download-config fails use and does not update the config`() {
        nestedExitCodes[DownloadConfig::class] = Constants.ExitCodes.ERROR
        val events = mutableListOf<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
        val command = UseCassandra()
        command.version = "4.1"

        assertThatThrownBy { command.execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(nestedRan).containsExactly(DownloadConfig::class)
        assertThat(events.filterIsInstance<Event.Command.NestedCommandFailed>().single())
            .isEqualTo(Event.Command.NestedCommandFailed(command = "use", nested = "download-config", exitCode = Constants.ExitCodes.ERROR))
    }

    @Test
    fun `a failed update-config fails use`() {
        nestedExitCodes[UpdateConfig::class] = Constants.ExitCodes.ERROR
        val command = UseCassandra()
        command.version = "4.1"

        assertThatThrownBy { command.execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(nestedRan).containsExactly(DownloadConfig::class, UpdateConfig::class)
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
}
