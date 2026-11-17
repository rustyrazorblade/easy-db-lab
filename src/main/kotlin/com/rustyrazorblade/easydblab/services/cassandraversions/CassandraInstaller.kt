package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InstalledVersion
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.reason
import com.rustyrazorblade.easydblab.services.CassandraService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.shellQuote
import com.rustyrazorblade.easydblab.ssh.redactUrlCredentials

/** What the caller is doing, which decides how a version already on a node is treated. */
enum class InstallMode {
    /** `cassandra install`: a version on the disk is kept; a git build asked for with other parameters is refused. */
    INSTALL,

    /** `cassandra install --reinstall`: every node installs again, unless Cassandra runs the version there. */
    REINSTALL,

    /** `cassandra use` and the node restorer: a version on the disk is kept, with the JDK asked for. */
    USE,
}

/** What one node does with the version, decided before any node changes. */
sealed interface HostAction {
    val host: ClusterHost

    /** The node downloads (or builds) [spec]; [reinstall] replaces the install on its disk. */
    data class Download(
        override val host: ClusterHost,
        val spec: InstallSpec,
        val reinstall: Boolean,
    ) : HostAction

    /** The version is on the node's disk; [spec] is what its record becomes. */
    data class Present(
        override val host: ClusterHost,
        val spec: InstallSpec,
    ) : HostAction

    /** The node changes nothing, and the command fails for it with [error]. */
    data class Refused(
        override val host: ClusterHost,
        val error: Event.Cassandra,
    ) : HostAction
}

/**
 * The install of one version on the targeted nodes: the cluster's [spec], and what each node does,
 * in target order.
 */
class InstallPlan(
    val spec: InstallSpec,
    val actions: List<HostAction>,
) {
    /** The nodes that download or build the version. */
    val downloadHosts: List<String> get() = actions.filterIsInstance<HostAction.Download>().map { it.host.alias }

    /** True when no node is refused, so running the plan can succeed on every node. */
    val canSucceed: Boolean get() = actions.none { it is HostAction.Refused }

    /** The refusal of each refused node. */
    val refusals: List<HostAction.Refused> get() = actions.filterIsInstance<HostAction.Refused>()

    /** The JDKs the nodes will run the version on, for the nodes that are not refused. */
    val javaVersions: List<String>
        get() =
            actions
                .mapNotNull { action ->
                    when (action) {
                        is HostAction.Download -> action.spec.java
                        is HostAction.Present -> action.spec.java
                        is HostAction.Refused -> null
                    }
                }.distinct()
}

/** How one node ended. */
sealed interface HostOutcome {
    val host: ClusterHost

    /** The node has the version now, as [spec]; [downloaded] when it fetched or built it. */
    data class Ready(
        override val host: ClusterHost,
        val spec: InstallSpec,
        val downloaded: Boolean,
    ) : HostOutcome

    /** The node does not have the version (or kept its old install), reported by [error]. */
    data class Failed(
        override val host: ClusterHost,
        val error: Event.Cassandra,
    ) : HostOutcome
}

/** Every node's outcome of one [InstallPlan]. */
class InstallReport(
    val version: String,
    val outcomes: List<HostOutcome>,
) {
    val ready: List<HostOutcome.Ready> get() = outcomes.filterIsInstance<HostOutcome.Ready>()

    val failed: List<HostOutcome.Failed> get() = outcomes.filterIsInstance<HostOutcome.Failed>()

    /**
     * Writes the record of each node that has the version into [state]. A failed node keeps the
     * record it had. Called once, in one thread, after every node finished (design D3).
     */
    fun recordInto(state: ClusterState) {
        ready.forEach { state.nodes.getOrPut(it.host.alias) { NodeState() }.installed[it.spec.name] = it.spec.toRecord() }
    }

    /** The command's error for the failed nodes, or null when none failed. */
    fun failureMessage(): String? =
        failed.takeIf { it.isNotEmpty() }?.let { failures ->
            "Cassandra $version was not installed on ${failures.joinToString(", ") { it.host.alias }}:\n" +
                failures.joinToString("\n") { "  ${it.error.toDisplayString()}" }
        }
}

/**
 * The one install path of `cassandra install`, `cassandra use` and the node restorer (design D1,
 * D3, D4, D12). [plan] asks every node what it has before any node changes; [execute] runs
 * `install-cassandra-version` where the plan says to, and reports each node's outcome. Neither
 * writes the cluster state: the caller applies [InstallReport.recordInto] once, after all nodes.
 */
class CassandraInstaller(
    private val remoteOps: RemoteOperationsService,
    private val hostOperations: HostOperationsService,
    private val cassandraService: CassandraService,
    private val eventBus: EventBus,
) {
    /** The version directories under `/usr/local/cassandra` on [host], without the `current` link. */
    fun installedOn(host: ClusterHost): List<String> =
        remoteOps
            .executeRemotely(host.toHost(), "ls ${Constants.Cassandra.INSTALL_DIR}", output = false)
            .text
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != CURRENT_LINK }

    /**
     * Decides what each of [targets] does, asking every node in parallel. Changes nothing.
     *
     * @param specFor the spec of one node, given its state record (the resolver's `forNode`)
     */
    fun plan(
        spec: InstallSpec,
        request: VersionRequest,
        targets: List<ClusterHost>,
        state: ClusterState,
        mode: InstallMode,
        specFor: (NodeState?) -> InstallSpec,
    ): InstallPlan {
        val results =
            hostOperations.collectFromHosts(targets, parallel = true) { host ->
                val node = state.nodes[host.alias]
                planHost(host, specFor(node), request, node, mode)
            }
        val actions =
            results.map { (host, result) ->
                result.getOrElse {
                    HostAction.Refused(
                        host,
                        Event.Cassandra.VersionInstallFailed(host.alias, spec.name, emptyList(), "could not be checked: ${reasonOf(it)}"),
                    )
                }
            }
        return InstallPlan(spec, actions)
    }

    private fun planHost(
        host: ClusterHost,
        spec: InstallSpec,
        request: VersionRequest,
        node: NodeState?,
        mode: InstallMode,
    ): HostAction {
        val reinstall = mode == InstallMode.REINSTALL
        if (reinstall && node?.version == spec.name && cassandraService.isRunning(host.toHost()).getOrThrow()) {
            return HostAction.Refused(host, Event.Cassandra.ReinstallRefusedRunning(host.alias, spec.name))
        }
        val onDisk = spec.name in installedOn(host)
        val differences =
            node
                ?.installed
                ?.get(spec.name)
                ?.let { gitBuildDifferences(it, request) }
                .orEmpty()
        return when {
            reinstall -> HostAction.Download(host, spec, reinstall = onDisk)
            !onDisk -> HostAction.Download(host, spec, reinstall = false)
            mode == InstallMode.INSTALL && differences.isNotEmpty() ->
                HostAction.Refused(host, Event.Cassandra.VersionRecordMismatch(host.alias, spec.name, differences))
            else -> HostAction.Present(host, spec)
        }
    }

    /**
     * How [request] contradicts a recorded git build. The bits on the disk were built with the
     * recorded JDK, branch and ant flags, so a plain install cannot change them. A tarball records
     * no build parameters, so nothing contradicts it.
     */
    private fun gitBuildDifferences(
        record: InstalledVersion,
        request: VersionRequest,
    ): List<String> {
        val branch = record.branch ?: return emptyList()
        return listOfNotNull(
            difference("java", record.java, request.java),
            difference("ant flags", record.antFlags.orEmpty(), request.antFlags),
            difference("branch", branch, request.branch),
            difference("url", record.url, request.url?.let(::redactUrlCredentials)),
        )
    }

    private fun difference(
        field: String,
        recorded: String,
        requested: String?,
    ): String? = requested?.takeIf { it != recorded }?.let { "$field: recorded $recorded, requested $it" }

    /**
     * Runs [plan]: every node that downloads does so in parallel. Every node's outcome is emitted
     * after all of them finished; a node's failure never throws (see [InstallReport.failed]).
     *
     * @param reportPresent emit `VersionAlreadyInstalled` for a node that had the version
     */
    fun execute(
        plan: InstallPlan,
        reportPresent: Boolean,
    ): InstallReport {
        // Results come back in list order, so each is paired with its action by position; the
        // lambda finds its action by identity, never by alias.
        val outcomes =
            hostOperations
                .collectFromHosts(plan.actions.map { it.host }, parallel = true) { host ->
                    runAction(plan.actions.first { it.host === host })
                }.zip(plan.actions) { (host, result), action ->
                    result.getOrElse { HostOutcome.Failed(host, failedEvent(host, action, reasonOf(it))) }
                }
        outcomes.forEach { emit(it, plan.spec.name, reportPresent) }
        return InstallReport(plan.spec.name, outcomes)
    }

    /**
     * Makes the version of each of [ready] the active one, with the JDK and Python of its spec, in
     * parallel. Returns each node's outcome, in [ready] order, after emitting the failures. Changes
     * no state: the caller records the nodes that are [HostOutcome.Ready].
     */
    fun activate(ready: List<HostOutcome.Ready>): List<HostOutcome> {
        val outcomes =
            hostOperations
                .collectFromHosts(ready.map { it.host }, parallel = true) { host ->
                    val spec = ready.first { it.host === host }.spec
                    remoteOps.executeRemotely(host.toHost(), activateCommand(spec))
                }.zip(ready) { (host, result), outcome ->
                    result.fold(
                        onSuccess = { outcome },
                        onFailure = {
                            HostOutcome.Failed(
                                host,
                                Event.Cassandra.VersionActivationFailed(host.alias, outcome.spec.name, reasonOf(it)),
                            )
                        },
                    )
                }
        outcomes.filterIsInstance<HostOutcome.Failed>().forEach { eventBus.emit(it.error) }
        return outcomes
    }

    private fun runAction(action: HostAction): HostOutcome =
        when (action) {
            is HostAction.Download -> {
                // Not sent as a secret: that would hide the failure reason. The SSH client redacts a
                // URL credential from every log line, event and exception instead.
                remoteOps.executeRemotely(action.host.toHost(), installCommand(action.spec, action.reinstall))
                HostOutcome.Ready(action.host, action.spec, downloaded = true)
            }
            is HostAction.Present -> HostOutcome.Ready(action.host, action.spec, downloaded = false)
            is HostAction.Refused -> HostOutcome.Failed(action.host, action.error)
        }

    private fun emit(
        outcome: HostOutcome,
        version: String,
        reportPresent: Boolean,
    ) {
        when (outcome) {
            is HostOutcome.Ready ->
                when {
                    outcome.downloaded -> eventBus.emit(Event.Cassandra.VersionInstalled(outcome.host.alias, version))
                    reportPresent -> eventBus.emit(Event.Cassandra.VersionAlreadyInstalled(outcome.host.alias, version))
                }
            is HostOutcome.Failed -> eventBus.emit(outcome.error)
        }
    }

    /** A node whose install command failed: the URLs are those of its own spec. */
    private fun failedEvent(
        host: ClusterHost,
        action: HostAction,
        reason: String,
    ): Event.Cassandra =
        when (action) {
            is HostAction.Download -> Event.Cassandra.VersionInstallFailed(host.alias, action.spec.name, action.spec.urlsTried, reason)
            is HostAction.Present -> Event.Cassandra.VersionInstallFailed(host.alias, action.spec.name, emptyList(), reason)
            is HostAction.Refused -> action.error
        }

    private fun reasonOf(error: Throwable): String = redactUrlCredentials(error.reason())

    companion object {
        private const val CURRENT_LINK = "current"

        /** The `use-cassandra` command line for [spec]: the CLI chooses the JDK and Python (D4). */
        fun activateCommand(spec: InstallSpec): String =
            "sudo use-cassandra ${spec.name.shellQuote()} --java ${spec.java.shellQuote()} --python ${spec.python.shellQuote()}"

        /** The `install-cassandra-version` command line for [spec] (the D4 contract). */
        fun installCommand(
            spec: InstallSpec,
            reinstall: Boolean,
        ): String =
            buildList {
                add("install-cassandra-version")
                add(spec.name.shellQuote())
                add("--url ${spec.url.shellQuote()}")
                if (spec.isGitBuild) {
                    add("--branch ${spec.branch.orEmpty().shellQuote()}")
                    add("--java ${spec.java.shellQuote()}")
                    spec.antFlags?.let { add("--ant-flags ${it.shellQuote()}") }
                } else {
                    spec.fallbackUrl?.let { add("--fallback-url ${it.shellQuote()}") }
                    spec.cacheUri?.let { add("--cache-uri ${it.shellQuote()}") }
                }
                if (reinstall) add("--reinstall")
            }.joinToString(" ")
    }
}
