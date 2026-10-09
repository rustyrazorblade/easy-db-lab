package com.rustyrazorblade.easydblab.proxy

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** How an attempt to stop a recorded SOCKS5 tunnel ended. */
sealed interface TunnelStopResult {
    /** The tunnel process [pid] ended. */
    data class Stopped(
        val pid: Int,
    ) : TunnelStopResult

    /** No tunnel was running: nothing was recorded, the PID was gone, or it now belongs to another process. */
    data object NotRunning : TunnelStopResult

    /** The tunnel process [pid] is still running after it was asked, then forced, to end. */
    data class StopFailed(
        val pid: Int,
    ) : TunnelStopResult
}

/**
 * Finds and ends the `ssh -N -D` process of a recorded SOCKS5 tunnel.
 *
 * `.socks5-proxy-state` records only a PID. The tunnel can die on its own and the OS can give its
 * PID to an unrelated process, so a PID is signaled only after its process proves to be this
 * tunnel: an `ssh` executable whose arguments, when the OS reports them, carry the recorded
 * `-D <port>` and `-F <sshConfig>`.
 *
 * @param lookup finds a live process by PID; tests replace it to model processes they cannot start
 * @param stopWait how long a process is given to end after each signal
 */
class TunnelProcessControl(
    private val lookup: (Long) -> ProcessHandle? = { ProcessHandle.of(it).orElse(null) },
    private val stopWait: Duration = Duration.ofSeconds(DEFAULT_STOP_WAIT_SECONDS),
) {
    /** Stops the tunnel [recorded] describes, if that PID still is that tunnel. */
    fun stop(recorded: Socks5ProxyStateFile): TunnelStopResult {
        val handle =
            lookup(recorded.pid.toLong())
                ?.takeIf { it.isAlive && it.pid() != ProcessHandle.current().pid() }
                ?: return TunnelStopResult.NotRunning
        if (!isTunnel(handle, recorded)) {
            log.info { "PID ${recorded.pid} is no longer the recorded SOCKS5 tunnel; leaving it alone" }
            return TunnelStopResult.NotRunning
        }
        return when {
            ends(handle) { it.destroy() } || ends(handle) { it.destroyForcibly() } -> TunnelStopResult.Stopped(recorded.pid)
            else -> {
                log.warn { "SOCKS5 tunnel process ${recorded.pid} did not stop; it stays recorded in the proxy state file" }
                TunnelStopResult.StopFailed(recorded.pid)
            }
        }
    }

    /** True when [handle] is the `ssh` process that [recorded] describes. */
    internal fun isTunnel(
        handle: ProcessHandle,
        recorded: Socks5ProxyStateFile,
    ): Boolean {
        val info = handle.info()
        val command = info.command().orElse(null) ?: return false
        if (File(command).name != SSH) return false
        val arguments = info.arguments().orElse(null)?.toList() ?: return true
        return arguments.containsPair("-D", recorded.port.toString()) && arguments.containsPair("-F", recorded.sshConfig)
    }

    /** Sends [signal] and reports whether the process ended within [stopWait]. */
    private fun ends(
        handle: ProcessHandle,
        signal: (ProcessHandle) -> Boolean,
    ): Boolean {
        if (!signal(handle)) return !handle.isAlive
        return try {
            handle.onExit().get(stopWait.toMillis(), TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            !handle.isAlive
        } catch (_: ExecutionException) {
            !handle.isAlive
        }
    }

    private fun List<String>.containsPair(
        flag: String,
        value: String,
    ): Boolean = indices.any { this[it] == flag && getOrNull(it + 1) == value }

    private companion object {
        val log = KotlinLogging.logger {}
        const val SSH = "ssh"
        const val DEFAULT_STOP_WAIT_SECONDS = 5L
    }
}
