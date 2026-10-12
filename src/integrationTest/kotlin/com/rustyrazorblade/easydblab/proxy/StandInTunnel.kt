package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A real process that the OS reports as the `edl-socks-tunnel` of a recorded tunnel: a shell running a
 * script of that name with the recorded port and `sshConfig` as its first two arguments. It only waits,
 * so it opens no port and starts no `ssh`.
 *
 * The CLI reuses a recorded tunnel only when its PID proves to be that script, so a test of the reuse
 * path needs a real process that passes [TunnelProcessControl]'s check; this JVM's own PID does not.
 *
 * @param directory where the script is written, under a `bin/` of its own
 * @param port the port the script is started with, as the CLI records it
 * @param sshConfig the `sshConfig` path the script is started with
 */
internal class StandInTunnel(
    directory: File,
    port: Int,
    sshConfig: String,
) : AutoCloseable {
    private val process: Process

    init {
        val script =
            File(File(directory, "stand-in-bin").apply { mkdirs() }, Constants.ToolWrappers.TUNNEL_SCRIPT).apply {
                writeText("#!/bin/sh\nsleep 300 &\nwait\n")
                setExecutable(true)
            }
        process =
            ProcessBuilder(script.absolutePath, "$port", sshConfig, "2", File(directory, "stand-in.log").absolutePath, "control0")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        awaitArguments()
    }

    /** The script's PID, the one the state file records. */
    val pid: Int get() = process.pid().toInt()

    /** Whether the script still runs. */
    val isAlive: Boolean get() = process.isAlive

    /** Waits until the OS reports the script as the first argument, which the identity check reads. */
    private fun awaitArguments() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)
        while (process
                .info()
                .arguments()
                .map { File(it.firstOrNull().orEmpty()).name != Constants.ToolWrappers.TUNNEL_SCRIPT }
                .orElse(true)
        ) {
            check(process.isAlive && System.nanoTime() < deadline) { "the stand-in tunnel script did not start" }
            Thread.sleep(POLL_MS)
        }
    }

    override fun close() {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    private companion object {
        const val LIMIT_SECONDS = 10L
        const val POLL_MS = 20L
    }
}
