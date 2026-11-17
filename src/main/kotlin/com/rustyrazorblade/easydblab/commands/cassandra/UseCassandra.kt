package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.HostOperationsService
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
        eventBus.emit(Event.Cassandra.UsingVersion(spec.name, targeted.size, hosts.hostList))

        val plan = installer.plan(spec, request, targeted, state, InstallMode.USE) { node -> resolver.forNode(spec, request, node) }
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
        recordActive(state, activated.filterIsInstance<HostOutcome.Ready>())
        clusterStateManager.save(state)

        val failed = activated.filterIsInstance<HostOutcome.Failed>().map { it.host.alias }
        if (failed.isNotEmpty()) {
            throw CommandFailedException("cassandra use: $version was not activated on ${failed.joinToString(", ")}")
        }
        applyConfig(targeted.map { it.alias })
        eventBus.emit(Event.Cassandra.ConfigUpdateHint(version))
    }

    /**
     * Each activated node runs its spec's version and JDK. With no `--hosts`, the choice is also
     * the cluster default, which a node with no record of its own (a new node) uses.
     */
    private fun recordActive(
        state: ClusterState,
        activated: List<HostOutcome.Ready>,
    ) {
        activated.forEach { outcome ->
            state.nodes.getOrPut(outcome.host.alias) { NodeState() }.apply {
                version = outcome.spec.name
                javaVersion = outcome.spec.java
            }
        }
        val first = activated.firstOrNull() ?: return
        if (hosts.hostList.isBlank()) {
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

    /** Applies the patch file again on the targeted hosts, now on the version they run. */
    private fun applyConfig(aliases: List<String>) {
        val failedStep = reapplyPatch(commandExecutor, hosts) ?: return
        eventBus.emit(Event.Cassandra.UseConfigNotApplied(version, failedStep, aliases))
        throw CommandFailedException("cassandra use: $failedStep failed after the version switch")
    }
}
