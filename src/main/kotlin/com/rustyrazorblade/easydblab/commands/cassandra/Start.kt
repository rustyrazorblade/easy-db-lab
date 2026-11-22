package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.KitHookExecutor
import com.rustyrazorblade.easydblab.services.SidecarService
import com.rustyrazorblade.easydblab.services.cassandraversions.AxonOpsAgents
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

/**
 * Start cassandra on all nodes via service command.
 */
@RequireProfileSetup
@RequireSSHKey
@RequiresProxy
@Command(
    name = "start",
    description = ["Start cassandra on all nodes via service command"],
)
class Start : PicoBaseCommand() {
    private val userConfig: User by inject()
    private val cassandraService: CassandraService by inject()
    private val sidecarService: SidecarService by inject()
    private val hostOperationsService: HostOperationsService by inject()
    private val kitHookExecutor: KitHookExecutor by inject()
    private val axonOpsAgents: AxonOpsAgents by inject()

    companion object {
        private const val DEFAULT_SLEEP_BETWEEN_STARTS_SECONDS = 120L
        const val DEFAULT_SIDECAR_IMAGE = "ghcr.io/apache/cassandra-sidecar:latest"
    }

    @Option(names = ["--sleep"], description = ["Time to sleep between starts in seconds"])
    var sleep: Long = DEFAULT_SLEEP_BETWEEN_STARTS_SECONDS

    @Option(
        names = ["--sidecar-image"],
        description = ["Container image for the Cassandra sidecar DaemonSet (default: $DEFAULT_SIDECAR_IMAGE)"],
    )
    var sidecarImage: String = DEFAULT_SIDECAR_IMAGE

    @Option(
        names = ["--skip-axonops"],
        description = ["Start Cassandra with no AxonOps agent this time, and do not start axon-agent"],
    )
    var skipAxonOps: Boolean = false

    @Mixin
    var hosts = HostsMixin()

    override fun execute() {
        validateVersionsAssigned()
        val withAxonOps = userConfig.isAxonOpsEnabled() && !skipAxonOps
        selectAxonOpsAgents(withAxonOps)

        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { host ->
            val h = host.toHost()
            eventBus.emit(Event.Cassandra.Starting(h.alias))
            // start() defaults to wait=true, which includes waiting for UP/NORMAL
            cassandraService.start(h).getOrThrow()
        }

        // Deploy cassandra-sidecar DaemonSet via K3s
        val controlHost = clusterState.getControlHost()
        if (controlHost != null) {
            sidecarService
                .deploy(controlHost, sidecarImage)
                .onFailure { e ->
                    eventBus.emit(Event.Cassandra.SidecarStartFailed("${e.message}"))
                }
        }

        // Start axon-agent on Cassandra nodes if configured
        if (withAxonOps) {
            eventBus.emit(Event.Cassandra.AxonAgentStarting)
            hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList, parallel = true) { host ->
                remoteOps.executeRemotely(host.toHost(), "sudo systemctl start axon-agent")
            }
        }

        // Inform user about AxonOps Workbench configuration if it exists
        val axonOpsWorkbenchFile = context.workspaceFile(Constants.Workspace.AXONOPS_CONFIG_FILE)
        if (axonOpsWorkbenchFile.exists()) {
            eventBus.emit(Event.Cassandra.AxonOpsWorkbenchInfo(userConfig.axonOpsOrg))
        }

        clusterStateManager.addRunningWorkload("cassandra")
        kitHookExecutor.firePostKitStart("cassandra")
    }

    /**
     * Before Cassandra starts on any node, names in each node's systemd drop-in the AxonOps agent
     * that start loads: with AxonOps on, the agent installed for the node's release and JDK, and
     * when any node has none, nothing starts; else no agent.
     */
    private fun selectAxonOpsAgents(withAxonOps: Boolean) {
        val targets = hostOperationsService.filteredHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList)
        val unavailable = axonOpsAgents.selectForStart(targets, clusterState, withAxonOps)
        if (unavailable.isNotEmpty()) {
            unavailable.forEach { eventBus.emit(it.error) }
            throw CommandFailedException("cassandra start: no AxonOps agent on ${unavailable.joinToString(", ") { it.host.alias }}")
        }
    }

    /**
     * Fails fast when any targeted node has no version assigned.
     *
     * A node only gets a version once `cassandra use <version>` has run against it, which both
     * records the version in cluster state and points `/usr/local/cassandra/current` at that
     * install on the box. Starting a node without a version produces a confusing systemd failure
     * far downstream, so we reject it up front with an actionable message before touching any node.
     */
    private fun validateVersionsAssigned() {
        val targets =
            hostOperationsService
                .filteredHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList)
                .map { it.alias }
        val missing = targets.filter { clusterState.nodes[it]?.version.isNullOrBlank() }
        check(missing.isEmpty()) {
            "No version assigned for node(s): ${missing.joinToString(", ")}. " +
                "Run 'cassandra use <version>' (e.g. 'cassandra use 5.0') to assign a version before starting."
        }
        val noJdk = targets.filter { clusterState.nodes[it]?.javaVersion.isNullOrBlank() }
        check(noJdk.isEmpty()) {
            "No JDK recorded for the active version of node(s): ${noJdk.joinToString(", ")}. " +
                "Run 'cassandra use <version>' on them to record it before starting."
        }
    }
}
