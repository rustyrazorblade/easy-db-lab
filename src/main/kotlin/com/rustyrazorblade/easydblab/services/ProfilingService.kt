package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.shell.shellQuote
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

private const val UNIT_NAME = "flamegraph-cassandra"
private const val SCRIPT_PATH = "/usr/local/bin/flamegraph-to-pyroscope"

/**
 * Service for managing continuous async-profiler flamegraph collection on cluster nodes.
 *
 * Profiling runs as a transient systemd unit launched via `systemd-run`. All configuration
 * (interval, asprof args) is passed as CLI arguments — no env files or service files on disk.
 * Stop with `systemctl stop flamegraph-cassandra`.
 */
interface ProfilingService {
    /**
     * Starts continuous profiling on the given host via a transient systemd unit.
     *
     * @param host The host to profile
     * @param args Extra arguments to pass to asprof (e.g. ["-e", "alloc", "-t"])
     * @param interval Upload interval in seconds, or null to use the script default (60)
     * @return Result indicating success or failure
     */
    fun startProfiling(
        host: Host,
        args: List<String>,
        interval: Int? = null,
    ): Result<Unit>

    fun stop(host: Host): Result<Unit>

    fun isRunning(host: Host): Result<Boolean>
}

/**
 * Default implementation using systemd-run for transient units.
 */
class DefaultProfilingService(
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
) : ProfilingService {
    override fun startProfiling(
        host: Host,
        args: List<String>,
        interval: Int?,
    ): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Profiling.Starting(host.alias, args))
            remoteOps.executeRemotely(host, buildSystemdRunCommand(args, interval))
            log.info { "Continuous profiling started on ${host.alias}" }
            eventBus.emit(Event.Profiling.Started(host.alias))
        }.onFailure { e ->
            eventBus.emit(Event.Profiling.Error(host.alias, e.message ?: "Unknown error"))
        }

    override fun stop(host: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Profiling.Stopping(host.alias))
            remoteOps.executeRemotely(host, "sudo systemctl stop $UNIT_NAME")
            log.info { "Profiling stopped on ${host.alias}" }
            eventBus.emit(Event.Profiling.Stopped(host.alias))
        }.onFailure { e ->
            eventBus.emit(Event.Profiling.Error(host.alias, e.message ?: "Unknown error"))
        }

    override fun isRunning(host: Host): Result<Boolean> =
        runCatching {
            val response =
                remoteOps.executeRemotely(
                    host,
                    "sudo systemctl is-active $UNIT_NAME",
                    output = false,
                )
            response.text.trim().equals("active", ignoreCase = true)
        }

    internal fun buildSystemdRunCommand(
        args: List<String>,
        interval: Int?,
    ): String =
        buildList {
            add("sudo")
            add("systemd-run")
            add("--unit=$UNIT_NAME")
            add("--")
            add(SCRIPT_PATH)
            if (interval != null) {
                add("--interval")
                add(interval.toString())
            }
            if (args.isNotEmpty()) {
                add("--")
                args.forEach { add(it.shellQuote()) }
            }
        }.joinToString(" ")
}
