package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.stream.Stream

/** How an attempt to stop a recorded SOCKS5 tunnel ended. */
sealed interface TunnelStopResult {
    /**
     * The tunnel ended: [pid] is the process that was stopped, the tunnel script with every ssh it ran,
     * or the orphaned ssh of a script that was already gone.
     */
    data class Stopped(
        val pid: Int,
    ) : TunnelStopResult

    /**
     * No tunnel was running: nothing was recorded, or the PID was gone or now belongs to another
     * process, and no orphaned ssh of the tunnel was found.
     */
    data object NotRunning : TunnelStopResult

    /**
     * The process [pid] is still running after it was asked, then forced, to end: the tunnel script,
     * or the orphaned ssh of a script that is gone.
     */
    data class StopFailed(
        val pid: Int,
    ) : TunnelStopResult
}

/** What the PID a tunnel record names is now. */
enum class RecordedTunnelStatus {
    /** The PID is the recorded `edl-socks-tunnel`, with the recorded port and `sshConfig`. */
    RUNNING,

    /** No live process holds the PID. */
    GONE,

    /** A live process holds the PID but does not prove to be the recorded script: the OS gave the PID to another program. */
    PID_REUSED,
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
 * A script killed from outside with KILL leaves its `ssh` running with no parent to find it by. So
 * when the recorded PID is gone, or now belongs to another program, the stop looks for an orphaned
 * `ssh`: one whose arguments the OS reports as the script's (`-N`, `-D 127.0.0.1:<recorded port>` and
 * `-F` with the recorded `sshConfig`), and whose parent is neither a live tunnel script nor such an
 * `ssh` itself: between `fork` and `exec`, the child an `ssh` starts for its `ProxyCommand` has the
 * same arguments. It stops each one it finds.
 *
 * @param lookup finds a live process by PID; tests replace it to model processes they cannot start
 * @param allProcesses lists the live processes searched for an orphaned `ssh`; tests replace it too
 * @param stopWait how long a process is given to end after each signal
 */
class TunnelProcessControl(
    private val lookup: (Long) -> ProcessHandle? = { ProcessHandle.of(it).orElse(null) },
    private val allProcesses: () -> Stream<ProcessHandle> = { ProcessHandle.allProcesses() },
    private val stopWait: Duration = Duration.ofSeconds(DEFAULT_STOP_WAIT_SECONDS),
) {
    /**
     * Stops the tunnel [recorded] describes: the script and its ssh when that PID still is the
     * script, otherwise any orphaned ssh it left.
     */
    fun stop(recorded: Socks5ProxyStateFile): TunnelStopResult {
        val handle = liveProcess(recorded) ?: return stopOrphanedSsh(recorded)
        if (!isTunnel(handle, recorded)) {
            log.info { "PID ${recorded.pid} is no longer the recorded SOCKS5 tunnel; leaving it alone" }
            return stopOrphanedSsh(recorded)
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
                    "SOCKS5 tunnel did not stop: script PID ${recorded.pid} alive=${!scriptEnded}; " +
                        "surviving ssh PIDs: ${survivors.joinToString { "${it.pid()}" }.ifEmpty { "none" }}; " +
                        "it stays recorded in the proxy state file"
                }
                // The PID to kill: the script while it runs, otherwise the ssh it left.
                TunnelStopResult.StopFailed(if (scriptEnded) survivors.first().pid().toInt() else recorded.pid)
            }
        }
    }

    /**
     * What the PID [recorded] names is now: the recorded `edl-socks-tunnel` ([isTunnel]), no live
     * process, or a live process that is not the tunnel, because the OS gave the PID to another program.
     */
    fun status(recorded: Socks5ProxyStateFile): RecordedTunnelStatus {
        val handle = liveProcess(recorded) ?: return RecordedTunnelStatus.GONE
        return if (isTunnel(handle, recorded)) RecordedTunnelStatus.RUNNING else RecordedTunnelStatus.PID_REUSED
    }

    /** The live process that holds the recorded PID, unless it is this JVM. */
    private fun liveProcess(recorded: Socks5ProxyStateFile): ProcessHandle? =
        lookup(recorded.pid.toLong())?.takeIf { it.isAlive && it.pid() != ProcessHandle.current().pid() }

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

    /**
     * Stops every live `ssh` that [isTunnelSsh] matches to [recorded], TERM then KILL. The tunnel
     * counts as stopped when they all end; one that does not is reported by its own PID, the one to kill.
     */
    private fun stopOrphanedSsh(recorded: Socks5ProxyStateFile): TunnelStopResult {
        val orphans =
            allProcesses()
                .filter { it.isAlive && isTunnelSsh(it, recorded) && isOrphaned(it, recorded) }
                .toList()
        if (orphans.isEmpty()) return TunnelStopResult.NotRunning
        log.info {
            "The SOCKS5 tunnel script [PID ${recorded.pid}] is gone; stopping its orphaned ssh (${orphans.joinToString {
                "PID ${it.pid()}"
            }})"
        }
        val survivors = orphans.filterNot { orphan -> ends(orphan) { it.destroy() } || ends(orphan) { it.destroyForcibly() } }
        return when (val survivor = survivors.firstOrNull()) {
            null -> TunnelStopResult.Stopped(orphans.first().pid().toInt())
            else -> {
                log.warn {
                    "The orphaned ssh of the SOCKS5 tunnel on port ${recorded.port} (${survivors.joinToString {
                        "PID ${it.pid()}"
                    }}) did not stop"
                }
                TunnelStopResult.StopFailed(survivor.pid().toInt())
            }
        }
    }

    /**
     * True when [handle] is an `ssh` the tunnel script ran for [recorded]: the executable is `ssh`, and
     * the OS reports `-N`, then `-D` followed by `127.0.0.1:<recorded port>` and `-F` followed by the
     * recorded `sshConfig`. A process whose arguments the OS does not report cannot prove that.
     */
    internal fun isTunnelSsh(
        handle: ProcessHandle,
        recorded: Socks5ProxyStateFile,
    ): Boolean {
        val info = handle.info()
        val command = info.command().orElse(null) ?: return false
        val arguments = info.arguments().orElse(null)?.toList() ?: return false
        return File(command).name == "ssh" &&
            "-N" in arguments &&
            arguments.hasPair("-D", "${Constants.Proxy.TUNNEL_LISTEN_ADDRESS}:${recorded.port}") &&
            arguments.hasPair("-F", recorded.sshConfig)
    }

    /**
     * True when no tunnel runs [handle]: its parent is gone, or is neither an `edl-socks-tunnel` nor the
     * tunnel's `ssh` that forked it.
     */
    private fun isOrphaned(
        handle: ProcessHandle,
        recorded: Socks5ProxyStateFile,
    ): Boolean = handle.parent().map { !isTunnelScript(it) && !isTunnelSsh(it, recorded) }.orElse(true)

    private fun isTunnelScript(handle: ProcessHandle): Boolean =
        handle
            .info()
            .arguments()
            .orElse(null)
            ?.firstOrNull()
            ?.let { File(it).name == Constants.ToolWrappers.TUNNEL_SCRIPT } ?: false

    private fun List<String>.hasPair(
        option: String,
        value: String,
    ): Boolean = zipWithNext().any { (first, second) -> first == option && second == value }

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
