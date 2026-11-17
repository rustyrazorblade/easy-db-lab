package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.exceptions.CommandExecutionException
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraInstaller
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraVersionResolver
import com.rustyrazorblade.easydblab.services.cassandraversions.FakeCassandraService
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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * `cassandra install` end to end over a fake fleet: the real resolver and installer run, and only
 * SSH, Cassandra's state and the nested config commands are stand-ins.
 */
class CassandraInstallTest : BaseKoinTest() {
    private lateinit var nodes: FakeNodes
    private lateinit var stateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler
    private var running: Set<String> = emptySet()

    /** Each state.json write, as the nodes' records at that moment. */
    private val saves = mutableListOf<Map<String, Set<String>>>()

    /** The nested commands run, and the exit code each returns. */
    private val nested = mutableListOf<PicoCommand>()
    private val nestedExitCodes = mutableMapOf<Class<*>, Int>()

    private val state =
        ClusterState(
            name = "test-cluster",
            initConfig = InitConfig(region = "us-west-2"),
            hosts = mapOf(ServerType.Cassandra to listOf(FakeNodes.host("db0"), FakeNodes.host("db1"))),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { stateManager }
                single<RemoteOperationsService> { nodes }
                single { HostOperationsService(stateManager) }
                single<CassandraService> { FakeCassandraService(running) }
                single { CassandraVersionResolver(RecordOnlySources()) }
                single { CassandraInstaller(get(), get(), get(), get()) }
                single<CommandExecutor> {
                    object : CommandExecutor {
                        override fun <T : PicoCommand> execute(commandFactory: () -> T): Int {
                            val command = commandFactory()
                            nested += command
                            return nestedExitCodes[command.javaClass] ?: 0
                        }

                        override fun <T : PicoCommand> schedule(commandFactory: () -> T) {}
                    }
                }
            },
        )

    @BeforeEach
    fun setup() {
        nodes = FakeNodes()
        stateManager = mock()
        whenever(stateManager.load()).thenReturn(state)
        whenever(stateManager.exists()).thenReturn(true)
        doAnswer {
            saves += it.getArgument<ClusterState>(0).nodes.mapValues { (_, node) -> node.installed.keys.toSet() }
        }.whenever(stateManager).save(any())
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    private fun install(
        version: String = "5.0",
        configure: CassandraInstall.() -> Unit = {},
    ) = CassandraInstall().apply {
        this.version = version
        configure()
    }

    private fun errors() = outputHandler.errors.joinToString("\n") { it.first }

    @Test
    fun `an Apache release is installed on every node and recorded in one write after all of them`() {
        install().execute()

        assertThat(nodes.scriptRuns("db0").single()).startsWith("install-cassandra-version 5.0 --url ").contains("5.0.6")
        assertThat(nodes.scriptRuns("db1")).hasSize(1)
        assertThat(saves).containsExactly(mapOf("db0" to setOf("5.0"), "db1" to setOf("5.0")))
        assertThat(
            state.nodes
                .getValue("db0")
                .installed
                .getValue("5.0")
                .java,
        ).isEqualTo("17")
    }

    @Test
    fun `a node that fails is named in the error, and the node that succeeded is still recorded`() {
        nodes.installFailures["db1"] = "ERROR: Failed to download version 5.0"

        assertThatThrownBy { install().execute() }
            .isInstanceOf(CommandExecutionException::class.java)
            .hasMessageContaining("db1")
            .hasMessageContaining("Failed to download version 5.0")
        assertThat(saves.single()).isEqualTo(mapOf("db0" to setOf("5.0")))
    }

    @Test
    fun `--hosts installs only on the named nodes`() {
        install { hosts.hostList = "db1" }.execute()

        assertThat(nodes.scriptRuns("db0")).isEmpty()
        assertThat(nodes.scriptRuns("db1")).hasSize(1)
    }

    @Test
    fun `a --hosts filter that matches no db node is refused and names the nodes there are`() {
        assertThatThrownBy { install { hosts.hostList = "db9" }.execute() }
            .hasMessageContaining("db9")
            .hasMessageContaining("db0, db1")
        assertThat(nodes.commands).isEmpty()
    }

    @Test
    fun `a branch without a repository is refused before any node is touched`() {
        assertThatThrownBy { install("mine") { branch = "feature" }.execute() }
            .hasMessageContaining("--url")
        assertThat(nodes.commands).isEmpty()
    }

    @Test
    fun `a reinstall re-applies the config only on the nodes where the version is active`() {
        nodes.disk("db0") += "5.0"
        nodes.disk("db1") += "5.0"
        state.nodes["db0"] = NodeState(version = "5.0")
        state.nodes["db1"] = NodeState(version = "4.1")

        install { reinstall = true }.execute()

        assertThat(nodes.scriptRuns("db0").single()).endsWith("--reinstall")
        assertThat(nodes.scriptRuns("db1").single()).endsWith("--reinstall")
        assertThat(nested.map { it.javaClass.simpleName }).containsExactly("DownloadConfig", "UpdateConfig")
        assertThat(nested.map(::hostFilter)).containsOnly("db0")
    }

    /**
     * A reinstall replaces the bits of the version a node runs, built or chosen for the JDK the
     * reinstall resolved, so that node is activated again on that JDK and the state records it; a
     * node where the version is not active keeps its active version and JDK.
     */
    @ParameterizedTest(name = "--java [{0}]")
    @CsvSource("11, 11", "'', 17")
    fun `a reinstall of a node's active version activates it again on the reinstalled JDK`(
        java: String,
        expectedJava: String,
    ) {
        nodes.disk("db0") += "5.0"
        nodes.disk("db1") += "5.0"
        state.nodes["db0"] = NodeState(version = "5.0", javaVersion = "17")
        state.nodes["db1"] = NodeState(version = "4.1", javaVersion = "11")

        install {
            reinstall = true
            javaVersion = java
        }.execute()

        assertThat(nodes.commandsOn("db0").filter { it.startsWith("sudo use-cassandra") })
            .containsExactly("sudo use-cassandra 5.0 --java $expectedJava --python 3.11.9")
        assertThat(state.nodes.getValue("db0").javaVersion).isEqualTo(expectedJava)
        assertThat(nodes.commandsOn("db1").filter { it.startsWith("sudo use-cassandra") }).isEmpty()
        assertThat(state.nodes.getValue("db1").javaVersion).isEqualTo("11")
    }

    @Test
    fun `a reinstall whose activation fails on a node exits non-zero, names the node, and keeps its recorded JDK`() {
        nodes.disk("db0") += "5.0"
        state.nodes["db0"] = NodeState(version = "5.0", javaVersion = "17")
        nodes.failOn("db0", "sudo use-cassandra", "use-cassandra: no JDK 11")

        assertThatThrownBy {
            install {
                hosts.hostList = "db0"
                reinstall = true
                javaVersion = "11"
            }.execute()
        }.isInstanceOf(CommandExecutionException::class.java)
            .hasMessageContaining("db0")
        assertThat(state.nodes.getValue("db0").javaVersion).isEqualTo("17")
        assertThat(errors()).contains("db0", "no JDK 11")
    }

    @Test
    fun `a reinstall whose config step fails exits non-zero and names the step`() {
        nodes.disk("db0") += "5.0"
        state.nodes["db0"] = NodeState(version = "5.0")
        nestedExitCodes[UpdateConfig::class.java] = 1

        assertThatThrownBy { install { reinstall = true }.execute() }
            .isInstanceOf(CommandExecutionException::class.java)
            .hasMessageContaining("update-config")
        assertThat(errors()).contains("update-config")
    }

    @Test
    fun `a reinstall of the version Cassandra runs is refused on that node and changes nothing there`() {
        nodes.disk("db0") += "5.0"
        state.nodes["db0"] = NodeState(version = "5.0")
        running = setOf("db0")

        assertThatThrownBy { install { reinstall = true }.execute() }
            .isInstanceOf(CommandExecutionException::class.java)
            .hasMessageContaining("db0")
        assertThat(nodes.scriptRuns("db0")).isEmpty()
        assertThat(nodes.scriptRuns("db1")).hasSize(1)
        assertThat(errors()).contains("cassandra stop --hosts db0")
        assertThat(nested).isEmpty()
    }

    @Test
    fun `a version already on a node with no record is recorded without a download`() {
        nodes.disk("db0") += "5.0"

        install { hosts.hostList = "db0" }.execute()

        assertThat(nodes.scriptRuns("db0")).isEmpty()
        assertThat(saves.single()).isEqualTo(mapOf("db0" to setOf("5.0")))
    }

    private fun hostFilter(command: PicoCommand): String =
        when (command) {
            is UpdateConfig -> command.hosts.hostList
            is DownloadConfig -> command.hosts.hostList
            else -> error("unexpected nested command $command")
        }
}
