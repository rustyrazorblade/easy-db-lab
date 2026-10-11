package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** How an attempt to stop a recorded SOCKS5 tunnel ended. */
sealed interface TunnelStopResult {
    /** The tunnel process [pid] ended, and so did every ssh it ran. */
    data class Stopped(
        val pid: Int,
    ) : TunnelStopResult

    /** No tunnel was running: nothing was recorded, the PID was gone, or it now belongs to another process. */
    data object NotRunning : TunnelStopResult

    /** The tunnel process [pid], or an ssh it ran, is still running after it was asked, then forced, to end. */
    data class StopFailed(
        val pid: Int,
    ) : TunnelStopResult
}

/**
 * Finds and ends the `edl-socks-tunnel` process of a recorded SOCKS5 tunnel, and the `ssh` it runs.
 *
 * `.socks5-proxy-state` records only a PID. The tunnel can die on its own and the OS can give its
 * PID to an unrelated process, so a PID is signaled only after its process proves to be this
 * tunnel: a shell running a script named [Constants.ToolWrappers.TUNNEL_SCRIPT] whose first two
 * arguments are the recorded port and `sshConfig`. A process whose arguments the OS does not report
 * cannot prove that, so it is never signaled.
 *
 * A stop asks the script to end (TERM), which ends its `ssh`, and forces it (KILL) if it does not.
 * A killed shell leaves its children running, so every descendant of the script is ended too: a stop
 * leaves no `ssh` of the tunnel running.
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
        // Taken before the KILL, because a killed shell's children stop being its descendants.
        val children = handle.descendants().toList().toMutableSet()
        val scriptEnded =
            ends(handle) { it.destroy() } ||
                run {
                    // The loop may have started a new ssh while TERM went unanswered.
                    children += handle.descendants().toList()
                    ends(handle) { it.destroyForcibly() }
                }
        val survivors = children.filter { it.isAlive }.filterNot { child -> ends(child) { it.destroyForcibly() } }
        return when {
            scriptEnded && survivors.isEmpty() -> TunnelStopResult.Stopped(recorded.pid)
            else -> {
                log.warn {
                    "SOCKS5 tunnel process ${recorded.pid} or its ssh (${survivors.joinToString { "PID ${it.pid()}" }}) did not stop; " +
                        "it stays recorded in the proxy state file"
                }
                TunnelStopResult.StopFailed(recorded.pid)
            }
        }
    }

    /**
     * True when [handle] is the `edl-socks-tunnel` that [recorded] describes: the OS reports the
     * script path as its first argument, then the recorded port and `sshConfig`.
     */
    internal fun isTunnel(
        handle: ProcessHandle,
        recorded: Socks5ProxyStateFile,
    ): Boolean {
        val arguments =
            handle
                .info()
                .arguments()
                .orElse(null)
                ?.toList() ?: return false
        val script = arguments.firstOrNull() ?: return false
        return File(script).name == Constants.ToolWrappers.TUNNEL_SCRIPT &&
            arguments.getOrNull(1) == recorded.port.toString() &&
            arguments.getOrNull(2) == recorded.sshConfig
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

    private companion object {
        val log = KotlinLogging.logger {}
        const val DEFAULT_STOP_WAIT_SECONDS = 5L
    }
}
