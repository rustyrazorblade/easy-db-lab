package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Service for managing continuous async-profiler flamegraph collection on cluster nodes.
 *
 * Profiling runs as a systemd service (`flamegraph-cassandra`) that loops indefinitely:
 * profile for FLAMEGRAPH_INTERVAL seconds, upload to Pyroscope, repeat.
 */
interface ProfilingService {
    /**
     * Starts continuous profiling on the given host.
     *
     * Writes any extra asprof args to `/etc/default/flamegraph-cassandra` as
     * `FLAMEGRAPH_EXTRA_ARGS`, then starts the `flamegraph-cassandra` systemd unit.
     *
     * @param host The host to profile
     * @param args Extra arguments to pass to asprof (e.g. ["-e", "alloc", "-t"])
     * @return Result indicating success or failure
     */
    fun startProfiling(
        host: Host,
        args: List<String>,
    ): Result<Unit>

    /**
     * Stops the continuous profiler on the given host.
     *
     * @param host The host on which to stop profiling
     * @return Result indicating success or failure
     */
    fun stopProfiling(host: Host): Result<Unit>
}

class DefaultProfilingService(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) : ProfilingService {
    private val log = KotlinLogging.logger {}

    override fun startProfiling(
        host: Host,
        args: List<String>,
    ): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Profiling.Starting(host.alias, args))

            if (args.isNotEmpty()) {
                val extraArgs = args.joinToString(" ") { it.shellQuote() }
                val envContent = "FLAMEGRAPH_EXTRA_ARGS=$extraArgs"
                remoteOps.executeRemotely(
                    host,
                    "printf '%s\\n' ${envContent.shellQuote()} | sudo tee /etc/default/flamegraph-cassandra > /dev/null",
                )
            }

            remoteOps.executeRemotely(host, "sudo systemctl start flamegraph-cassandra")
            log.info { "Continuous profiling started on ${host.alias}" }
            eventBus.emit(Event.Profiling.Started(host.alias))
        }.onFailure { e ->
            eventBus.emit(Event.Profiling.Error(host.alias, e.message ?: "Unknown error"))
        }

    override fun stopProfiling(host: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Profiling.Stopping(host.alias))
            remoteOps.executeRemotely(host, "sudo systemctl stop flamegraph-cassandra")
            log.info { "Profiling stopped on ${host.alias}" }
            eventBus.emit(Event.Profiling.Stopped(host.alias))
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
