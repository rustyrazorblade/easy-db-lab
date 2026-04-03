package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Service for managing continuous async-profiler flamegraph collection on cluster nodes.
 *
 * Profiling runs as a systemd service (`flamegraph-cassandra`) that loops indefinitely:
 * profile for FLAMEGRAPH_INTERVAL seconds, upload to Pyroscope, repeat.
 *
 * Extends SystemDServiceManager so callers can also use isRunning()/getStatus() to
 * diagnose whether profiling is active on a node.
 */
interface ProfilingService : SystemDServiceManager {
    /**
     * Starts continuous profiling on the given host.
     *
     * Writes FLAMEGRAPH_EXTRA_ARGS (and optionally FLAMEGRAPH_INTERVAL) to
     * `/etc/default/flamegraph-cassandra`, then starts the `flamegraph-cassandra` systemd unit.
     *
     * @param host The host to profile
     * @param args Extra arguments to pass to asprof (e.g. ["-e", "alloc", "-t"])
     * @param interval Upload interval in seconds, or null to use the service default (60)
     * @return Result indicating success or failure
     */
    fun startProfiling(
        host: Host,
        args: List<String>,
        interval: Int? = null,
    ): Result<Unit>
}

/**
 * Default implementation of ProfilingService.
 *
 * Extends AbstractSystemDServiceManager for free isRunning/getStatus/restart operations.
 * Overrides stop to emit domain-specific Profiling events.
 */
class DefaultProfilingService(
    remoteOps: RemoteOperationsService,
    eventBus: EventBus,
) : AbstractSystemDServiceManager("flamegraph-cassandra", remoteOps, eventBus),
    ProfilingService {
    override val log: KLogger = KotlinLogging.logger {}

    override fun startProfiling(
        host: Host,
        args: List<String>,
        interval: Int?,
    ): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Profiling.Starting(host.alias, args))

            val envLines = buildList {
                if (interval != null) add("FLAMEGRAPH_INTERVAL=$interval")
                if (args.isNotEmpty()) add("FLAMEGRAPH_EXTRA_ARGS=${args.joinToString(" ") { it.shellQuote() }}")
            }

            if (envLines.isNotEmpty()) {
                val quotedArgs = envLines.joinToString(" ") { it.shellQuote() }
                remoteOps.executeRemotely(
                    host,
                    "printf '%s\\n' $quotedArgs | sudo tee /etc/default/flamegraph-cassandra > /dev/null",
                )
            }

            remoteOps.executeRemotely(host, "sudo systemctl start flamegraph-cassandra")
            log.info { "Continuous profiling started on ${host.alias}" }
            eventBus.emit(Event.Profiling.Started(host.alias))
        }.onFailure { e ->
            eventBus.emit(Event.Profiling.Error(host.alias, e.message ?: "Unknown error"))
        }

    override fun stop(host: Host): Result<Unit> =
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
