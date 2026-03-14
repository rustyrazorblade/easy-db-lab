package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.toHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService

interface ProfilingService {
    /**
     * Runs async-profiler on a host and sends the resulting profile to Pyroscope.
     *
     * @param host The cluster host to profile
     * @param args The complete list of asprof arguments (including any explicit flags)
     */
    fun profileHost(
        host: ClusterHost,
        args: List<String>,
    )
}

class DefaultProfilingService(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) : ProfilingService {
    override fun profileHost(
        host: ClusterHost,
        args: List<String>,
    ) {
        eventBus.emit(Event.Profiling.Starting(host.alias, args))
        val argString = args.joinToString(" ")
        remoteOps.executeRemotely(
            host.toHost(),
            "/usr/local/bin/flamegraph-to-pyroscope $argString",
        )
        eventBus.emit(Event.Profiling.Complete(host.alias))
    }
}
