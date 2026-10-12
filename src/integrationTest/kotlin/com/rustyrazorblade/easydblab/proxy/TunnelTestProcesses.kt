package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * What the integration tests that run a real `edl-socks-tunnel` share: one installed copy of the script
 * per test class, and the wait for the tunnel's `ssh`.
 *
 * Each test links its workspace's `bin/edl-socks-tunnel` to the class's one copy, so a test does not
 * exec a file the OS has never seen. On macOS the first exec of a new file can wait on a system scan,
 * which made a fresh script per test stall on a busy machine. The script still finds the workspace it
 * serves through the link, because it resolves its own directory, not the link's target.
 *
 * The wait finds the `ssh` among every live process by its `-D 127.0.0.1:<port>` arguments and its
 * parent, the script. It does not read the executable's name, which macOS does not always report for a
 * process that has only just started. When it gives up, it says what the script was doing.
 */
internal object TunnelTestProcesses {
    /** How long a test waits for the tunnel's ssh; a passing test waits only as long as ssh takes to start. */
    const val SSH_WAIT_SECONDS = 60L

    private const val POLL_MS = 100L

    /** Installs the tunnel script once, in a new directory, and returns that directory. */
    fun installScriptOnce(): File = Files.createTempDirectory("edl-tunnel-script").toFile().also { ToolWrapperInstaller().install(it) }

    /** Links [workspace]'s `bin/edl-socks-tunnel` to the copy [installScriptOnce] put in [installed]. */
    fun linkScript(
        installed: File,
        workspace: File,
    ) {
        val bin = File(workspace, Constants.ToolWrappers.DIRECTORY).apply { mkdirs() }
        val target = File(File(installed, Constants.ToolWrappers.DIRECTORY), Constants.ToolWrappers.TUNNEL_SCRIPT)
        Files.createSymbolicLink(File(bin, Constants.ToolWrappers.TUNNEL_SCRIPT).toPath(), target.toPath())
    }

    /**
     * Waits until [tunnel] runs an `ssh` for the dynamic forward on [port] that [accept] takes, and
     * returns it. The tunnel log [logFile] goes into the failure message.
     */
    fun awaitSsh(
        tunnel: ProcessHandle,
        port: Int,
        logFile: File,
        accept: (ProcessHandle) -> Boolean = { true },
    ): ProcessHandle {
        val forward = "${Constants.Proxy.TUNNEL_LISTEN_ADDRESS}:$port"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SSH_WAIT_SECONDS)
        while (true) {
            ProcessHandle
                .allProcesses()
                .filter { it.parent().map { parent -> parent.pid() == tunnel.pid() }.orElse(false) }
                .filter { arguments(it).zipWithNext().any { (option, value) -> option == "-D" && value == forward } }
                .filter(accept)
                .findFirst()
                .orElse(null)
                ?.let { return it }
            check(tunnel.isAlive) { "the tunnel script exited before it ran ssh.\n${diagnostics(tunnel, logFile)}" }
            check(System.nanoTime() < deadline) {
                "the tunnel script ran no ssh for $forward in ${SSH_WAIT_SECONDS}s.\n${diagnostics(tunnel, logFile)}"
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** The arguments the OS reports for [handle], or none. */
    fun arguments(handle: ProcessHandle): List<String> =
        handle
            .info()
            .arguments()
            .map { it.toList() }
            .orElse(emptyList())

    /**
     * What tells a lookup that missed from a script that is stuck: the launched process's own command
     * line (`nohup` while its exec has not happened), every descendant, the process count (a fork that
     * is being retried), and the tunnel log.
     */
    private fun diagnostics(
        tunnel: ProcessHandle,
        logFile: File,
    ): String {
        val descendants =
            tunnel
                .descendants()
                .toList()
                .joinToString("\n") { "  ${describe(it)} parent=${it.parent().map { p -> p.pid() }.orElse(-1)}" }
                .ifEmpty { "  none" }
        val log = logFile.takeIf { it.exists() }?.readText()?.ifBlank { null } ?: "(empty)"
        return """
            |launched process: ${describe(tunnel)} alive=${tunnel.isAlive}
            |descendants:
            |$descendants
            |live processes: ${ProcessHandle.allProcesses().count()}
            |tunnel log ${logFile.absolutePath}:
            |$log
            """.trimMargin()
    }

    private fun describe(handle: ProcessHandle): String {
        val info = handle.info()
        return "PID ${handle.pid()} command=${info.command().orElse("?")} arguments=${arguments(handle)}"
    }
}
