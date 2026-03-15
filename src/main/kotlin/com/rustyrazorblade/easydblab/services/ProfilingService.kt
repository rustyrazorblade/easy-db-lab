package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Service for running async-profiler on cluster nodes and sending results to Pyroscope.
 */
interface ProfilingService {
    /**
     * Profiles the database process on the given host and sends the flamegraph to Pyroscope.
     *
     * @param host The host to profile
     * @param args Additional arguments to pass to asprof (e.g., ["-d", "30", "-e", "alloc"])
     * @return Result indicating success or failure
     */
    fun profileNode(
        host: Host,
        args: List<String>,
    ): Result<Unit>
}

class DefaultProfilingService(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) : ProfilingService {
    private val log = KotlinLogging.logger {}

    override fun profileNode(
        host: Host,
        args: List<String>,
    ): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Profiling.Starting(host.alias, args))

            val argStr = args.joinToString(" ") { it.shellQuote() }
            val command = "/usr/local/bin/flamegraph-to-pyroscope $argStr".trim()
            remoteOps.executeRemotely(host, command)

            log.info { "Profiling complete on ${host.alias}" }
            eventBus.emit(Event.Profiling.Complete(host.alias))
        }.onFailure { e ->
            eventBus.emit(Event.Profiling.Error(host.alias, e.message ?: "Unknown error"))
        }

    /**
     * Shell-quotes a string to prevent injection when embedding in a remote command.
     * Safe characters pass through unquoted; anything else is wrapped in single quotes.
     */
    private fun String.shellQuote(): String {
        if (isEmpty()) return "''"
        if (matches(Regex("[a-zA-Z0-9_./:=@%+,-]+"))) return this
        return "'" + replace("'", "'\\''") + "'"
    }
}
