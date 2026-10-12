package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.Optional
import java.util.concurrent.CompletableFuture

/**
 * A stand-in for a running process, for the tunnel stop paths: a test cannot start a real
 * `edl-socks-tunnel`, and a real process can always be killed, so the refusal to stop cannot be shown
 * with one.
 *
 * A gentle signal (TERM) ends it and, as the tunnel script's trap does, its [children]. A forced one
 * (KILL) ends only it, so its children are left running, as a killed shell leaves its `ssh`.
 *
 * @param command the executable path the OS reports for it
 * @param arguments its arguments, or null when the OS reports none
 * @param endsOnSignal whether it ends when it is signaled; one that does not models a hung process
 * @param endsOnlyWhenForced whether it ignores TERM and ends only on KILL
 * @param children the processes it started, reported as its descendants
 * @param startsOnIgnoredTerm a process it starts when it ignores a TERM, as the tunnel loop starts a
 *   new ssh while a TERM goes unanswered; it is reported as a descendant from then on
 */
internal class FakeTunnelProcess(
    val pid: Long,
    command: String,
    arguments: List<String>?,
    private val endsOnSignal: Boolean = true,
    private val endsOnlyWhenForced: Boolean = false,
    children: List<FakeTunnelProcess> = emptyList(),
    private val startsOnIgnoredTerm: FakeTunnelProcess? = null,
) {
    private val exit = CompletableFuture<ProcessHandle>()
    private val started = children.toMutableList()

    /** The process that started it, reported as its parent while that one runs; none once it has ended. */
    private var parent: FakeTunnelProcess? = null

    init {
        (children + listOfNotNull(startsOnIgnoredTerm)).forEach { it.parent = this }
    }

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
            on { descendants() } doAnswer { started.filter { !it.exit.isDone }.map { it.handle }.stream() }
            on { parent() } doAnswer { Optional.ofNullable(parent?.takeIf { !it.exit.isDone }?.handle) }
            on { destroy() } doAnswer { signal(forced = false) }
            on { destroyForcibly() } doAnswer { signal(forced = true) }
        }

    private fun signal(forced: Boolean): Boolean {
        signals++
        if (endsOnlyWhenForced && !forced) startsOnIgnoredTerm?.let { started += it }
        if (!endsOnSignal || (endsOnlyWhenForced && !forced)) return true
        if (!forced) started.forEach { it.signal(forced = true) }
        exit.complete(handle)
        return true
    }

    companion object {
        /** The `ssh -N -D 127.0.0.1:<port>` an `edl-socks-tunnel` runs for [port] and [sshConfig]. */
        fun ssh(
            pid: Long,
            port: Int,
            sshConfig: String,
            endsOnSignal: Boolean = true,
        ) = FakeTunnelProcess(
            pid,
            "/usr/bin/ssh",
            listOf("-v", "-o", "ExitOnForwardFailure=yes", "-N", "-D", "127.0.0.1:$port", "-F", sshConfig, "control0"),
            endsOnSignal,
        )

        /**
         * The `edl-socks-tunnel` shell [ProcessSocksProxyService] launches for [port] and [sshConfig], as
         * the OS reports it: the shell, then the script path and the script's arguments.
         */
        fun tunnelScript(
            pid: Long,
            port: Int,
            sshConfig: String,
            endsOnSignal: Boolean = true,
            endsOnlyWhenForced: Boolean = false,
            children: List<FakeTunnelProcess> = emptyList(),
            startsOnIgnoredTerm: FakeTunnelProcess? = null,
        ) = FakeTunnelProcess(
            pid,
            "/bin/sh",
            listOf("/work/bin/${Constants.ToolWrappers.TUNNEL_SCRIPT}", "$port", sshConfig, "2", "/work/logs/socks5-proxy.log", "control0"),
            endsOnSignal,
            endsOnlyWhenForced,
            children,
            startsOnIgnoredTerm,
        )
    }
}
