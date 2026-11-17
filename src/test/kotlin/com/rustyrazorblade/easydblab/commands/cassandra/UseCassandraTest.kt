package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
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
import com.rustyrazorblade.easydblab.services.cassandraversions.VersionResolutionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.random.Random

/**
 * `cassandra use` over a fake fleet: the real resolver and installer run; SSH, Cassandra's state
 * and the nested config commands are stand-ins.
 */
class UseCassandraTest : BaseKoinTest() {
    private lateinit var nodes: FakeNodes
    private lateinit var stateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler
    private lateinit var state: ClusterState
    private var saveCount = 0

    private val chained = mutableListOf<PicoCommand>()
    private val chainedExitCodes = mutableMapOf<String, Int>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { stateManager }
                single<RemoteOperationsService> { nodes }
                single { HostOperationsService(stateManager) }
                single<CassandraService> { FakeCassandraService() }
                single { CassandraVersionResolver(RecordOnlySources()) }
                single { CassandraInstaller(get(), get(), get(), get()) }
                single<CommandExecutor> {
                    object : CommandExecutor {
                        override fun <T : PicoCommand> execute(commandFactory: () -> T): Int {
                            val command = commandFactory()
                            chained += command
                            return chainedExitCodes[command.javaClass.simpleName] ?: 0
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
        withNodes(2)
        whenever(stateManager.exists()).thenReturn(true)
        doAnswer { saveCount++ }.whenever(stateManager).save(any())
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    private fun withNodes(count: Int) {
        state =
            ClusterState(
                name = "test-cluster",
                initConfig = InitConfig(region = "us-west-2"),
                hosts = mapOf(ServerType.Cassandra to (0 until count).map { FakeNodes.host("db$it") }),
            )
        whenever(stateManager.load()).thenReturn(state)
    }

    private fun use(
        version: String,
        configure: UseCassandra.() -> Unit = {},
    ) = UseCassandra().apply {
        this.version = version
        configure()
    }

    private fun messages() = outputHandler.messages.joinToString("\n")

    private fun errors() = outputHandler.errors.joinToString("\n") { it.first }

    private fun activations(alias: String) = nodes.commandsOn(alias).filter { it.startsWith("sudo use-cassandra") }

    @Test
    fun `a version missing everywhere is installed, then activated with the chosen JDK and Python, and recorded once`() {
        use("5.0").execute()

        listOf("db0", "db1").forEach { alias ->
            assertThat(nodes.scriptRuns(alias)).hasSize(1)
            assertThat(activations(alias)).containsExactly("sudo use-cassandra 5.0 --java 17 --python 3.11.9")
            val node = state.nodes.getValue(alias)
            assertThat(node.version).isEqualTo("5.0")
            assertThat(node.javaVersion).isEqualTo("17")
            assertThat(node.installed.keys).containsExactly("5.0")
        }
        assertThat(state.default.version).isEqualTo("5.0")
        assertThat(saveCount).isEqualTo(1)
    }

    @Test
    fun `a node that has the version does not download it, and the side-effect warning names the one that does`() {
        nodes.disk("db0") += "5.0"

        use("5.0").execute()

        assertThat(nodes.scriptRuns("db0")).isEmpty()
        assertThat(nodes.scriptRuns("db1")).hasSize(1)
        val warning = outputHandler.messages.single { it.contains("re-applies") }
        assertThat(warning).contains("db1 download 5.0 first", "JDK 17", "cassandra.patch.yaml", "db0, db1")
    }

    @Test
    fun `a failed install on one node activates the version on no node and names the node and the URL`() {
        nodes.installFailures["db1"] = "ERROR: Failed to download version 5.0"

        assertThatThrownBy { use("5.0").execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(activations("db0") + activations("db1")).isEmpty()
        assertThat(state.nodes["db0"]?.version.orEmpty()).isEmpty()
        assertThat(errors()).contains("db1", "5.0", "5.0.6", "activated on no host")
        assertThat(chained).isEmpty()
    }

    @Test
    fun `a node that cannot be listed stops use before any node downloads`() {
        nodes.unreachable += "db1"

        assertThatThrownBy { use("5.0").execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(nodes.scriptRuns("db0")).isEmpty()
        assertThat(errors()).contains("db1")
    }

    @Test
    fun `a name no source has fails before any node is touched`() {
        assertThatThrownBy { use("no-such-version").execute() }.isInstanceOf(VersionResolutionException::class.java)

        assertThat(nodes.commands).isEmpty()
    }

    @Test
    fun `--java outside the supported list fails before any node is touched`() {
        assertThatThrownBy { use("5.0") { javaVersion = "21" }.execute() }
            .isInstanceOf(VersionResolutionException::class.java)
            .hasMessageContaining("11")
            .hasMessageContaining("17")

        assertThat(nodes.commands).isEmpty()
    }

    @Test
    fun `--java is passed to the node and recorded, and a later use without it keeps that JDK`() {
        use("5.0") { javaVersion = "11" }.execute()
        use("5.0").execute()

        assertThat(activations("db0")).containsExactly(
            "sudo use-cassandra 5.0 --java 11 --python 3.11.9",
            "sudo use-cassandra 5.0 --java 11 --python 3.11.9",
        )
        assertThat(state.nodes.getValue("db0").javaVersion).isEqualTo("11")
        assertThat(nodes.scriptRuns("db0")).hasSize(1)
    }

    @Test
    fun `--hosts switches only the named nodes and leaves the cluster default alone`() {
        state.nodes["db0"] = NodeState(version = "4.1")

        use("5.0") { hosts.hostList = "db1" }.execute()

        assertThat(nodes.commandsOn("db0")).isEmpty()
        assertThat(state.nodes.getValue("db0").version).isEqualTo("4.1")
        assertThat(state.nodes.getValue("db1").version).isEqualTo("5.0")
        assertThat(state.default.version).isEmpty()
        assertThat(chained.map { hostFilter(it) }).containsOnly("db1")
        assertThat(messages()).contains("Using version 5.0 on 1 hosts, filter: db1")
    }

    @Test
    fun `a host filter that matches no node fails before anything is switched`() {
        assertThatThrownBy { use("5.0") { hosts.hostList = "db9" }.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("db9")
        assertThat(nodes.commands).isEmpty()
    }

    @Test
    fun `a failed activation on a node fails use and records only the nodes that switched`() {
        nodes.failOn("db1", "sudo use-cassandra", "ERROR: JDK 17 is not installed")

        assertThatThrownBy { use("5.0").execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(state.nodes.getValue("db0").version).isEqualTo("5.0")
        assertThat(state.nodes.getValue("db1").version).isEmpty()
        assertThat(
            state.nodes
                .getValue("db1")
                .installed.keys,
        ).containsExactly("5.0")
        assertThat(errors()).contains("db1: failed to activate 5.0", "JDK 17 is not installed")
        assertThat(chained).isEmpty()
    }

    @Test
    fun `a failed config download fails use, skips the update and says which step failed`() {
        chainedExitCodes["DownloadConfig"] = 1

        assertThatThrownBy { use("5.0").execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(chained.filterIsInstance<UpdateConfig>()).isEmpty()
        assertThat(errors()).contains("5.0", "download-config failed", "update-config --hosts db0,db1")
    }

    @Test
    fun `a failed config update fails use and says which step failed`() {
        chainedExitCodes["UpdateConfig"] = 1

        assertThatThrownBy { use("5.0").execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(errors()).contains("update-config failed")
    }

    /** D3: the state is written once, after every node; no node's record is lost to a race. */
    @ParameterizedTest(name = "{0} nodes")
    @ValueSource(ints = [1, 50])
    fun `every node is recorded at any fleet size, with some already holding the version`(count: Int) {
        withNodes(count)
        val random = Random(count)
        state.hosts
            .getValue(ServerType.Cassandra)
            .filter { random.nextBoolean() }
            .forEach { nodes.disk(it.alias) += "4.1" }

        use("4.1").execute()

        assertThat(state.nodes).hasSize(count)
        assertThat(state.nodes.values).allSatisfy { node ->
            assertThat(node.version).isEqualTo("4.1")
            assertThat(node.javaVersion).isEqualTo("11")
            assertThat(node.installed.getValue("4.1").url).contains("4.1.7")
        }
        assertThat(saveCount).isEqualTo(1)
    }

    private fun hostFilter(command: PicoCommand): String =
        when (command) {
            is UpdateConfig -> command.hosts.hostList
            is DownloadConfig -> command.hosts.hostList
            else -> error("unexpected nested command $command")
        }
}
