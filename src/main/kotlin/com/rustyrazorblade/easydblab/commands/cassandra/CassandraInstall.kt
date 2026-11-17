package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.exceptions.CommandExecutionException
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraInstaller
import com.rustyrazorblade.easydblab.services.cassandraversions.CassandraVersionResolver
import com.rustyrazorblade.easydblab.services.cassandraversions.InstallMode
import com.rustyrazorblade.easydblab.services.cassandraversions.VersionRequest
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

/**
 * Installs one Cassandra version onto the db nodes of a running cluster, without an AMI rebuild.
 *
 * The resolver turns the name and the options into one install for the whole cluster (an Apache
 * release, a cassandra-builds nightly, a profile build, a catalog build, or `--url`), and the
 * shared [CassandraInstaller] runs it on each node. The result is recorded in the cluster state,
 * once, after every node finished; nothing about the version is written to a node.
 */
@RequireProfileSetup
@TriggerBackup
@Command(
    name = "install",
    description = ["Install a Cassandra version onto a running cluster"],
)
class CassandraInstall : PicoBaseCommand() {
    private val hostOperationsService: HostOperationsService by inject()
    private val resolver: CassandraVersionResolver by inject()
    private val installer: CassandraInstaller by inject()
    private val commandExecutor: CommandExecutor by inject()

    @Parameters(description = ["Cassandra version to install"], index = "0")
    lateinit var version: String

    @Mixin
    var hosts = HostsMixin()

    @Option(
        names = ["--url"],
        description = ["Tarball URL (.tar.gz or s3://) or git repository URL (with --branch)"],
    )
    var url: String = ""

    @Option(
        names = ["--branch"],
        description = ["Git branch to clone and build, requires --url"],
    )
    var branch: String = ""

    @Option(
        names = ["--java", "-j"],
        description = ["JDK to build and run this version with, e.g. 8, 11, 17, 21, 25"],
    )
    var javaVersion: String = ""

    @Option(
        names = ["--python"],
        description = ["Python version cqlsh runs under (default: ${Constants.Cassandra.DEFAULT_PYTHON_VERSION})"],
    )
    var pythonVersion: String = ""

    @Option(
        names = ["--ant-flags"],
        description = ["Extra flags passed to ant when building from a branch"],
    )
    var antFlags: String = ""

    @Option(
        names = ["--reinstall"],
        description = ["Download or build the version again on each node, replacing the install (stop Cassandra first)"],
    )
    var reinstall: Boolean = false

    override fun execute() {
        check(version.isNotBlank()) { "A version to install is required, e.g. 'cassandra install 5.0'" }
        require(branch.isBlank() || url.isNotBlank()) { "Building $version from a branch requires a git repository: pass --url." }

        val state = clusterState
        val targets = targetedHosts()
        val request = request()
        val spec = resolver.resolve(request, state)

        eventBus.emit(Event.Cassandra.InstallingVersion(spec.name, targets.size, hosts.hostList))

        val mode = if (reinstall) InstallMode.REINSTALL else InstallMode.INSTALL
        val plan = installer.plan(spec, request, targets, state, mode) { node -> resolver.forNode(spec, request, node) }
        val report = installer.execute(plan, reportPresent = true)

        report.recordInto(state)
        clusterStateManager.save(state)

        val reapplied = if (reinstall) reapplyConfig(report.ready.map { it.host.alias }) else null
        report.failureMessage()?.let { throw CommandExecutionException(it) }
        reapplied?.let { throw CommandExecutionException(it) }
    }

    private fun request() =
        VersionRequest(
            name = version,
            url = url.ifBlank { null },
            branch = branch.ifBlank { null },
            java = javaVersion.ifBlank { null },
            python = pythonVersion.ifBlank { null },
            antFlags = antFlags.ifBlank { null },
            reinstall = reinstall,
        )

    private fun targetedHosts(): List<ClusterHost> {
        val available = clusterState.getHosts(ServerType.Cassandra)
        val targeted = hostOperationsService.filteredHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList)
        // Installing on nothing is never what was meant; without this the command reports success
        // for an install that never ran.
        require(targeted.isNotEmpty()) {
            if (available.isEmpty()) {
                "This cluster has no Cassandra nodes. Check you are in the right cluster workspace directory."
            } else {
                "--hosts ${hosts.hostList} matched no Cassandra nodes; available: " + available.joinToString(", ") { it.alias }
            }
        }
        return targeted
    }

    /**
     * After a reinstall, the conf directory of each node where [version] is the active version is
     * the fresh one of the new install, so `cassandra.patch.yaml` is applied again there, as
     * `cassandra use` does. Returns the error when a step failed, else null.
     */
    private fun reapplyConfig(reinstalled: List<String>): String? {
        val active = reinstalled.filter { clusterState.nodes[it]?.version == version }
        if (active.isEmpty()) return null
        val failedStep = reapplyPatch(commandExecutor, HostsMixin().apply { hostList = active.joinToString(",") }) ?: return null
        eventBus.emit(Event.Cassandra.UseConfigNotApplied(version, failedStep, active))
        return "cassandra install --reinstall: $failedStep failed after the reinstall of $version"
    }
}
