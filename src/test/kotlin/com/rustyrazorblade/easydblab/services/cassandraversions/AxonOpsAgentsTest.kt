package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.mock

/** The `install-axonops-agent` contract (design D6) as the CLI reads it, and the drop-in it writes. */
class AxonOpsAgentsTest {
    private val host = FakeNodes.host("db0")

    /** One row per exit status of the script, with what it printed. `|` separates stdout lines. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = ';',
        value = [
            "agent installed;               installing|5.0-agent-jdk17|edl-exit:0;     ;                    installed:5.0-agent-jdk17",
            "no agent for release and JDK;  4.1-agent|5.0-agent-jdk11|edl-exit:3;      ;                    not-shipped:4.1-agent,5.0-agent-jdk11",
            "other failure, stderr given;   edl-exit:1;                                 ERROR: apt has none; failed:ERROR: apt has none",
            "other failure, no stderr;      edl-exit:1;                                 ;                    failed:install-axonops-agent exited with status 1",
            "status 0 but no agent name;    edl-exit:0;                                 ;                    failed:install-axonops-agent exited with status 0",
            "no status line;                garbage;                                    ;                    failed:install-axonops-agent exited with status unknown",
        ],
    )
    fun `the script's output and status decide the node's outcome`(
        case: String,
        stdout: String,
        stderr: String?,
        expected: String,
    ) {
        val outcome = AxonOpsAgents.outcomeOf(host, "5.0", "21", stdout.replace('|', '\n'), stderr.orEmpty())

        val actual =
            when (outcome) {
                is AgentOutcome.Installed -> "installed:${outcome.agent}"
                is AgentOutcome.Unavailable ->
                    when (val error = outcome.error) {
                        is Event.Cassandra.AxonOpsAgentNotShipped -> "not-shipped:" + error.shipped.joinToString(",")
                        is Event.Cassandra.AxonOpsAgentInstallFailed -> "failed:" + error.reason
                        else -> "other"
                    }
            }
        assertThat(actual).describedAs(case).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("5.0, 5.0", "5.0-rustyrazorblade-HEAD, 5.0", "4.1.7, 4.1", "3.11, 3.11", "trunk, trunk")
    fun `the agent is chosen by the release line of the active version`(
        version: String,
        release: String,
    ) {
        assertThat(AxonOpsAgents.releaseLine(version)).isEqualTo(release)
    }

    @Test
    fun `each node is asked for the agent of its own release and JDK, with the account cache prefix`() {
        val nodes = FakeNodes()
        nodes.respond("db0", "install-axonops-agent", "4.1-agent\nedl-exit:0")
        nodes.respond("db1", "install-axonops-agent", "5.0-agent-jdk17\nedl-exit:0")
        val state =
            ClusterState(
                name = "c",
                nodes = mutableMapOf("db0" to NodeState("4.1", "11"), "db1" to NodeState("5.0-HEAD", "17")),
            )

        val outcomes =
            AxonOpsAgents(
                nodes,
                HostOperationsService(mock()),
                RecordOnlySources(),
            ).install(listOf(host, FakeNodes.host("db1")), state)

        assertThat(outcomes.map { (it as AgentOutcome.Installed).agent }).containsExactly("4.1-agent", "5.0-agent-jdk17")
        assertThat(nodes.commandsOn("db0").single())
            .startsWith("install-axonops-agent 4.1 11 --cache-prefix s3://acct-bucket/download-cache/axonops")
        assertThat(nodes.commandsOn("db1").single()).startsWith("install-axonops-agent 5.0 17 ")
    }

    @Test
    fun `the drop-in names the agent, or no agent, and reloads systemd`() {
        assertThat(AxonOpsAgents.selectCommand("4.1-agent"))
            .contains(" Environment=EDL_AXONOPS_AGENT=4.1-agent ", AxonOpsAgents.DROP_IN, "daemon-reload")
        assertThat(AxonOpsAgents.selectCommand(null)).contains(" Environment=EDL_AXONOPS_AGENT= ")
    }
}
