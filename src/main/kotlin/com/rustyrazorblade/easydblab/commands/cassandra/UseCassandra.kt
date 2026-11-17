package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.cassandraversions.AxonOpsAgents
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraInstaller
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraVersionResolver
import com.rustyrazorblade.easydblab.services.cassandraversions.HostOutcome
import com.rustyrazorblade.easydblab.services.cassandraversions.InstallMode
import com.rustyrazorblade.easydblab.services.cassandraversions.VersionRequest
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

/**
 * Switches the db nodes to a Cassandra version, installing it first on each node that lacks it.
 *
 * The resolver picks one install for the cluster, and [CassandraInstaller] plans every node before
 * any changes: when any node cannot have the version, no node is switched. The nodes' records,
 * active version and JDK are written to the cluster state once, after every node finished.
 */
@RequireProfileSetup
@TriggerBackup
@Command(
    name = "use",
    description = ["Switch the active Cassandra version, installing it where it is missing"],
)
class UseCassandra : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()
    private val commandExecutor: CommandExecutor by inject()
    private val resolver: CassandraVersionResolver by inject()
    private val installer: CassandraInstaller by inject()
    private val axonOpsAgents: AxonOpsAgents by inject()

    @Parameters(description = ["Cassandra version"], index = "0")
    lateinit var version: String

    @Mixin
    var hosts = HostsMixin()

    @Option(
        names = ["--java", "-j"],
        description = ["JDK to run the version on, e.g. 8, 11, 17, 21 or 25 (default: the CLI's choice for the version)"],
    )
    var javaVersion = ""

    @Option(
        names = ["--python"],
        description = ["Python version cqlsh runs under (default: ${Constants.Cassandra.DEFAULT_PYTHON_VERSION})"],
    )
    var pythonVersion = ""

    override fun execute() {
        check(version.isNotBlank()) { "A version is required, e.g. 'cassandra use 5.0'" }
        if (!clusterStateManager.exists()) {
            eventBus.emit(Event.Cassandra.ClusterStateNotFound)
            throw CommandFailedException("cassandra use: no cluster state")
        }
        val state = clusterState
        val targeted = hostOperationsService.filteredHosts(state.hosts, ServerType.Cassandra, hosts.hostList)
        require(targeted.isNotEmpty()) {
            "No ${ServerType.Cassandra.serverType} host matches --hosts '${hosts.hostList}'"
        }
        val request =
            VersionRequest(
                name = version,
                java = javaVersion.ifBlank { null },
                python = pythonVersion.ifBlank { null },
            )
        val spec = resolver.resolve(request, state)
        eventBus.emit(Event.Cassandra.UsingVersion(spec.name, targeted.size, hosts.hostList, spec.source.name, spec.recordedUrl, spec.java))

        val plan = installer.plan(request, targeted, state, InstallMode.USE) { node -> resolver.forNode(spec, request, node) }
        eventBus.emit(
            Event.Cassandra.UseSideEffects(
                version = spec.name,
                patchFile = Constants.ConfigPaths.CASSANDRA_PATCH_FILE,
                hosts = targeted.map { it.alias },
                downloadHosts = plan.downloadHosts,
                javaVersions = plan.javaVersions,
                javaOverride = request.java != null,
            ),
        )

        if (!plan.canSucceed) {
            plan.refusals.forEach { eventBus.emit(it.error) }
            notActivated(spec.name, plan.refusals.map { it.host.alias })
        }
        val report = installer.execute(plan, reportPresent = false)
        // A node that installed the version keeps the install and its record, even when another
        // node failed and no node is switched: a retry then downloads only where it failed.
        report.recordInto(state)
        if (report.failed.isNotEmpty()) {
            clusterStateManager.save(state)
            notActivated(spec.name, report.failed.map { it.host.alias })
        }

        val activated = installer.activate(report.ready)
        recordActive(state, activated.filterIsInstance<HostOutcome.Ready>(), allActivated = activated.all { it is HostOutcome.Ready })
        clusterStateManager.save(state)

        // A node that switched runs the new version's conf directory, so it gets the patch even when
        // another node failed to switch; a node that did not switch keeps its old version and config.
        val switched = activated.filterIsInstance<HostOutcome.Ready>().map { it.host }
        val failed = activated.filterIsInstance<HostOutcome.Failed>().map { it.host.alias }
        if (switched.isNotEmpty()) applyConfig(switched.map { it.alias })
        refreshAxonOpsAgents(switched, state)
        if (failed.isNotEmpty()) {
            throw CommandFailedException("cassandra use: $version was not activated on ${failed.joinToString(", ")}")
        }
        eventBus.emit(Event.Cassandra.ConfigUpdateHint(version))
    }

    /**
     * Each activated node runs its spec's version and JDK. With no `--hosts`, and when every node
     * switched, the choice is also the cluster default, which a node with no record of its own (a
     * new node) uses; a default that some nodes do not run would describe no cluster.
     */
    private fun recordActive(
        state: ClusterState,
        activated: List<HostOutcome.Ready>,
        allActivated: Boolean,
    ) {
        activated.forEach { outcome ->
            state.nodes.getOrPut(outcome.host.alias) { NodeState() }.apply {
                version = outcome.spec.name
                javaVersion = outcome.spec.java
            }
        }
        val first = activated.firstOrNull() ?: return
        if (hosts.hostList.isBlank() && allActivated) {
            state.default = state.default.copy(version = first.spec.name, javaVersion = first.spec.java)
        }
    }

    /** No node is switched: [notReady] could not get [name]. */
    private fun notActivated(
        name: String,
        notReady: List<String>,
    ): Nothing {
        eventBus.emit(Event.Cassandra.UseNotActivated(name, notReady))
        throw CommandFailedException("cassandra use: $version was not installed on ${notReady.joinToString(", ")}")
    }

    /**
     * A switched node whose last start loaded an AxonOps agent gets the agent of its new version
     * and JDK in its drop-in, so `cassandra restart` does not load the old one. A node AxonOps
     * ships no agent for fails `use`; it runs the new version, and `start --skip-axonops` starts it.
     */
    private fun refreshAxonOpsAgents(
        switched: List<ClusterHost>,
        state: ClusterState,
    ) {
        val unavailable = axonOpsAgents.refreshAfterSwitch(switched, state)
        if (unavailable.isEmpty()) return
        unavailable.forEach { eventBus.emit(it.error) }
        throw CommandFailedException("cassandra use: no AxonOps agent for $version on ${unavailable.joinToString(", ") { it.host.alias }}")
    }

    /** Applies the patch file again on [aliases], now on the version they run. */
    private fun applyConfig(aliases: List<String>) {
        val failedStep = reapplyPatch(commandExecutor, HostsMixin().apply { hostList = aliases.joinToString(",") }) ?: return
        eventBus.emit(Event.Cassandra.UseConfigNotApplied(version, failedStep, aliases))
        throw CommandFailedException("cassandra use: $failedStep failed after the version switch")
    }
}
