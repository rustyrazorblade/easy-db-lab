package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.NodeState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.ssh.hasRedactedCredentials

/** How the restore of one replaced db node ended. */
sealed interface RestoreResult {
    /** The node runs its active version again; [node] is its new record, for the caller to save. */
    data class Restored(
        val node: NodeState,
    ) : RestoreResult

    /** The cluster never chose a version, so there is nothing to install. */
    data object NothingToRestore : RestoreResult

    /** The node does not run its version; the error event was emitted. */
    data class Failed(
        val error: Event.Cassandra,
    ) : RestoreResult
}

/**
 * Puts a replaced db node back on the Cassandra version its alias ran (design D7, #1158).
 *
 * The new instance has an empty disk, so it installs only the active version, from the recorded
 * source and URL and not a new resolution, and activates it with the recorded JDK and Python. The
 * other versions the record lists are not installed; a later `use` or `install` installs them. A
 * node with no record of its own uses the cluster default, resolved as `cassandra use` would.
 *
 * The cluster state is not written here: the caller stores [RestoreResult.Restored.node] once,
 * with its other per-node results. The configuration is not applied here either: the activated
 * version has its stock conf directory, so before Cassandra starts on the node the caller runs
 * `download-config` and `update-config` for it (as `cassandra use` does, see `reapplyPatch`).
 */
class CassandraNodeRestorer(
    private val resolver: CassandraVersionResolver,
    private val installer: CassandraInstaller,
    private val eventBus: EventBus,
) {
    /**
     * Installs and activates the version [host] ran, per [state]. Every failure is emitted and
     * returned, and the state is not written.
     *
     * @throws VersionResolutionException when a node with no install record names a version no source has
     */
    fun restore(
        host: ClusterHost,
        state: ClusterState,
    ): RestoreResult {
        val node = state.nodes[host.alias]?.takeIf { it.version.isNotBlank() }
        val spec =
            when {
                node != null -> node.installed[node.version]?.let(resolver::forRecord) ?: resolveAsUse(node, state)
                state.default.version.isNotBlank() -> resolveAsUse(state.default, state)
                else -> null
            } ?: return RestoreResult.NothingToRestore
        if (hasRedactedCredentials(spec.url)) {
            return fail(Event.Cassandra.RestoreNeedsCredential(host.alias, spec.name, spec.url))
        }
        val plan = InstallPlan(spec, listOf(HostAction.Download(host, spec, reinstall = false)))
        val installed = installer.execute(plan, reportPresent = false)
        // execute and activate emit each failure themselves.
        val outcome = installed.failed.firstOrNull() ?: installer.activate(installed.ready).single()
        return when (outcome) {
            is HostOutcome.Ready -> RestoreResult.Restored(NodeState(spec.name, spec.java, mutableMapOf(spec.name to spec.toRecord())))
            is HostOutcome.Failed -> RestoreResult.Failed(outcome.error)
        }
    }

    /** [choice] (a node with no install record, or the cluster default) resolved as `cassandra use` would. */
    private fun resolveAsUse(
        choice: NodeState,
        state: ClusterState,
    ): InstallSpec = resolver.resolve(VersionRequest(choice.version, java = choice.javaVersion.ifBlank { null }), state)

    private fun fail(error: Event.Cassandra): RestoreResult {
        eventBus.emit(error)
        return RestoreResult.Failed(error)
    }
}
