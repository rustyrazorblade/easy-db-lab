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

/** How an attempt to restart the `ssh` of a recorded SOCKS5 tunnel ended. */
sealed interface TunnelSshRestart {
    /** The tunnel's ssh [sshPid] ended; the script starts a new one on the same port after its backoff. */
    data class Restarted(
        val sshPid: Long,
    ) : TunnelSshRestart

    /** The tunnel runs no ssh right now, between two runs; the next one it starts reads the current files. */
    data object NoSshRunning : TunnelSshRestart

    /** The recorded PID is gone or is not this tunnel, so nothing was signaled. */
    data object NotTheTunnel : TunnelSshRestart

    /** The tunnel's ssh [sshPid] is still running after it was asked, then forced, to end. */
    data class SshDidNotStop(
        val sshPid: Long,
    ) : TunnelSshRestart
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
 * @param requestSshRestart asks a verified tunnel script to restart its ssh, and reports whether the
 *   request was delivered; tests replace it to model a script they cannot start
 */
class TunnelProcessControl(
    private val lookup: (Long) -> ProcessHandle? = { ProcessHandle.of(it).orElse(null) },
    private val stopWait: Duration = Duration.ofSeconds(DEFAULT_STOP_WAIT_SECONDS),
    private val requestSshRestart: (ProcessHandle) -> Boolean = ::sendRestartSignal,
) {
    /** Stops the tunnel [recorded] describes, if that PID still is that tunnel. */
    fun stop(recorded: Socks5ProxyStateFile): TunnelStopResult {
        val handle = verifiedTunnel(recorded) ?: return TunnelStopResult.NotRunning
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
     * Restarts the `ssh` of the tunnel [recorded] describes and leaves the script running, so the new
     * ssh starts on the same port and reads the files as they are now. The script, once verified, is
     * asked with [Constants.Proxy.TUNNEL_RESTART_SIGNAL]: it ends its ssh and starts the next one at
     * once. Ending the ssh directly would not do, because the script takes an early exit of its first
     * ssh for a failed start and exits too. Nothing is signaled when the PID is not that tunnel.
     */
    fun restartSsh(recorded: Socks5ProxyStateFile): TunnelSshRestart {
        val handle = verifiedTunnel(recorded) ?: return TunnelSshRestart.NotTheTunnel
        val ssh =
            handle
                .children()
                .filter { isSsh(it) }
                .findFirst()
                .orElse(null) ?: return TunnelSshRestart.NoSshRunning
        return if (requestSshRestart(handle) && ends(ssh) { true }) {
            TunnelSshRestart.Restarted(ssh.pid())
        } else {
            log.warn { "The ssh [PID ${ssh.pid()}] of SOCKS5 tunnel process ${recorded.pid} did not stop" }
            TunnelSshRestart.SshDidNotStop(ssh.pid())
        }
    }

    /** The live process of the tunnel [recorded] describes, or null when the PID is gone or is not that tunnel. */
    private fun verifiedTunnel(recorded: Socks5ProxyStateFile): ProcessHandle? {
        val handle =
            lookup(recorded.pid.toLong())
                ?.takeIf { it.isAlive && it.pid() != ProcessHandle.current().pid() }
                ?: return null
        if (!isTunnel(handle, recorded)) {
            log.info { "PID ${recorded.pid} is no longer the recorded SOCKS5 tunnel; leaving it alone" }
            return null
        }
        return handle
    }

    private fun isSsh(handle: ProcessHandle): Boolean =
        handle
            .info()
            .command()
            .map { File(it).name == SSH }
            .orElse(false)

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

        /** Sends [Constants.Proxy.TUNNEL_RESTART_SIGNAL] to [script] with the system `kill`; the JDK can send only TERM and KILL. */
        fun sendRestartSignal(script: ProcessHandle): Boolean {
            val kill =
                ProcessBuilder("kill", "-${Constants.Proxy.TUNNEL_RESTART_SIGNAL}", "${script.pid()}")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            return kill.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS) && kill.exitValue() == 0
        }

        const val KILL_WAIT_SECONDS = 5L
        const val SSH = "ssh"
        const val DEFAULT_STOP_WAIT_SECONDS = 5L
    }
}
