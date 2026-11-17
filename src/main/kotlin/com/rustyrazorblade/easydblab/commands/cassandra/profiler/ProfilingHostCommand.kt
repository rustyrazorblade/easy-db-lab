package com.rustyrazorblade.easydblab.commands.cassandra.profiler

import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.CassandraProfilingService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import org.koin.core.component.inject
import picocli.CommandLine.Mixin

/**
 * Shared scaffolding for the host-scoped profiling commands.
 *
 * Every profiling verb targets Cassandra nodes — all of them by default, a subset with `--hosts` —
 * and reaches the node only through [CassandraProfilingService]. Putting both in one place keeps
 * each command down to its own decision and keeps SSH out of the command layer entirely.
 */
abstract class ProfilingHostCommand : PicoBaseCommand() {
    protected val profilingService: CassandraProfilingService by inject()
    private val hostOperationsService: HostOperationsService by inject()

    @Mixin
    var hosts = HostsMixin()

    /** Runs [action] against each targeted Cassandra node, in declaration order. */
    protected fun forEachTarget(action: (Host) -> Unit) {
        hostOperationsService.withHosts(clusterState.hosts, ServerType.Cassandra, hosts.hostList) { clusterHost ->
            action(clusterHost.toHost())
        }
    }
}
