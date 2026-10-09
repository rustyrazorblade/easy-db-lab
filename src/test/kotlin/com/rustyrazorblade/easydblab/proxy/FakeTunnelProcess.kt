package com.rustyrazorblade.easydblab.proxy

import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.Optional
import java.util.concurrent.CompletableFuture

/**
 * A stand-in for a running process, for the tunnel stop paths: a test cannot start a real
 * `ssh -N -D`, and a real process can always be killed, so the refusal to stop cannot be shown with one.
 *
 * @param command the executable path the OS reports for it
 * @param arguments its arguments, or null when the OS reports none
 * @param endsOnSignal whether it ends when it is signaled; one that does not models a hung process
 */
internal class FakeTunnelProcess(
    val pid: Long,
    command: String,
    arguments: List<String>?,
    private val endsOnSignal: Boolean = true,
) {
    private val exit = CompletableFuture<ProcessHandle>()

    /** How many times it was signaled, gently or forcibly. */
    var signals: Int = 0
        private set

    private val info: ProcessHandle.Info =
        mock {
            on { command() } doReturn Optional.of(command)
            on { arguments() } doReturn Optional.ofNullable(arguments?.toTypedArray())
        }

    val handle: ProcessHandle =
        mock {
            on { pid() } doReturn pid
            on { info() } doReturn info
            on { isAlive } doAnswer { !exit.isDone }
            on { onExit() } doReturn exit
            on { destroy() } doAnswer { signal() }
            on { destroyForcibly() } doAnswer { signal() }
        }

    private fun signal(): Boolean {
        signals++
        if (endsOnSignal) exit.complete(handle)
        return true
    }

    companion object {
        /** The `ssh -N -D` command line [ProcessSocksProxyService] launches for [port] and [sshConfig]. */
        fun sshTunnel(
            pid: Long,
            port: Int,
            sshConfig: String,
            endsOnSignal: Boolean = true,
        ) = FakeTunnelProcess(
            pid,
            "/usr/bin/ssh",
            listOf("-v", "-o", "ExitOnForwardFailure=yes", "-N", "-D", "$port", "-F", sshConfig, "control0"),
            endsOnSignal,
        )
    }
}
