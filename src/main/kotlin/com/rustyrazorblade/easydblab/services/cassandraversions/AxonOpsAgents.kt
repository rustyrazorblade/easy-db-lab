package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.reason
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.shellQuote
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials

/** The AxonOps agent one node starts Cassandra with, or why it has none (design D6). */
sealed interface AgentOutcome {
    val host: ClusterHost

    /** [agent] is installed on [host]. */
    data class Installed(
        override val host: ClusterHost,
        val agent: String,
    ) : AgentOutcome

    /** The node cannot start with AxonOps; [error] says why. */
    data class Unavailable(
        override val host: ClusterHost,
        val error: Event.Cassandra,
    ) : AgentOutcome
}

/**
 * Installs the AxonOps agent that matches each node's active release and JDK, through the
 * `install-axonops-agent` node script and the account's download cache, and names the agent the
 * next Cassandra start loads in a systemd drop-in. The JDK is the CLI's choice; nothing here
 * changes it to fit AxonOps.
 */
class AxonOpsAgents(
    private val remoteOps: RemoteOperationsService,
    private val hostOperations: HostOperationsService,
    private val sources: VersionSources,
) {
    /**
     * Installs the agent on each of [targets] in parallel, with the release line and JDK [state]
     * records for the node. Returns each node's outcome in target order; throws nothing.
     */
    fun install(
        targets: List<ClusterHost>,
        state: ClusterState,
    ): List<AgentOutcome> {
        val cachePrefix = sources.downloadCacheRoot().resolve(CACHE_DIR).toUri()
        return hostOperations
            .collectFromHosts(targets, parallel = true) { host ->
                val node = state.nodes[host.alias]
                val release = releaseLine(node?.version.orEmpty())
                val java = node?.javaVersion.orEmpty()
                val response = remoteOps.executeRemotely(host.toHost(), installCommand(release, java, cachePrefix), output = false)
                outcomeOf(host, release, java, response.text, response.stderr)
            }.map { (host, result) ->
                result.getOrElse {
                    AgentOutcome.Unavailable(host, Event.Cassandra.AxonOpsAgentInstallFailed(host.alias, redactUrlCredentials(it.reason())))
                }
            }
    }

    /**
     * Before Cassandra starts or restarts on [targets]: with [withAxonOps], installs each node's
     * agent for the release and JDK [state] records now (a `cassandra use` since the last start may
     * have changed them), and names it in the node's drop-in; else names no agent. When any node
     * has no agent, no drop-in changes and the nodes without one are returned.
     */
    fun selectForStart(
        targets: List<ClusterHost>,
        state: ClusterState,
        withAxonOps: Boolean,
    ): List<AgentOutcome.Unavailable> {
        val outcomes = if (withAxonOps) install(targets, state) else emptyList()
        val unavailable = outcomes.filterIsInstance<AgentOutcome.Unavailable>()
        if (unavailable.isNotEmpty()) return unavailable
        val agents = outcomes.filterIsInstance<AgentOutcome.Installed>().associate { it.host.alias to it.agent }
        hostOperations.withHosts(targets, parallel = true) { host -> select(host, agents[host.alias]) }
        return emptyList()
    }

    /**
     * After `cassandra use` switched [switched], the agent the last start named in each drop-in
     * belongs to the old release or JDK, and `cassandra restart` would load it. Each node whose
     * drop-in names an agent gets the agent of the version and JDK [state] now records; a node
     * whose drop-in names none (AxonOps off, or `start --skip-axonops`) is left alone. A node
     * AxonOps ships no agent for gets no agent, and is returned with the reason.
     */
    fun refreshAfterSwitch(
        switched: List<ClusterHost>,
        state: ClusterState,
    ): List<AgentOutcome.Unavailable> {
        val withAgent =
            hostOperations
                .collectFromHosts(switched, parallel = true) { host ->
                    remoteOps.executeRemotely(host.toHost(), READ_DROP_IN, output = false).text
                }.filter { (_, result) -> agentNamedIn(result.getOrDefault("")) != null }
                .map { it.host }
        if (withAgent.isEmpty()) return emptyList()
        val outcomes = install(withAgent, state)
        val agents = outcomes.filterIsInstance<AgentOutcome.Installed>().associate { it.host.alias to it.agent }
        hostOperations.withHosts(withAgent, parallel = true) { host -> select(host, agents[host.alias]) }
        return outcomes.filterIsInstance<AgentOutcome.Unavailable>()
    }

    /** Names [agent] in the drop-in of [host], or no agent when null, and reloads systemd. */
    fun select(
        host: ClusterHost,
        agent: String?,
    ) {
        remoteOps.executeRemotely(host.toHost(), selectCommand(agent), output = false)
    }

    companion object {
        /** The cache directory of the agent packages, under the account download cache (D5). */
        const val CACHE_DIR = "axonops"

        /** The drop-in that `cassandra.in.sh` reads the agent name from, through systemd. */
        const val DROP_IN = "/etc/systemd/system/cassandra.service.d/axonops.conf"

        /** The script's status when AxonOps ships no agent for the release and JDK. */
        private const val NO_AGENT = 3
        private const val EXIT_MARKER = "edl-exit:"

        /** The release line of [version]; a name with none (`trunk`) is passed as is, and the script refuses it. */
        fun releaseLine(version: String): String = ReleaseLines.leadingReleaseLine(version) ?: version

        /**
         * The script's exit status is the contract (0, 3, else a failure), and a non-zero exit would
         * reach the CLI as an exception with no status, so the status is printed as the last line.
         */
        fun installCommand(
            release: String,
            java: String,
            cachePrefix: String,
        ): String =
            "install-axonops-agent ${release.shellQuote()} ${java.shellQuote()} --cache-prefix ${cachePrefix.shellQuote()}; echo $EXIT_MARKER$?"

        /** Prints the drop-in, or nothing when the node has none. */
        const val READ_DROP_IN = "cat $DROP_IN 2>/dev/null || true"

        /** The agent a drop-in names, or null when it names none. */
        fun agentNamedIn(dropIn: String): String? =
            dropIn
                .lines()
                .firstOrNull { it.trim().startsWith("Environment=EDL_AXONOPS_AGENT=") }
                ?.substringAfter("EDL_AXONOPS_AGENT=")
                ?.trim()
                ?.ifEmpty { null }

        fun selectCommand(agent: String?): String {
            val line = "Environment=EDL_AXONOPS_AGENT=${agent.orEmpty()}"
            return "sudo mkdir -p ${DROP_IN.substringBeforeLast('/')} && " +
                "printf '%s\\n' '[Service]' ${line.shellQuote()} | sudo tee $DROP_IN > /dev/null && " +
                "sudo systemctl daemon-reload"
        }

        /** Reads the script's output: the agent on its last line before the status, or the list of shipped agents. */
        fun outcomeOf(
            host: ClusterHost,
            release: String,
            java: String,
            stdout: String,
            stderr: String,
        ): AgentOutcome {
            val lines = stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val status = lines.lastOrNull()?.removePrefix(EXIT_MARKER)?.toIntOrNull()
            val output = lines.dropLast(1)
            return when {
                status == 0 && output.isNotEmpty() -> AgentOutcome.Installed(host, output.last())
                status == NO_AGENT ->
                    AgentOutcome.Unavailable(
                        host,
                        Event.Cassandra.AxonOpsAgentNotShipped(host.alias, release, java, output),
                    )
                else -> {
                    val reason = stderr.trim().ifEmpty { "install-axonops-agent exited with status ${status ?: "unknown"}" }
                    AgentOutcome.Unavailable(host, Event.Cassandra.AxonOpsAgentInstallFailed(host.alias, reason))
                }
            }
        }
    }
}
