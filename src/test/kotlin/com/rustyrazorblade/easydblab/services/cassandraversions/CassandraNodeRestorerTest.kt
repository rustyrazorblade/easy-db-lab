package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.CassandraSource
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.InstalledVersion
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.mock

/**
 * A replaced db node (design D7): it installs only its active version, from the record and not a
 * new resolution, and its new record lists only that version.
 */
class CassandraNodeRestorerTest : BaseKoinTest() {
    private lateinit var nodes: FakeNodes
    private lateinit var outputHandler: BufferedOutputHandler
    private val host = FakeNodes.host("db1")

    @BeforeEach
    fun setup() {
        nodes = FakeNodes()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    /** The ASF list has 5.0.6, so a new resolution of `5.0` would install 5.0.6. */
    private fun restorer(): CassandraNodeRestorer {
        val eventBus = getKoin().get<EventBus>()
        val installer = CassandraInstaller(nodes, HostOperationsService(mock()), FakeCassandraService(), eventBus)
        return CassandraNodeRestorer(CassandraVersionResolver(RecordOnlySources(asf = listOf("5.0.5", "5.0.6"))), installer, eventBus)
    }

    private fun state(
        node: NodeState? = null,
        default: NodeState = NodeState(),
    ) = ClusterState(name = "c", initConfig = InitConfig(region = "us-west-2")).apply {
        node?.let { nodes["db1"] = it }
        this.default = default
    }

    private fun record(
        version: String,
        source: CassandraSource,
        url: String,
        java: String,
        python: String,
    ) = InstalledVersion(version, java, listOf("11", "17"), python, source, url)

    private fun activations() = nodes.commandsOn("db1").filter { it.startsWith("sudo use-cassandra") }

    @ParameterizedTest(name = "{0} from {1}")
    @CsvSource(
        "5.0-HEAD, CASSANDRA_BUILDS, https://gh.test/apache-cassandra-5.0-HEAD-bin.tar.gz, 17, 3.11.9",
        "5.0,      ASF,              https://dlcdn.apache.org/cassandra/5.0.5/apache-cassandra-5.0.5-bin.tar.gz, 11, 3.10.4",
    )
    fun `the active version installs from its record with the recorded JDK and Python, and is the only one recorded after`(
        version: String,
        source: CassandraSource,
        url: String,
        java: String,
        python: String,
    ) {
        val active = record(version, source, url, java, python)
        val other =
            record("4.1", CassandraSource.ASF, "https://dlcdn.apache.org/cassandra/4.1.7/apache-cassandra-4.1.7-bin.tar.gz", "11", "3.11.9")
        val node = NodeState(version, java, mutableMapOf(version to active, "4.1" to other))

        val result = restorer().restore(host, state(node))

        assertThat(nodes.scriptRuns("db1")).singleElement().asString().startsWith("install-cassandra-version $version --url $url")
        assertThat(activations()).containsExactly("sudo use-cassandra $version --java $java --python $python")
        assertThat(result).isEqualTo(RestoreResult.Restored(NodeState(version, java, mutableMapOf(version to active))))
    }

    @Test
    fun `a node with no record of its own installs the cluster default, resolved as a use would`() {
        val result = restorer().restore(host, state(default = NodeState("5.0", "17")))

        assertThat(nodes.scriptRuns("db1")).singleElement().asString().contains("5.0.6")
        assertThat(activations()).containsExactly("sudo use-cassandra 5.0 --java 17 --python 3.11.9")
        val restored = (result as RestoreResult.Restored).node
        assertThat(restored.version to restored.installed.keys).isEqualTo("5.0" to setOf("5.0"))
    }

    @Test
    fun `a node of a cluster that never chose a version installs nothing`() {
        assertThat(restorer().restore(host, state())).isEqualTo(RestoreResult.NothingToRestore)
        assertThat(nodes.commands).isEmpty()
    }

    @Test
    fun `a version recorded with a redacted credential is not installed, and the error says how to install it again`() {
        val active = record("mine", CassandraSource.GIT, "https://***@github.test/me/cassandra.git", "11", "3.11.9")

        val result = restorer().restore(host, state(NodeState("mine", "11", mutableMapOf("mine" to active))))

        assertThat(result).isInstanceOf(RestoreResult.Failed::class.java)
        assertThat(nodes.commands).isEmpty()
        assertThat(outputHandler.errors.joinToString { it.first })
            .contains("db1", "mine", "cassandra install mine --reinstall --url", "--hosts db1")
    }

    @Test
    fun `a failed install activates nothing`() {
        nodes.installFailures["db1"] = "curl: (22) 404"
        val active =
            record("5.0", CassandraSource.ASF, "https://dlcdn.apache.org/cassandra/5.0.5/apache-cassandra-5.0.5-bin.tar.gz", "17", "3.11.9")

        val result = restorer().restore(host, state(NodeState("5.0", "17", mutableMapOf("5.0" to active))))

        assertThat(result).isInstanceOf(RestoreResult.Failed::class.java)
        assertThat(activations()).isEmpty()
    }

    @Test
    fun `a failed activation fails the restore`() {
        nodes.failOn("db1", "sudo use-cassandra", "no such JDK")

        val result = restorer().restore(host, state(default = NodeState("5.0", "17")))

        assertThat(result).isInstanceOf(RestoreResult.Failed::class.java)
        assertThat(outputHandler.errors.joinToString { it.first }).contains("db1", "no such JDK")
    }
}
